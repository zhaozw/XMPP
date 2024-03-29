package com.zxq.activity;

import android.app.Dialog;
import android.content.AsyncQueryHandler;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.zxq.adapter.GroupChatAdapter;
import com.zxq.adapter.GroupChatListAdapter;
import com.zxq.adapter.RosterChooseAdapter;
import com.zxq.app.XmppApplication;
import com.zxq.db.GroupChatProvider;
import com.zxq.db.GroupChatProvider.GroupChatConstants;
import com.zxq.fragment.GroupChatFragment;
import com.zxq.service.IConnectionStatusCallback;
import com.zxq.service.XmppService;
import com.zxq.ui.emoji.EmojiKeyboard;
import com.zxq.ui.emoji.EmojiKeyboard.EventListener;
import com.zxq.ui.swipeback.SwipeBackActivity;
import com.zxq.ui.xlistview.MsgListView;
import com.zxq.ui.xlistview.MsgListView.IXListViewListener;
import com.zxq.util.*;
import com.zxq.vo.GroupChat;
import com.zxq.xmpp.R;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.packet.VCard;

import java.util.*;

public class GroupChatActivity extends SwipeBackActivity implements OnTouchListener, OnClickListener, IXListViewListener, IConnectionStatusCallback {
	private MsgListView mMsgListView;// 对话ListView
	private boolean mIsFaceShow = false;// 是否显示表情
	private Button mSendMsgBtn;// 发送消息button
	private ImageButton mFaceSwitchBtn;// 切换键盘和表情的button
	private TextView mTitleNameView;// 标题栏
	private EditText mChatEditText;// 消息输入框
	private EmojiKeyboard mFaceRoot;// 表情父容器
	private WindowManager.LayoutParams mWindowNanagerParams;
	private InputMethodManager mInputMethodManager;
	private List<String> mFaceMapKeys;// 表情对应的字符串数组
	private String mRoomName = null;
    private String mRoomJID = null;// 当前聊天用户的ID
    private MultiUserChat multiUserChat;
    private  List<GroupChat> arrayList = new ArrayList<GroupChat>();
    private GroupChatListAdapter groupChatListAdapter;
    private String userName;
    private String passWord;

    private static final String[] PROJECTION_FROM = new String[] {GroupChatConstants._ID, GroupChatConstants.DATE, GroupChatConstants.DIRECTION, GroupChatConstants.JID, GroupChatConstants.RoomJID, GroupChatConstants.MESSAGE};// 查询字段

