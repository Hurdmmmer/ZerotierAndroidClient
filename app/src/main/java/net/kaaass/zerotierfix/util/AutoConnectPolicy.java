package net.kaaass.zerotierfix.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.preference.PreferenceManager;
import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;

/**
 * 自动连接策略，基于用户配置的内网探测 IP 决定是否自动启用 ZeroTier。
 */
public final class AutoConnectPolicy {
    private static final String TAG = "AutoConnectPolicy";
    private static final int PROBE_TIMEOUT_MS = 800;

    private AutoConnectPolicy() {
    }

    /**
     * 是否启用了自动路由探测策略。
     */
    public static boolean isAutoRouteCheckEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(Constants.PREF_PLANET_AUTO_ROUTE_CHECK, false);
    }

    /**
     * 读取用户配置的内网探测 IP。
     */
    public static String getConfiguredProbeIp(Context context) {
        String ip = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_PLANET_INTRANET_PROBE_IP, "");
        return ip == null ? "" : ip.trim();
    }

    /**
     * 当前配置是否完整可用。
     */
    public static boolean isConfigReady(Context context) {
        if (!isAutoRouteCheckEnabled(context)) {
            return true;
        }
        return !getConfiguredProbeIp(context).isEmpty();
    }

    /**
     * 内网探测结果。
     */
    public static final class ProbeResult {
        private final Boolean inIntranet;
        private final String detail;

        public ProbeResult(Boolean inIntranet, String detail) {
            this.inIntranet = inIntranet;
            this.detail = detail;
        }

        /**
         * @return true=在内网；false=不在内网；null=未知
         */
        public Boolean getInIntranet() {
            return this.inIntranet;
        }

        /**
         * @return 诊断信息
         */
        public String getDetail() {
            return this.detail;
        }
    }

    /**
     * 是否应该自动连接 ZeroTier。
     * 返回 true 表示应连接，false 表示应跳过。
     */
    public static boolean shouldAutoConnect(Context context) {
        ProbeResult result = detectIntranetState(context);
        if (Boolean.TRUE.equals(result.getInIntranet())) {
            Log.i(TAG, "Intranet detected, skip ZeroTier start. detail=" + result.getDetail());
            return false;
        }
        if (result.getInIntranet() == null) {
            Log.w(TAG, "Intranet state unknown, allow ZeroTier start. detail=" + result.getDetail());
            return true;
        }
        Log.i(TAG, "Not in intranet, allow ZeroTier start. detail=" + result.getDetail());
        return true;
    }

    /**
     * 检测是否处于“可直达探测 IP 的内网”。
     * <p>
     * 关键点：仅按“同网段”判定（不做可达性探测），避免公网/运营商网络误判为内网。
     *
     * @param context 上下文
     * @return 内网探测结果
     */
    public static ProbeResult detectIntranetState(Context context) {
        if (!isAutoRouteCheckEnabled(context)) {
            return new ProbeResult(null, "auto_route_check_disabled");
        }
        String probeIp = getConfiguredProbeIp(context);
        if (probeIp.isEmpty()) {
            return new ProbeResult(null, "probe_ip_empty");
        }
        try {
            InetAddress targetAddress = InetAddress.getByName(probeIp);
            Log.i(TAG, "detectIntranetState start probeIp=" + probeIp
                    + ", targetFamily=" + targetAddress.getClass().getSimpleName());
            ConnectivityManager connectivityManager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) {
                return new ProbeResult(null, "connectivity_manager_null");
            }

            android.net.Network[] allNetworks = connectivityManager.getAllNetworks();
            if (allNetworks == null || allNetworks.length == 0) {
                return new ProbeResult(false, "no_active_network");
            }

            for (android.net.Network network : allNetworks) {
                NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                if (capabilities == null) {
                    Log.i(TAG, "detectIntranetState skip network: capabilities null");
                    continue;
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    Log.i(TAG, "detectIntranetState skip network: transport=vpn");
                    continue;
                }
                // 蜂窝网络通常不应参与“内网同网段”判定，避免运营商私网段引入误判。
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    Log.i(TAG, "detectIntranetState skip network: transport=cellular");
                    continue;
                }
                LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
                if (linkProperties == null) {
                    Log.i(TAG, "detectIntranetState skip network: linkProperties null");
                    continue;
                }
                String interfaceName = linkProperties.getInterfaceName();
                if (interfaceName == null || interfaceName.isEmpty()) {
                    Log.i(TAG, "detectIntranetState skip network: interfaceName empty");
                    continue;
                }
                NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
                if (networkInterface == null) {
                    Log.i(TAG, "detectIntranetState skip network: interface not found name=" + interfaceName);
                    continue;
                }
                Log.i(TAG, "detectIntranetState inspect interface=" + interfaceName
                        + ", isWifiTransport=" + capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        + ", linkAddressCount=" + linkProperties.getLinkAddresses().size());

                boolean matchedSubnet = false;
                for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                    if (linkAddress == null || linkAddress.getAddress() == null) {
                        continue;
                    }
                    InetAddress localAddress = linkAddress.getAddress();
                    if (localAddress.isLoopbackAddress()) {
                        continue;
                    }
                    int prefixLength = linkAddress.getPrefixLength();
                    Log.i(TAG, "detectIntranetState compare local=" + localAddress.getHostAddress()
                            + "/" + prefixLength + ", target=" + targetAddress.getHostAddress());
                    if (isSameSubnet(targetAddress, localAddress, prefixLength)) {
                        matchedSubnet = true;
                        Log.i(TAG, "detectIntranetState matched interface=" + interfaceName
                                + ", local=" + localAddress.getHostAddress() + "/" + prefixLength);
                        break;
                    }
                }
                if (matchedSubnet) {
                    return new ProbeResult(true, "same_subnet_via_" + interfaceName);
                }
            }
            Log.i(TAG, "detectIntranetState result different_subnet_physical_networks");
            return new ProbeResult(false, "different_subnet_physical_networks");
        } catch (Exception e) {
            Log.e(TAG, "Probe failed: " + probeIp, e);
            return new ProbeResult(null, "probe_exception");
        }
    }

    /**
     * 判断两个 IP 是否在同一网段（按 prefixLength 比较前缀位）。
     *
     * @param target       目标 IP（用户配置内网 IP）
     * @param local        本地接口 IP
     * @param prefixLength 网段前缀长度
     * @return true 表示同网段
     */
    private static boolean isSameSubnet(InetAddress target, InetAddress local, int prefixLength) {
        if (target == null || local == null) {
            return false;
        }
        byte[] targetBytes = target.getAddress();
        byte[] localBytes = local.getAddress();
        if (targetBytes == null || localBytes == null || targetBytes.length != localBytes.length) {
            return false;
        }
        int maxBits = targetBytes.length * 8;
        if (prefixLength <= 0 || prefixLength > maxBits) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        int remainBits = prefixLength % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (targetBytes[i] != localBytes[i]) {
                return false;
            }
        }
        if (remainBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainBits);
        return (targetBytes[fullBytes] & mask) == (localBytes[fullBytes] & mask);
    }
}
