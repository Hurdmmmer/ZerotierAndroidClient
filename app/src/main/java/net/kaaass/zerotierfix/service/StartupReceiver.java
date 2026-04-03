package net.kaaass.zerotierfix.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;
import android.util.Log;

import net.kaaass.zerotierfix.util.AutoConnectPolicy;
import net.kaaass.zerotierfix.util.Constants;

// TODO: clear up
public class StartupReceiver extends BroadcastReceiver {
    private static final String TAG = "StartupReceiver";

    public void onReceive(Context context, Intent intent) {
        // 仅在开机广播时处理自动启动，避免误响应其他广播
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }
        Log.i(TAG, "Received BOOT_COMPLETED. Evaluating autostart policy.");

        var pref = PreferenceManager.getDefaultSharedPreferences(context);
        if (pref.getBoolean(Constants.PREF_GENERAL_START_ZEROTIER_ON_BOOT, true)) {
            Log.i(TAG, "Start-on-boot enabled.");
        } else {
            Log.i(TAG, "Start-on-boot disabled.");
            return;
        }

        // 自动探测启用但缺少探测 IP 时，按用户规则“不开启自动路由转发”
        if (!AutoConnectPolicy.isConfigReady(context)) {
            Log.i(TAG, "Auto route check enabled but probe IP is not configured. Skip autostart.");
            return;
        }

        // 探测到内网可达时，跳过 ZeroTier 自动启动，直接使用本地链路
        if (!AutoConnectPolicy.shouldAutoConnect(context)) {
            Log.i(TAG, "Autostart skipped by intranet probe policy.");
            return;
        }

        try {
            Intent serviceIntent = new Intent(context, ZeroTierOneService.class);
            context.startService(serviceIntent);
            Log.i(TAG, "ZeroTier service autostart requested.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to autostart ZeroTier service", e);
        }
    }
}
