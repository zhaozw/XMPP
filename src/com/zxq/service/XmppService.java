package com.zxq.service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.*;

import android.app.*;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;

import com.zxq.app.XmppApplication;
import com.zxq.app.XmppBroadcastReceiver;
import com.zxq.exception.XmppException;
import com.zxq.fragment.RecentChatFragment;
import com.zxq.util.*;
import com.zxq.vo.GroupEntry;
import com.zxq.xmpp.R;
import com.zxq.activity.BaseActivity;
import com.zxq.activity.LoginActivity;
import com.zxq.activity.MainActivity;
import com.zxq.activity.BaseActivity.BackPressHandler;
import com.zxq.app.XmppBroadcastReceiver.EventHandler;
import com.zxq.smack.SmackImpl;
import com.zxq.util.LogUtil;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smackx.muc.HostedRoom;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.RoomInfo;
import org.jivesoftware.smackx.packet.VCard;

public class XmppService extends BaseService implements EventHandler, BackPressHandler {
    public static final int CONNECTED = 0;
    public static final int DISCONNECTED = -1;
    public static final int CONNECTING = 1;
    public static final String PONG_TIMEOUT = "pong timeout";// 连接超时
    public static final String NETWORK_ERROR = "network error";// 网络错误
    public static final String LOGOUT = "logout";// 手动退出
    public static final String LOGIN_FAILED = "login failed";// 登录失败
    public static final String DISCONNECTED_WITHOUT_WARNING = "disconnected without warning";// 没有警告的断开连接

    private IBinder mBinder = new XXBinder();
    private IConnectionStatusCallback mConnectionStatusCallback;
    private SmackImpl mSmackable;
    private Thread mConnectingThread;
    private Thread mRegisterThread;
    private Handler mMainHandler = new Handler();
    private int registerResult = -1;

    private boolean mIsFirstLoginAction;
    // 自动重连 start
    private static final int RECONNECT_AFTER = 5;
    private static final int RECONNECT_MAXIMUM = 10 * 60;// 最大重连时间间隔
    private static final String RECONNECT_ALARM = "com.zxq.xx.RECONNECT_ALARM";
    // private boolean mIsNeedReConnection = false; // 是否需要重连
    private int mConnectedState = DISCONNECTED; // 是否已经连接
    private int mReconnectTimeout = RECONNECT_AFTER;
    private Intent mAlarmIntent = new Intent(RECONNECT_ALARM);
    private PendingIntent mPAlarmIntent;
    private BroadcastReceiver mAlarmReceiver = new ReconnectAlarmReceiver();
    // 自动重连 end
    private ActivityManager mActivityManager;
    private HashSet<String> mIsBoundTo = new HashSet<String>();// 用来保存当前正在聊天对象的数组

    /**
     * 注册注解面和聊天界面时连接状态变化回调
     *
     * @param cb
     */
    public void registerConnectionStatusCallback(IConnectionStatusCallback cb) {
        mConnectionStatusCallback = cb;
    }

