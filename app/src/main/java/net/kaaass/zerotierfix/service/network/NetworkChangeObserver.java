package net.kaaass.zerotierfix.service.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

/**
 * 网络变化观察器。
 * <p>
 * 负责统一管理 ConnectivityManager 回调的注册与反注册，
 * 并把系统网络变化映射为上层可消费的 reason 文本。
 */
public class NetworkChangeObserver {

    /** 应用级上下文。 */
    private final Context context;
    /** 链路追踪日志标签。 */
    private final String traceTag;
    /** 常规错误日志标签。 */
    private final String logTag;
    /** 系统网络连接管理器。 */
    private ConnectivityManager connectivityManager;
    /** 当前注册的网络回调对象。 */
    private ConnectivityManager.NetworkCallback networkCallback;

    /**
     * 网络变化回调监听器。
     */
    public interface Listener {
        /**
         * 通知上层发生了网络变化。
         *
         * @param reason 变化原因标识
         */
        void onNetworkChanged(String reason);
    }

    /**
     * 构造网络变化观察器。
     *
     * @param context  Android 上下文
     * @param logTag   常规日志标签
     * @param traceTag 连接链路追踪标签
     */
    public NetworkChangeObserver(Context context, String logTag, String traceTag) {
        this.context = context.getApplicationContext();
        this.logTag = logTag;
        this.traceTag = traceTag;
    }

    /**
     * 启动观察器（幂等）。
     *
     * @param listener 回调监听器
     */
    public synchronized void start(Listener listener) {
        if (this.networkCallback != null) {
            Log.i(this.traceTag, "Service.registerNetworkChangeMonitor skip: already registered");
            return;
        }
        this.connectivityManager = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (this.connectivityManager == null) {
            Log.w(this.traceTag, "Service.registerNetworkChangeMonitor skip: connectivityManager null");
            return;
        }
        this.networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(android.net.Network network) {
                listener.onNetworkChanged("network_available");
            }

            @Override
            public void onLost(android.net.Network network) {
                listener.onNetworkChanged("network_lost");
            }

            @Override
            public void onCapabilitiesChanged(android.net.Network network, NetworkCapabilities networkCapabilities) {
                listener.onNetworkChanged("network_capabilities_changed");
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                this.connectivityManager.registerDefaultNetworkCallback(this.networkCallback);
            } else {
                NetworkRequest request = new NetworkRequest.Builder().build();
                this.connectivityManager.registerNetworkCallback(request, this.networkCallback);
            }
            Log.i(this.traceTag, "Service.registerNetworkChangeMonitor success");
        } catch (Exception e) {
            Log.e(this.logTag, "Failed to register network callback", e);
            Log.e(this.traceTag, "Service.registerNetworkChangeMonitor failed: " + e.getClass().getSimpleName());
            this.networkCallback = null;
        }
    }

    /**
     * 停止观察器（幂等）。
     */
    public synchronized void stop() {
        if (this.connectivityManager == null || this.networkCallback == null) {
            Log.i(this.traceTag, "Service.unregisterNetworkChangeMonitor skip: not registered");
            return;
        }
        try {
            this.connectivityManager.unregisterNetworkCallback(this.networkCallback);
            Log.i(this.traceTag, "Service.unregisterNetworkChangeMonitor success");
        } catch (Exception e) {
            Log.e(this.logTag, "Failed to unregister network callback", e);
            Log.e(this.traceTag, "Service.unregisterNetworkChangeMonitor failed: " + e.getClass().getSimpleName());
        } finally {
            this.networkCallback = null;
        }
    }
}