	private XmppService mXmppService;// Main服务
	ServiceConnection mServiceConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			mXmppService = ((XmppService.XXBinder) service).getService();
			mXmppService.registerConnectionStatusCallback(GroupChatActivity.this);
			// 如果没有连接上，则重新连接xmpp服务器
			if (!mXmppService.isAuthenticated()) {
				String usr = PreferenceUtils.getPrefString(GroupChatActivity.this, PreferenceConstants.ACCOUNT, "");
				String password = PreferenceUtils.getPrefString(GroupChatActivity.this, PreferenceConstants.PASSWORD, "");
				mXmppService.login(usr, password);
			}
            initData();// 初始化数据
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			mXmppService.unRegisterConnectionStatusCallback();
			mXmppService = null;
		}

	};
    private ImageButton mGroupSettingBtn;
    private int REQUEST_CODE_INVITE = 0X1;
    private int REQUEST_CODE_INFO_CHANGE = 0X2;
    private int REQUEST_CODE_KILL_MENBER = 0X3;

    /**
	 * 解绑服务
	 */
	private void unbindXMPPService() {
		try {
			unbindService(mServiceConnection);
		} catch (IllegalArgumentException e) {
			LogUtil.e("Service wasn't bound!");
		}
	}

	/**
	 * 绑定服务
	 */
	private void bindXMPPService() {
		Intent mServiceIntent = new Intent(this, XmppService.class);
		bindService(mServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_group_chat);

		initView();// 初始化view
  	}

	@Override
	protected void onResume() {
		super.onResume();
	}

	@Override
	protected void onPause() {
		super.onPause();
	}




	@Override
	protected void onDestroy() {
		super.onDestroy();

		if (hasWindowFocus())
			unbindXMPPService();// 解绑服务

	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		// 窗口获取到焦点时绑定服务，失去焦点将解绑
		if (hasFocus)
			bindXMPPService();
		else
			unbindXMPPService();
	}

	private void initData() {
        String jid = getIntent().getStringExtra(GroupChatFragment.GROUP_CHAT_ROOM_JID);
        String name = mXmppService.getXmppUserName();
        userName = name.substring(0,name.indexOf("@"));
        passWord = getIntent().getStringExtra(GroupChatFragment.GROUP_CHAT_ROOM_PASD);
        RoomInfo roomInfo = mXmppService.queryGroupChatRoomInfoByJID(jid);
        mRoomName = roomInfo.getSubject();
        multiUserChat = mXmppService.getMultiUserChatByRoomJID(jid);
        mTitleNameView.setText(mRoomName);
        DiscussionHistory history = new DiscussionHistory();
        history.setMaxStanzas(8);
        try {
            multiUserChat.join(userName,passWord,history,3000);
        } catch (XMPPException e) {
            e.printStackTrace();
        }
        multiUserChat.addMessageListener(new PacketListener() {
            @Override
            public void processPacket(Packet packet) {
                final Message message = (Message) packet;
                System.out.println(message.getFrom() + " : " + message.getBody());
                GroupChatActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        GroupChat groupChat = new GroupChat();
                        groupChat.jid = message.getFrom().substring(message.getFrom().indexOf("/") + 1);
                        groupChat.come = groupChat.jid.equals(userName) ? 1 : 0;
                        groupChat.date = new Date().toLocaleString();
                        groupChat.dateMilliseconds = new Date().getTime();
                        groupChat.message = message.getBody();
                        groupChat.roomJid = message.getTo();
                        arrayList.add(groupChat);
                        groupChatListAdapter.notifyDataSetChanged();
                        mMsgListView.setSelection(groupChatListAdapter.getCount() - 1);
                        LogUtil.e("===============================", message.toXML());
                    }
                });
            }
        });
        multiUserChat.addSubjectUpdatedListener(new SubjectUpdatedListener() {
            @Override
            public void subjectUpdated(String subject, String from) {
                mTitleNameView.setText(subject);
            }
        });
        //TODO:======待考虑实现验证是否为管理员======
       // ToastUtil.showShort(this, "" + isAdmin(multiUserChat));
        // 将表情map的key保存在数组中
		Set<String> keySet = XmppApplication.getInstance().getFaceMap().keySet();
		mFaceMapKeys = new ArrayList<String>();
		mFaceMapKeys.addAll(keySet);

        mGroupSettingBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                final Dialog groupChatMenuDialog = DialogUtil.getGroupChatMenuDialog(GroupChatActivity.this);
                Button btnInvite = (Button) groupChatMenuDialog.findViewById(R.id.dialog_menu_btn_group_chat_invite);
                Button btnInfoChange = (Button) groupChatMenuDialog.findViewById(R.id.dialog_menu_btn_group_chat_info_change);
                Button btnKillMenber = (Button) groupChatMenuDialog.findViewById(R.id.dialog_menu_btn_group_chat_kill_menber);
                btnInvite.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent();
                        intent.setClass(GroupChatActivity.this, CurrentUserChooseActivity.class);
                        GroupChatActivity.this.startActivityForResult(intent,REQUEST_CODE_INVITE);
                        groupChatMenuDialog.dismiss();
                    }
                });

                btnInfoChange.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent();
                        intent.setClass(GroupChatActivity.this, EditGroupInfoActivity.class);
                        GroupChatActivity.this.startActivityForResult(intent,REQUEST_CODE_INFO_CHANGE);
                        groupChatMenuDialog.dismiss();
                    }
                });

                btnKillMenber.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent();
                        intent.setClass(GroupChatActivity.this, GroupOccupantsActivity.class);
                        GroupChatActivity.this.startActivityForResult(intent,REQUEST_CODE_KILL_MENBER);
                        groupChatMenuDialog.dismiss();
                    }
                });
                groupChatMenuDialog.show();
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        multiUserChat.leave();
    }

    private boolean isAdmin(MultiUserChat muc) {
        //TODO:待考虑实现验证是否为管理员
        boolean isAdmin = false;
        LogUtil.e("======isAdmin======", mXmppService.getXmppUserName());
        //muc.a
//        try {
//            Collection<Affiliate> admins = muc.getAdmins();
//            Iterator<Affiliate> iterator = admins.iterator();
//            while (iterator.hasNext()){
//                Affiliate next = iterator.next();
//                String adminJid = next.getJid();
//                LogUtil.e("======isAdmin======|||",adminJid);
//               if(userName.equals(adminJid)){
//                   isAdmin = true;
//               }
//            }
//        } catch (XMPPException e) {
//            e.printStackTrace();
//        }

        try {
            Collection<Affiliate> owners =  muc.getOwners();
            Iterator<Affiliate> iterator = owners.iterator();
            while (iterator.hasNext()){
                Affiliate next = iterator.next();
                String adminJid = next.getJid();
                LogUtil.e("======isAdmin======|||",adminJid);
                if(userName.equals(adminJid)){
                    isAdmin = true;
                }
            }
        } catch (XMPPException e) {
            e.printStackTrace();
        }

        return isAdmin;
    }

    /**
	 * 设置聊天的Adapter
	 */
	private void setChatWindowAdapter() {
		String selection = GroupChatConstants.RoomJID + "='" + mRoomJID + "'";
		// 异步查询数据库
		new AsyncQueryHandler(getContentResolver()) {

			@Override
			protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
				ListAdapter adapter = new GroupChatAdapter(GroupChatActivity.this, cursor, PROJECTION_FROM);
				mMsgListView.setAdapter(adapter);
				mMsgListView.setSelection(adapter.getCount() - 1);
			}

		}.startQuery(0, null, GroupChatProvider.CONTENT_URI, PROJECTION_FROM, selection, null, null);
	}

	private void initView() {
		mInputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		mWindowNanagerParams = getWindow().getAttributes();

		mMsgListView = (MsgListView) findViewById(R.id.msg_listView);
		// 触摸ListView隐藏表情和输入法
		mMsgListView.setOnTouchListener(this);
		mMsgListView.setPullLoadEnable(false);
		mMsgListView.setXListViewListener(this);
		mSendMsgBtn = (Button) findViewById(R.id.send);
		mFaceSwitchBtn = (ImageButton) findViewById(R.id.face_switch_btn);
        mGroupSettingBtn = (ImageButton) findViewById(R.id.btn_group_setting);
		mChatEditText = (EditText) findViewById(R.id.group_input);
		mFaceRoot = (EmojiKeyboard) findViewById(R.id.face_ll);
        groupChatListAdapter = new GroupChatListAdapter(this,arrayList);
        mMsgListView.setAdapter(groupChatListAdapter);
		mFaceRoot.setEventListener(new EventListener() {

			@Override
			public void onEmojiSelected(String res) {
				EmojiKeyboard.input(mChatEditText, res);
			}

			@Override
			public void onBackspace() {
				EmojiKeyboard.backspace(mChatEditText);
			}
		});
		mChatEditText.setOnTouchListener(this);
		mTitleNameView = (TextView) findViewById(R.id.ivTitleName);
		mChatEditText.setOnKeyListener(new OnKeyListener() {

			@Override
			public boolean onKey(View v, int keyCode, KeyEvent event) {
				if (keyCode == KeyEvent.KEYCODE_BACK) {
					if (mWindowNanagerParams.softInputMode == WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE || mIsFaceShow) {
						mFaceRoot.setVisibility(View.GONE);
						mIsFaceShow = false;
						return true;
					}
				}
				return false;
			}
		});
		mChatEditText.addTextChangedListener(new TextWatcher() {

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				if (s.length() > 0) {
					mSendMsgBtn.setEnabled(true);
				} else {
					mSendMsgBtn.setEnabled(false);
				}
			}
		});
		mFaceSwitchBtn.setOnClickListener(this);
		mSendMsgBtn.setOnClickListener(this);

	}

	@Override
	public void onRefresh() {
		mMsgListView.stopRefresh();
	}

	@Override
	public void onLoadMore() {
		// do nothing
	}

	@Override
	public void onClick(View v) {
		int id = v.getId();
		if (id == R.id.face_switch_btn) {
			if (!mIsFaceShow) {
				mInputMethodManager.hideSoftInputFromWindow(mChatEditText.getWindowToken(), 0);
				try {
					Thread.sleep(80);// 解决此时会黑一下屏幕的问题
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				mFaceRoot.setVisibility(View.VISIBLE);
				mFaceSwitchBtn.setImageResource(R.drawable.aio_keyboard);
				mIsFaceShow = true;
			} else {
				mFaceRoot.setVisibility(View.GONE);
				mInputMethodManager.showSoftInput(mChatEditText, 0);
				mFaceSwitchBtn.setImageResource(R.drawable.qzone_edit_face_drawable);
				mIsFaceShow = false;
			}
		} else if (id == R.id.send) {// 发送消息
			sendMessageIfNotNull();
		}
	}

    private void sendMessageIfNotNull() {
        if (mChatEditText.getText().length() >= 1) {
            if (mXmppService != null) {
                mXmppService.sendGroupChat(multiUserChat,mChatEditText.getText().toString());
                if (!mXmppService.isAuthenticated())
                    ToastUtil.showShort(this, "消息已经保存随后发送");
            }
            mChatEditText.setText("");
            mSendMsgBtn.setEnabled(false);
        }
    }

	@Override
	public boolean onTouch(View v, MotionEvent event) {
		int id = v.getId();
		if (id == R.id.msg_listView) {
			mInputMethodManager.hideSoftInputFromWindow(mChatEditText.getWindowToken(), 0);
			mFaceSwitchBtn.setImageResource(R.drawable.qzone_edit_face_drawable);
			mFaceRoot.setVisibility(View.GONE);
			mIsFaceShow = false;
		} else if (id == R.id.input) {
			mInputMethodManager.showSoftInput(mChatEditText, 0);
			mFaceSwitchBtn.setImageResource(R.drawable.qzone_edit_face_drawable);
			mFaceRoot.setVisibility(View.GONE);
			mIsFaceShow = false;
		}
		return false;
	}

	@Override
	public void connectionStatusChanged(int connectedState, String reason) {
	}



}