    public void unRegisterConnectionStatusCallback() {
        mConnectionStatusCallback = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        LogUtil.i(XmppService.class, "[SERVICE] onBind");
        String chatPartner = intent.getDataString();
        if ((chatPartner != null)) {
            mIsBoundTo.add(chatPartner);
        }
        String action = intent.getAction();
        if (!TextUtils.isEmpty(action) && TextUtils.equals(action, LoginActivity.LOGIN_ACTION)) {
            mIsFirstLoginAction = true;
        } else {
            mIsFirstLoginAction = false;
        }
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        String chatPartner = intent.getDataString();
        if ((chatPartner != null)) {
            mIsBoundTo.add(chatPartner);
        }
        String action = intent.getAction();
        if (!TextUtils.isEmpty(action) && TextUtils.equals(action, LoginActivity.LOGIN_ACTION)) {
            mIsFirstLoginAction = true;
        } else {
            mIsFirstLoginAction = false;
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        String chatPartner = intent.getDataString();
        if ((chatPartner != null)) {
            mIsBoundTo.remove(chatPartner);
        }
        return true;
    }

    public void callbackWhenAddRoster(String name) {
        RecentChatFragment.getRecentChatFragment().callbackWhenAddRoster(name);
    }

    public void callbackWhenUpdateRoster(String name) {
        RecentChatFragment.getRecentChatFragment().callbackWhenUpdateRoster(name);
    }

    public void callbackWhenDeleteRoster(String name) {
        RecentChatFragment.getRecentChatFragment().callbackWhenDeleteRoster(name);
    }



    public class XXBinder extends Binder {
        public XmppService getService() {
            return XmppService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        XmppBroadcastReceiver.mListeners.add(this);
        BaseActivity.mListeners.add(this);
        mActivityManager = ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE));
        mPAlarmIntent = PendingIntent.getBroadcast(this, 0, mAlarmIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        registerReceiver(mAlarmReceiver, new IntentFilter(RECONNECT_ALARM));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null && TextUtils.equals(intent.getAction(), XmppBroadcastReceiver.BOOT_COMPLETED_ACTION)) {
            String account = PreferenceUtils.getPrefString(XmppService.this, PreferenceConstants.ACCOUNT, "");
            String password = PreferenceUtils.getPrefString(XmppService.this, PreferenceConstants.PASSWORD, "");
            if (!TextUtils.isEmpty(account) && !TextUtils.isEmpty(password))
                login(account, password);
        }
        mMainHandler.removeCallbacks(monitorStatus);
        mMainHandler.postDelayed(monitorStatus, 1000L);// 检查应用是否在后台运行线程
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        XmppBroadcastReceiver.mListeners.remove(this);
        BaseActivity.mListeners.remove(this);
        ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(mPAlarmIntent);// 取消重连闹钟
        unregisterReceiver(mAlarmReceiver);// 注销广播监听
        logout();
    }

    // 登录
    public void login(final String account, final String password) {
        if (NetUtil.getNetworkState(this) == NetUtil.NETWORN_NONE) {
            connectionFailed(NETWORK_ERROR);
            return;
        }
        if (mConnectingThread != null) {
            LogUtil.i("a connection is still goign on!");
            return;
        }
        mConnectingThread = new Thread() {
            @Override
            public void run() {
                try {
                    postConnecting();
                    mSmackable = new SmackImpl(XmppService.this);
                    if (mSmackable.login(account, password)) {
                        // 登陆成功
                        PreferenceUtils.setPrefString(XmppService.this, PreferenceConstants.ACCOUNT, account);// 帐号是一直保存的
                        PreferenceUtils.setPrefString(XmppService.this, PreferenceConstants.PASSWORD, password);
                        postConnectionScuessed();
                    } else {
                        // 登陆失败
                        postConnectionFailed(LOGIN_FAILED);
                    }
                } catch (XmppException e) {
                    String message = e.getLocalizedMessage();
                    // 登陆失败
                    if (e.getCause() != null)
                        message += "\n" + e.getCause().getLocalizedMessage();
                    postConnectionFailed(message);
                    LogUtil.i(XmppService.class, "YaximXMPPException in doConnect():");
                    e.printStackTrace();
                } finally {
                    if (mConnectingThread != null)
                        synchronized (mConnectingThread) {
                            mConnectingThread = null;
                        }
                }
            }

        };
        mConnectingThread.start();
    }

    public interface IRegisterCallBack {
        void onRegisterResult(int resultCode);
    }

    public void registerAccount(final String account, final String password, final IRegisterCallBack iRegisterCallBack) {
        if (NetUtil.getNetworkState(this) == NetUtil.NETWORN_NONE) {
            connectionFailed(NETWORK_ERROR);
            iRegisterCallBack.onRegisterResult(-1);
        }
        if (mRegisterThread != null) {
            LogUtil.i("a connection is still goign on!");
            iRegisterCallBack.onRegisterResult(-1);
        }
        mRegisterThread = new Thread() {
            @Override
            public void run() {
                mSmackable = new SmackImpl(XmppService.this);
                registerResult = mSmackable.registerAccount(account, password);
                iRegisterCallBack.onRegisterResult(registerResult);
            }
        };
        mRegisterThread.start();
    }

    // 退出
    public boolean logout() {
        // mIsNeedReConnection = false;// 手动退出就不需要重连闹钟了
        boolean isLogout = false;
        if (mConnectingThread != null) {
            synchronized (mConnectingThread) {
                try {
                    mConnectingThread.interrupt();
                    mConnectingThread.join(50);
                } catch (InterruptedException e) {
                    LogUtil.e("doDisconnect: failed catching connecting thread");
                } finally {
                    mConnectingThread = null;
                }
            }
        }
        if (mSmackable != null) {
            isLogout = mSmackable.logout();
            mSmackable = null;
        }
        connectionFailed(LOGOUT);// 手动退出
        return isLogout;
    }


