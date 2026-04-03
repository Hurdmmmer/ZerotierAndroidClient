package net.kaaass.zerotierfix.service.notification;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.ui.NetworkListActivity;
import net.kaaass.zerotierfix.util.Constants;

import java.util.Locale;

/**
 * 前台通知控制器。
 * <p>
 * 职责：
 * 1) 创建通知渠道；
 * 2) 维护连接展示状态（网络名/网络 ID/速率）；
 * 3) 生成并刷新前台通知。
 */
public class ServiceNotificationController {

    /** Android 上下文。 */
    private final Context context;
    /** 连接追踪日志标签。 */
    private final String traceTag;
    /** 系统通知管理器。 */
    private NotificationManager notificationManager;
    /** 当前连接网络名称。 */
    private String connectedNetworkName = "";
    /** 当前连接网络 ID 文本。 */
    private String connectedNetworkIdText = "";
    /** 最近一次展示的速率文本。 */
    private String lastSpeedText = "↑0.0KB/s ↓0.0KB/s";
    /** 最近一次通知刷新时间戳。 */
    private long lastNotificationUpdateTs = 0L;

    /**
     * 构造通知控制器。
     *
     * @param context  Android 上下文
     * @param traceTag 连接追踪日志标签
     */
    public ServiceNotificationController(Context context, String traceTag) {
        this.context = context;
        this.traceTag = traceTag;
    }

    /**
     * 绑定当前连接网络信息，用于通知标题展示。
     *
     * @param networkName   网络名称
     * @param networkIdText 网络 ID 文本
     */
    public void bindConnectedNetwork(String networkName, String networkIdText) {
        this.connectedNetworkName = networkName == null ? "" : networkName;
        this.connectedNetworkIdText = networkIdText == null ? "" : networkIdText;
        this.lastNotificationUpdateTs = 0L;
    }

    /**
     * 重置通知状态到未连接初始值。
     */
    public void resetState() {
        this.connectedNetworkName = "";
        this.connectedNetworkIdText = "";
        this.lastSpeedText = "↑0.0KB/s ↓0.0KB/s";
        this.lastNotificationUpdateTs = 0L;
    }

    /**
     * 初始化通知系统（管理器与渠道）。
     * <p>
     * 该方法设计为在 Service 启动阶段调用一次。
     */
    public void initNotification() {
        if (this.notificationManager == null) {
            this.notificationManager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        }
        if (Build.VERSION.SDK_INT >= 26 && this.notificationManager != null) {
            String channelName = this.context.getString(R.string.channel_name);
            String description = this.context.getString(R.string.channel_description);
            NotificationChannel channel = new NotificationChannel(
                    Constants.CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(description);
            this.notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * 构建“已连接”通知。再通知面板中显示
     *
     * @return 通知对象
     */
    public android.app.Notification buildConnectedNotification() {
        String displayName = this.connectedNetworkName == null || this.connectedNetworkName.isEmpty()
                ? this.connectedNetworkIdText
                : this.connectedNetworkName;
        if (displayName == null || displayName.isEmpty()) {
            displayName = this.context.getString(R.string.app_name);
        }
        String primary = this.context.getString(R.string.notification_primary_connected_simple, displayName);
        String secondary = this.context.getString(R.string.notification_secondary_connected_simple, this.lastSpeedText);
        return createNotificationBuilderBase()
                .setContentTitle(primary)
                .setContentText(secondary)
                .build();
    }

    /**
     * 按流量统计刷新通知（有节流）。
     *
     * @param now                当前时间戳（毫秒）
     * @param updateIntervalMs   刷新间隔
     * @param statsProvider      速率统计提供者
     * @param connected          当前是否已建立 VPN
     * @param notificationTag    通知 ID
     */
    public void refreshTrafficIfNeeded(long now,
                                       long updateIntervalMs,
                                       TrafficStatsProvider statsProvider,
                                       boolean connected,
                                       int notificationTag) {
        if (now - this.lastNotificationUpdateTs < updateIntervalMs) {
            return;
        }
        this.lastNotificationUpdateTs = now;
        updateSpeedText(statsProvider);
        if (this.notificationManager != null && connected) {
            this.notificationManager.notify(notificationTag, buildConnectedNotification());
        }
    }

    /**
     * 取消并移除通知。
     *
     * @param notificationTag 通知 ID
     */
    public void cancel(int notificationTag) {
        if (this.notificationManager != null) {
            this.notificationManager.cancel(notificationTag);
        }
    }

    /**
     * 速率统计提供者。
     */
    public interface TrafficStatsProvider {
        /**
         * 提供当前时刻的流量统计并清零计数。
         *
         * @return [0]=上行字节数，[1]=下行字节数
         */
        long[] consumeTrafficStats();
    }

    /**
     * 创建通知 Builder 基础配置。
     *
     * @return Builder
     */
    private NotificationCompat.Builder createNotificationBuilderBase() {
        int pendingIntentFlag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            pendingIntentFlag |= PendingIntent.FLAG_IMMUTABLE;
        }
        Intent openIntent = new Intent(this.context, NetworkListActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this.context, 0, openIntent, pendingIntentFlag);

        return new NotificationCompat.Builder(this.context, Constants.CHANNEL_ID)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setColor(ContextCompat.getColor(this.context.getApplicationContext(), R.color.zerotier_orange))
                .setContentIntent(openPendingIntent);
    }

    /**
     * 更新速率展示文本。
     *
     * @param statsProvider 速率统计提供者
     */
    private void updateSpeedText(TrafficStatsProvider statsProvider) {
        if (statsProvider == null) {
            return;
        }
        try {
            long[] stats = statsProvider.consumeTrafficStats();
            double tx = stats[0] / 1024.0;
            double rx = stats[1] / 1024.0;
            this.lastSpeedText = String.format(Locale.ROOT, "↑%.1fKB/s ↓%.1fKB/s", tx, rx);
        } catch (Exception e) {
            Log.e(this.traceTag, "ServiceNotificationController.updateSpeedText failed", e);
        }
    }
}
