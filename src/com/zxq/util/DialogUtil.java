package com.zxq.util;

import com.zxq.xmpp.R;

import android.app.Activity;
import android.app.Dialog;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

public class DialogUtil {
	public static Dialog getMenuDialog(Activity context, View view) {

		final Dialog dialog = new Dialog(context, R.style.MenuDialogStyle);
		dialog.setContentView(view);
		Window window = dialog.getWindow();
		WindowManager.LayoutParams lp = window.getAttributes();

		int screenW = getScreenWidth(context);
		// int screenH = getScreenHeight(context);
		lp.width = screenW;
		window.setGravity(Gravity.BOTTOM); // 此处可以设置dialog显示的位置
		window.setWindowAnimations(R.style.MenuDialogAnimation); // 添加动画
		return dialog;
	}

	public static Dialog getLoginDialog(Activity context) {

		final Dialog dialog = new Dialog(context, R.style.DialogStyle);
		dialog.setCancelable(false);
		dialog.setContentView(R.layout.custom_progress_dialog);
		Window window = dialog.getWindow();
		WindowManager.LayoutParams lp = window.getAttributes();

		int screenW = getScreenWidth(context);
		lp.width = (int) (0.6 * screenW);

		TextView titleTxtv = (TextView) dialog.findViewById(R.id.dialogText);
		titleTxtv.setText(R.string.login_prompt);
		return dialog;
	}

    public static Dialog getInputDialog(Activity context) {

        final Dialog dialog = new Dialog(context, R.style.DialogStyle);
        dialog.setContentView(R.layout.input_dialog);
        return dialog;
    }

	public static int getScreenWidth(Activity context) {
		DisplayMetrics dm = new DisplayMetrics();
		context.getWindowManager().getDefaultDisplay().getMetrics(dm);
		return dm.widthPixels;
	}

	public static int getScreenHeight(Activity context) {
		DisplayMetrics dm = new DisplayMetrics();
		context.getWindowManager().getDefaultDisplay().getMetrics(dm);
		return dm.heightPixels;
	}

    public static Dialog getGroupPasswordInputDialog(Activity context) {
        final Dialog dialog = new Dialog(context, R.style.DialogStyle);
        dialog.setContentView(R.layout.group_password_input_dialog);
        return dialog;
    }

    public static Dialog getGroupChatMenuDialog(Activity context) {
        final Dialog dialog = new Dialog(context, R.style.DialogStyle);
        dialog.setContentView(R.layout.group_chat_menu_dialog);
        return dialog;
    }

	public static Dialog getGroupDeleteDialog(Activity context) {
		final Dialog dialog = new Dialog(context, R.style.DialogStyle);
		dialog.setContentView(R.layout.group_delete_dialog);
		return dialog;
	}

	public static Dialog getGroupOrFriendChooseDialog(Activity context) {
		final Dialog dialog = new Dialog(context, R.style.DialogStyle);
		dialog.setContentView(R.layout.choose_create_group_add_friend_dialog);
		return dialog;
	}


}