    // 是否连接上服务器
    public boolean isAuthenticated() {
        if (mSmackable != null) {
            return mSmackable.isAuthenticated();
        }

        return false;
    }

    // 清除通知栏
    public void clearNotifications(String Jid) {
        clearNotification(Jid);
    }

    /**
     * 非UI线程连接失败反馈
     *
     * @param reason
     */
    public void postConnectionFailed(final String reason) {
        mMainHandler.post(new Runnable() {
            public void run() {
                connectionFailed(reason);
            }
        });
    }

    // 设置连接状态
    public void setStatusFromConfig() {
        mSmackable.setStatusFromConfig();
    }

    // 新增联系人
    public void addRosterItem(String user, String alias, String group) {
        try {
            mSmackable.addRosterItem(user, alias, group);
        } catch (XmppException e) {
            ToastUtil.showShort(this, e.getMessage());
            LogUtil.e("exception in addRosterItem(): " + e.getMessage());
        }
    }

    // 新增分组
    public void addRosterGroup(String group) {
        mSmackable.addRosterGroup(group);
    }

    // 删除联系人
    public void removeRosterItem(String user) {
        try {
            mSmackable.removeRosterItem(user);
        } catch (XmppException e) {
            ToastUtil.showShort(this, e.getMessage());
            LogUtil.e("exception in removeRosterItem(): " + e.getMessage());
        }
    }

    // 将联系人移动到其他组
    public void moveRosterItemToGroup(String user, String group) {
        try {
            mSmackable.moveRosterItemToGroup(user, group);
        } catch (XmppException e) {
            ToastUtil.showShort(this, e.getMessage());
            LogUtil.e("exception in moveRosterItemToGroup(): " + e.getMessage());
        }
    }

    // 重命名联系人
    public void renameRosterItem(String user, String newName) {
        try {
            mSmackable.renameRosterItem(user, newName);
        } catch (XmppException e) {
            ToastUtil.showShort(this, e.getMessage());
            LogUtil.e("exception in renameRosterItem(): " + e.getMessage());
        }
    }

    // 重命名组
    public void renameRosterGroup(String group, String newGroup) {
        mSmackable.renameRosterGroup(group, newGroup);
    }

    public void requestAuthorizationForRosterItem(String user) {
        mSmackable.requestAuthorizationForRosterItem(user);
    }

    /**
     * UI线程反馈连接失败
     *
     * @param reason
     */
    private void connectionFailed(String reason) {
        LogUtil.i(XmppService.class, "connectionFailed: " + reason);
        mConnectedState = DISCONNECTED;// 更新当前连接状态
        // if (mSmackable != null)
        // mSmackable.setStatusOffline();// 将所有联系人标记为离线
        if (TextUtils.equals(reason, LOGOUT)) {// 如果是手动退出
            ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(mPAlarmIntent);
            return;
        }
        // 回调
        if (mConnectionStatusCallback != null) {
            mConnectionStatusCallback.connectionStatusChanged(mConnectedState, reason);
            if (mIsFirstLoginAction)// 如果是第一次登录,就算登录失败也不需要继续
                return;
        }

        // 无网络连接时,直接返回
        if (NetUtil.getNetworkState(this) == NetUtil.NETWORN_NONE) {
            ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(mPAlarmIntent);
            return;
        }

        String account = PreferenceUtils.getPrefString(XmppService.this, PreferenceConstants.ACCOUNT, "");
        String password = PreferenceUtils.getPrefString(XmppService.this, PreferenceConstants.PASSWORD, "");
        // 无保存的帐号密码时，也直接返回
        if (TextUtils.isEmpty(account) || TextUtils.isEmpty(password)) {
            LogUtil.d("account = null || password = null");
            return;
        }
        // 如果不是手动退出并且需要重新连接，则开启重连闹钟
        if (PreferenceUtils.getPrefBoolean(this, PreferenceConstants.AUTO_RECONNECT, true)) {
            LogUtil.d("connectionFailed(): registering reconnect in " + mReconnectTimeout + "s");
            ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + mReconnectTimeout * 1000, mPAlarmIntent);
            mReconnectTimeout = mReconnectTimeout * 2;
            if (mReconnectTimeout > RECONNECT_MAXIMUM)
                mReconnectTimeout = RECONNECT_MAXIMUM;
        } else {
            ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(mPAlarmIntent);
        }


    }

    private void postConnectionScuessed() {
        mMainHandler.post(new Runnable() {
            public void run() {
                connectionScuessed();
            }

        });
    }

    private void connectionScuessed() {
        mConnectedState = CONNECTED;// 已经连接上
        mReconnectTimeout = RECONNECT_AFTER;// 重置重连的时间

        if (mConnectionStatusCallback != null)
            mConnectionStatusCallback.connectionStatusChanged(mConnectedState, "");
    }

    // 连接中，通知界面线程做一些处理
    private void postConnecting() {
        mMainHandler.post(new Runnable() {
            public void run() {
                connecting();
            }
        });
    }

    private void connecting() {
        mConnectedState = CONNECTING;// 连接中
        if (mConnectionStatusCallback != null)
            mConnectionStatusCallback.connectionStatusChanged(mConnectedState, "");
    }

    // 收到新消息
    public void newMessage(final String from, final String message) {
        mMainHandler.post(new Runnable() {
            public void run() {
                if (!PreferenceUtils.getPrefBoolean(XmppService.this, PreferenceConstants.SCLIENTNOTIFY, false))
                    MediaPlayer.create(XmppService.this, R.raw.office).start();
                if (!isAppOnForeground())
                    notifyClient(from, mSmackable.getNameForJID(from), message, !mIsBoundTo.contains(from));
                // T.showLong(XXService.this, from + ": " + message);

            }

        });
    }

    // 联系人改变
    public void rosterChanged() {
        // gracefully handle^W ignore events after a disconnect
        if (mSmackable == null)
            return;
        if (mSmackable != null && !mSmackable.isAuthenticated()) {
            LogUtil.i("rosterChanged(): disconnected without warning");
            connectionFailed(DISCONNECTED_WITHOUT_WARNING);
        }
    }

    /**
     * 更新通知栏
     *
     * @param message
     */
    public void updateServiceNotification(String message) {
        if (!PreferenceUtils.getPrefBoolean(this, PreferenceConstants.FOREGROUND, true))
            return;
        String title = PreferenceUtils.getPrefString(this, PreferenceConstants.ACCOUNT, "");
        Notification n = new Notification(R.drawable.login_default_avatar, title, System.currentTimeMillis());
        n.flags = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;

        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        n.contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        n.setLatestEventInfo(this, title, message, n.contentIntent);
        startForeground(SERVICE_NOTIFICATION, n);
    }

    // 判断程序是否在后台运行的任务
    Runnable monitorStatus = new Runnable() {
        public void run() {
            try {
                LogUtil.i("monitorStatus is running... " + getPackageName());
                mMainHandler.removeCallbacks(monitorStatus);
                // 如果在后台运行并且连接上了
                if (!isAppOnForeground()) {
                    LogUtil.i("app run in background...");
                    updateServiceNotification(getString(R.string.run_bg_ticker));
                    return;
                } else {
                    stopForeground(true);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    public boolean isAppOnForeground() {
        List<RunningTaskInfo> taskInfos = mActivityManager.getRunningTasks(1);
        if (taskInfos.size() > 0 && TextUtils.equals(getPackageName(), taskInfos.get(0).topActivity.getPackageName())) {
            return true;
        }
        return false;
    }

    // 自动重连广播
    private class ReconnectAlarmReceiver extends BroadcastReceiver {
        public void onReceive(Context ctx, Intent i) {
            LogUtil.d("Alarm received.");
            if (!PreferenceUtils.getPrefBoolean(XmppService.this, PreferenceConstants.AUTO_RECONNECT, true)) {
                return;
            }
            if (mConnectedState != DISCONNECTED) {
                LogUtil.d("Reconnect attempt aborted: we are connected again!");
                return;
            }
            String account = PreferenceUtils.getPrefString(XmppService.this, PreferenceConstants.ACCOUNT, "");
            String password = PreferenceUtils.getPrefString(XmppService.this, PreferenceConstants.PASSWORD, "");
            if (TextUtils.isEmpty(account) || TextUtils.isEmpty(password)) {
                LogUtil.d("account = null || password = null");
                return;
            }
            login(account, password);
        }
    }

    @Override
    public void onNetChange() {
        if (NetUtil.getNetworkState(this) == NetUtil.NETWORN_NONE) {// 如果是网络断开，不作处理
            connectionFailed(NETWORK_ERROR);
            return;
        }
        if (isAuthenticated())// 如果已经连接上，直接返回
            return;
        String account = PreferenceUtils.getPrefString(XmppService.this, PreferenceConstants.ACCOUNT, "");
        String password = PreferenceUtils.getPrefString(XmppService.this, PreferenceConstants.PASSWORD, "");
        if (TextUtils.isEmpty(account) || TextUtils.isEmpty(password))// 如果没有帐号，也直接返回
            return;
        if (!PreferenceUtils.getPrefBoolean(this, PreferenceConstants.AUTO_RECONNECT, true))// 不需要重连
            return;
        login(account, password);// 重连
    }

    @Override
    public void activityOnResume() {
        LogUtil.i("activity onResume ...");
        mMainHandler.post(monitorStatus);
    }

    @Override
    public void activityOnPause() {
        LogUtil.i("activity onPause ...");
        mMainHandler.postDelayed(monitorStatus, 1000L);
    }

    //=============个人信息区===============
    public VCard getMyInfo() {
        if(mSmackable != null){
            return mSmackable.getMyVcardInfo();
        }
return null;
    }

    public String getXmppUserName() {
        return mSmackable.getXmppUserName();
    }

    public boolean saveMyInfo(String name, String signature, String qq, String phone, String email) {
        return mSmackable.putPersonInfoInVcardAndSaved(name, signature, qq, phone, email);
    }

    public VCard getUserAvatarByName(String name) {
        return mSmackable.getVcardInfo(name);
    }

    public int alterPersonPassword(String oldPassword, String newPassword, String againNewPassword) {
        String recordPassword = PreferenceUtils.getPrefString(XmppService.this, PreferenceConstants.PASSWORD, "");

        if("".equals(oldPassword.trim())){
            return -4;//原密码不能为空
        }
        if("".equals(newPassword.trim())){
            return -5;//新不能为空
        }
        if("".equals(againNewPassword.trim())){
            return -6;//确认不能为空
        }
        if(newPassword.trim().length() <6){
            return -7;//密码长度必须大于6位不能为空
        }
        if (newPassword == null && !(newPassword.trim().equals(againNewPassword.trim()))) {
            return -2;//新密码和确认密码不一致
        }
        if (!recordPassword.trim().equals(oldPassword.trim())) {
            return -1;//原密码不正确
        }
        if (newPassword.trim().equals(oldPassword.trim())) {
            return 0;//新密码和旧密码不能相同
        }
        boolean ok = mSmackable.alterPassword(newPassword);
        if (ok) {
            return 1;//修改成功
        } else {
            return -3;//未知异常
        }
    }

    public void changeImage(Activity activity,String filePath,Runnable successRunnable,Runnable failRunnable){//此方法需要异步加载，加载完成再更新主UI线程
        File file = new File(filePath);
        try {
            mSmackable.changeImage(file);
            activity.runOnUiThread(successRunnable);
        } catch (XMPPException e) {
            e.printStackTrace();
            activity.runOnUiThread(failRunnable);
        } catch (IOException e) {
            e.printStackTrace();
            activity.runOnUiThread(failRunnable);
        }
    }

    //============FriendChat功能区=============
    // 发送消息
    public void sendMessage(String user, String message) {
        if (mSmackable != null)
            mSmackable.sendMessage(user, message);
        else
            SmackImpl.saveAsOfflineMessage(getContentResolver(), user, message);
    }


    //============GroupChat功能区================

    public List<GroupEntry> getGroupEntryList(){
        List<GroupEntry> userJoinGroupChatRoom = mSmackable.getUserJoinGroupChatRoom();
        return userJoinGroupChatRoom;
    }

    public RoomInfo queryGroupChatRoomInfoByJID(String jid){
        return  mSmackable.queryGroupChatRoomInfoByJID(jid);
    }

    public MultiUserChat getMultiUserChatByRoomJID(String jid){
        return  mSmackable.getMultiUserChatByRoomJID(jid);
    }

    public void sendGroupChat(MultiUserChat nowRoomChat,String msg){
       mSmackable.sendGroupChat(nowRoomChat,msg);
    }


}
