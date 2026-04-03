package net.kaaass.zerotierfix.service.runtime;

import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.zerotier.sdk.Node;
import com.zerotier.sdk.ResultCode;
import com.zerotier.sdk.VirtualNetworkConfig;

import net.kaaass.zerotierfix.model.Network;
import net.kaaass.zerotierfix.model.type.DNSMode;
import net.kaaass.zerotierfix.service.Route;
import net.kaaass.zerotierfix.service.TunTapAdapter;
import net.kaaass.zerotierfix.util.Constants;
import net.kaaass.zerotierfix.util.InetAddressUtils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * 运行时隧道核心。
 * <p>
 * 该组件负责：
 * 1) 根据 ZeroTier 配置构建 VPN Builder；
 * 2) 应用地址/路由/DNS/MTU；
 * 3) 建立 VPN FD 并启动 TUN/TAP 数据线程。
 */
public final class TunnelRuntimeCore {
    /**
     * VPN 构建请求。
     */
    public static final class BuildRequest {
        /** VpnService 上下文。 */
        public final VpnService vpnService;
        /** ZeroTier 节点实例。 */
        public final Node node;
        /** TUN/TAP 适配器。 */
        public final TunTapAdapter tunTapAdapter;
        /** 当前网络实体。 */
        public final Network network;
        /** ZeroTier 下发的虚拟网络配置。 */
        public final VirtualNetworkConfig virtualNetworkConfig;
        /** 是否禁用 IPv6。 */
        public final boolean disableIPv6;
        /** 不走 VPN 的应用包名列表。 */
        public final String[] disallowedApps;
        /** 主日志标签。 */
        public final String tag;
        /** 连接链路追踪标签。 */
        public final String traceTag;

        /**
         * @param vpnService           VpnService 上下文
         * @param node                 ZeroTier 节点实例
         * @param tunTapAdapter        TUN/TAP 适配器
         * @param network              当前网络实体
         * @param virtualNetworkConfig ZeroTier 下发配置
         * @param disableIPv6          是否禁用 IPv6
         * @param disallowedApps       不走 VPN 的应用包名
         * @param tag                  主日志标签
         * @param traceTag             连接追踪标签
         */
        public BuildRequest(
                VpnService vpnService,
                Node node,
                TunTapAdapter tunTapAdapter,
                Network network,
                VirtualNetworkConfig virtualNetworkConfig,
                boolean disableIPv6,
                String[] disallowedApps,
                String tag,
                String traceTag
        ) {
            this.vpnService = vpnService;
            this.node = node;
            this.tunTapAdapter = tunTapAdapter;
            this.network = network;
            this.virtualNetworkConfig = virtualNetworkConfig;
            this.disableIPv6 = disableIPv6;
            this.disallowedApps = disallowedApps;
            this.tag = tag;
            this.traceTag = traceTag;
        }
    }

    /**
     * VPN 构建结果。
     */
    public static final class BuildResult {
        /** 是否成功建立 VPN。 */
        public final boolean success;
        /** 失败时的错误文案。 */
        public final String errorMessage;
        /** 已建立的 VPN FD。 */
        public final ParcelFileDescriptor vpnSocket;
        /** VPN 输入流。 */
        public final FileInputStream in;
        /** VPN 输出流。 */
        public final FileOutputStream out;
        /** 已连接网络名称。 */
        public final String connectedNetworkName;
        /** 已连接网络 ID 文本。 */
        public final String connectedNetworkIdText;

        private BuildResult(
                boolean success,
                String errorMessage,
                ParcelFileDescriptor vpnSocket,
                FileInputStream in,
                FileOutputStream out,
                String connectedNetworkName,
                String connectedNetworkIdText
        ) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.vpnSocket = vpnSocket;
            this.in = in;
            this.out = out;
            this.connectedNetworkName = connectedNetworkName;
            this.connectedNetworkIdText = connectedNetworkIdText;
        }

        /**
         * @param errorMessage 错误文案
         * @return 失败结果
         */
        public static BuildResult failed(String errorMessage) {
            return new BuildResult(false, errorMessage, null, null, null, "", "");
        }

        /**
         * @param vpnSocket              VPN FD
         * @param in                     VPN 输入流
         * @param out                    VPN 输出流
         * @param connectedNetworkName   已连接网络名称
         * @param connectedNetworkIdText 已连接网络 ID 文本
         * @return 成功结果
         */
        public static BuildResult success(
                ParcelFileDescriptor vpnSocket,
                FileInputStream in,
                FileOutputStream out,
                String connectedNetworkName,
                String connectedNetworkIdText
        ) {
            return new BuildResult(
                    true,
                    null,
                    vpnSocket,
                    in,
                    out,
                    connectedNetworkName,
                    connectedNetworkIdText
            );
        }
    }

    /**
     * 受管路由构建结果。
     */
    private static final class ManagedRouteResult {
        /** 是否构建成功。 */
        final boolean success;
        /** 失败时的错误信息。 */
        final String errorMessage;
        /** 成功添加的受管路由数量。 */
        final int addedManagedRouteCount;

        private ManagedRouteResult(boolean success, String errorMessage, int addedManagedRouteCount) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.addedManagedRouteCount = addedManagedRouteCount;
        }

        /**
         * @param addedManagedRouteCount 成功添加的受管路由数量
         * @return 成功结果
         */
        static ManagedRouteResult success(int addedManagedRouteCount) {
            return new ManagedRouteResult(true, null, addedManagedRouteCount);
        }

        /**
         * @param errorMessage 错误信息
         * @return 失败结果
         */
        static ManagedRouteResult failed(String errorMessage) {
            return new ManagedRouteResult(false, errorMessage, 0);
        }
    }

    /**
     * 将网络配置应用到 VPN，并启动 TUN/TAP 数据线程。
     *
     * @param request VPN 构建请求
     * @return VPN 构建结果
     */
    public BuildResult buildAndStartTunnel(BuildRequest request) {
        var network = request.network;
        long networkId = network.getNetworkId();
        var networkConfig = network.getNetworkConfig();
        var virtualNetworkConfig = request.virtualNetworkConfig;
        var builder = request.vpnService.new Builder();
        var assignedAddresses = virtualNetworkConfig.getAssignedAddresses();
        boolean isRouteViaZeroTier = networkConfig.getRouteViaZeroTier();
        int addedAddressCount = 0;
        int addedDirectRouteCount = 0;

        Log.i(request.tag, "Configuring VpnService.Builder");
        Log.i(request.tag, "address length: " + assignedAddresses.length);
        Log.i(request.traceTag, "Service.updateTunnelConfig assignedAddresses=" + assignedAddresses.length);
        Log.i(request.traceTag, "Service.updateTunnelConfig routingFlags routeViaZeroTier=" + isRouteViaZeroTier
                + ", disableIPv6=" + request.disableIPv6);

        // 第一阶段：遍历设备分配地址，构建“地址 + 直连路由 + 组播订阅”。
        for (var vpnAddress : assignedAddresses) {
            Log.d(request.tag, "Adding VPN Address: " + vpnAddress.getAddress()
                    + " Mac: " + com.zerotier.sdk.util.StringUtils.macAddressToString(virtualNetworkConfig.getMac()));
            byte[] rawAddress = vpnAddress.getAddress().getAddress();
            if (request.disableIPv6 && vpnAddress.getAddress() instanceof Inet6Address) {
                continue;
            }
            var address = vpnAddress.getAddress();
            var port = vpnAddress.getPort();
            var route = InetAddressUtils.addressToRoute(address, port);
            if (route == null) {
                Log.e(request.tag, "NULL route calculated!");
                continue;
            }

            long multicastGroup;
            long multicastAdi;
            if (rawAddress.length == 4) {
                multicastGroup = InetAddressUtils.BROADCAST_MAC_ADDRESS;
                multicastAdi = ByteBuffer.wrap(rawAddress).getInt();
            } else {
                multicastGroup = ByteBuffer.wrap(new byte[]{
                                0, 0, 0x33, 0x33, (byte) 0xFF, rawAddress[13], rawAddress[14], rawAddress[15]})
                        .getLong();
                multicastAdi = 0;
            }
            var multicastResult = request.node.multicastSubscribe(networkId, multicastGroup, multicastAdi);
            if (multicastResult != ResultCode.RESULT_OK) {
                Log.e(request.tag, "Error joining multicast group");
            } else {
                Log.d(request.tag, "Joined multicast group");
            }

            builder.addAddress(address, port);
            builder.addRoute(route, port);
            request.tunTapAdapter.addRouteAndNetwork(new Route(route, port), networkId);
            addedAddressCount++;
            addedDirectRouteCount++;
            Log.i(request.traceTag, "Service.updateTunnelConfig addAssignedRoute addr="
                    + address.getHostAddress() + "/" + port
                    + ", route=" + route.getHostAddress() + "/" + port);
        }

        // 第二阶段：应用受管路由（控制器下发路由表）与组播默认路由。
        ManagedRouteResult managedRouteResult = addManagedRoutes(request, builder, isRouteViaZeroTier, networkId);
        if (!managedRouteResult.success) {
            return BuildResult.failed(managedRouteResult.errorMessage);
        }

        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false);
        }
        // 第三阶段：补充 DNS/MTU/会话信息，确保 VPN 配置完整。
        addDnsServers(request, builder);
        Log.i(request.traceTag, "Service.updateTunnelConfig routeSummary addresses=" + addedAddressCount
                + ", directRoutes=" + addedDirectRouteCount
                + ", managedRoutes=" + managedRouteResult.addedManagedRouteCount
                + ", routeViaZeroTier=" + isRouteViaZeroTier);

        int mtu = virtualNetworkConfig.getMtu();
        Log.i(request.tag, "MTU from Network Config: " + mtu);
        if (mtu == 0) {
            mtu = 2800;
        }
        Log.i(request.tag, "MTU Set: " + mtu);
        builder.setMtu(mtu);
        builder.setSession(Constants.VPN_SESSION_NAME);

        if (!isRouteViaZeroTier) {
            for (var app : request.disallowedApps) {
                try {
                    builder.addDisallowedApplication(app);
                } catch (Exception e) {
                    Log.e(request.tag, "Cannot disallow app", e);
                }
            }
        }

        // 第四阶段：建立 VPN FD，并把 IO 绑定到 TUN/TAP 线程。
        ParcelFileDescriptor vpnSocket = builder.establish();
        Log.i(request.traceTag, "Service.updateTunnelConfig establish vpnSocketNull=" + (vpnSocket == null));
        if (vpnSocket == null) {
            return BuildResult.failed(request.vpnService.getString(
                    net.kaaass.zerotierfix.R.string.toast_vpn_application_not_prepared));
        }
        FileInputStream in = new FileInputStream(vpnSocket.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnSocket.getFileDescriptor());
        request.tunTapAdapter.setVpnSocket(vpnSocket);
        request.tunTapAdapter.setFileStreams(in, out);
        request.tunTapAdapter.startThreads();

        String connectedNetworkName = network.getNetworkName() == null ? "" : network.getNetworkName();
        String connectedNetworkIdText = network.getNetworkIdStr();
        if (connectedNetworkIdText == null || connectedNetworkIdText.isEmpty()) {
            connectedNetworkIdText = com.zerotier.sdk.util.StringUtils.networkIdToString(network.getNetworkId());
        }
        return BuildResult.success(vpnSocket, in, out, connectedNetworkName, connectedNetworkIdText);
    }

    /**
     * 添加受管路由与组播路由。
     *
     * @param request            VPN 构建请求
     * @param builder            VPN Builder
     * @param isRouteViaZeroTier 是否全量经 ZeroTier 路由
     * @param networkId          网络 ID
     * @return 受管路由构建结果
     */
    private ManagedRouteResult addManagedRoutes(
            BuildRequest request,
            VpnService.Builder builder,
            boolean isRouteViaZeroTier,
            long networkId
    ) {
        try {
            int addedManagedRouteCount = 0;
            var v4Loopback = InetAddress.getByName("0.0.0.0");
            var v6Loopback = InetAddress.getByName("::");
            for (var routeConfig : request.virtualNetworkConfig.getRoutes()) {
                var target = routeConfig.getTarget();
                var via = routeConfig.getVia();
                var targetAddress = target.getAddress();
                var targetPort = target.getPort();
                var viaAddress = InetAddressUtils.addressToRoute(targetAddress, targetPort);
                boolean isIPv6Route = (targetAddress instanceof Inet6Address) || (viaAddress instanceof Inet6Address);
                boolean isDisabledV6Route = request.disableIPv6 && isIPv6Route;
                boolean shouldRouteToZerotier = viaAddress != null && (
                        isRouteViaZeroTier
                                || (!viaAddress.equals(v4Loopback) && !viaAddress.equals(v6Loopback))
                );
                String targetText = targetAddress == null ? "null" : targetAddress.getHostAddress();
                String viaText = viaAddress == null ? "null" : viaAddress.getHostAddress();
                String gatewayText = (via == null || via.getAddress() == null)
                        ? "null" : via.getAddress().getHostAddress();
                Log.i(request.traceTag, "Service.updateTunnelConfig routeDecision target=" + targetText + "/" + targetPort
                        + ", via=" + viaText
                        + ", gateway=" + gatewayText
                        + ", isIPv6=" + isIPv6Route
                        + ", disableIPv6=" + request.disableIPv6
                        + ", routeViaZeroTier=" + isRouteViaZeroTier
                        + ", shouldRouteToZerotier=" + shouldRouteToZerotier);
                // 仅在“未被 IPv6 禁用”且“判定应进 ZeroTier”时才写入 VPN 路由。
                if (!isDisabledV6Route && shouldRouteToZerotier) {
                    builder.addRoute(viaAddress, targetPort);
                    Route route = new Route(viaAddress, targetPort);
                    if (via != null) {
                        route.setGateway(via.getAddress());
                    }
                    request.tunTapAdapter.addRouteAndNetwork(route, networkId);
                    addedManagedRouteCount++;
                    Log.i(request.traceTag, "Service.updateTunnelConfig addManagedRoute via="
                            + viaAddress.getHostAddress() + "/" + targetPort
                            + ", gateway=" + gatewayText);
                }
            }
            // 与原实现保持一致：补充 IPv4 组播范围路由。
            builder.addRoute(InetAddress.getByName("224.0.0.0"), 4);
            Log.i(request.traceTag, "Service.updateTunnelConfig addMulticastRoute route=224.0.0.0/4");
            return ManagedRouteResult.success(addedManagedRouteCount);
        } catch (Exception e) {
            // 保持错误上抛语义：由调用方转换为 VPNErrorEvent。
            return ManagedRouteResult.failed(e.getLocalizedMessage());
        }
    }

    /**
     * 根据 DNS 模式向 VPN Builder 添加 DNS 服务器。
     *
     * @param request VPN 构建请求
     * @param builder VPN Builder
     */
    private void addDnsServers(BuildRequest request, VpnService.Builder builder) {
        var networkConfig = request.network.getNetworkConfig();
        var virtualNetworkConfig = request.virtualNetworkConfig;
        var dnsMode = DNSMode.fromInt(networkConfig.getDnsMode());
        int addedDnsCount = 0;
        Log.i(request.traceTag, "Service.addDNSServers mode=" + dnsMode + ", disableIPv6=" + request.disableIPv6);
        switch (dnsMode) {
            case NETWORK_DNS:
                if (virtualNetworkConfig.getDns() == null) {
                    Log.i(request.traceTag, "Service.addDNSServers skip: virtual dns config is null");
                    return;
                }
                builder.addSearchDomain(virtualNetworkConfig.getDns().getDomain());
                Log.i(request.traceTag, "Service.addDNSServers searchDomain=" + virtualNetworkConfig.getDns().getDomain());
                for (var inetSocketAddress : virtualNetworkConfig.getDns().getServers()) {
                    InetAddress address = inetSocketAddress.getAddress();
                    if (address instanceof Inet4Address) {
                        builder.addDnsServer(address);
                        addedDnsCount++;
                        Log.i(request.traceTag, "Service.addDNSServers add=" + address.getHostAddress());
                    } else if ((address instanceof Inet6Address) && !request.disableIPv6) {
                        builder.addDnsServer(address);
                        addedDnsCount++;
                        Log.i(request.traceTag, "Service.addDNSServers add=" + address.getHostAddress());
                    }
                }
                break;
            case CUSTOM_DNS:
                // 自定义 DNS 模式下逐条解析配置，保留原有容错行为（单条失败不影响其余条目）。
                for (var dnsServer : networkConfig.getDnsServers()) {
                    try {
                        InetAddress byName = InetAddress.getByName(dnsServer.getNameserver());
                        if (byName instanceof Inet4Address) {
                            builder.addDnsServer(byName);
                            addedDnsCount++;
                            Log.i(request.traceTag, "Service.addDNSServers addCustom=" + byName.getHostAddress());
                        } else if ((byName instanceof Inet6Address) && !request.disableIPv6) {
                            builder.addDnsServer(byName);
                            addedDnsCount++;
                            Log.i(request.traceTag, "Service.addDNSServers addCustom=" + byName.getHostAddress());
                        }
                    } catch (Exception e) {
                        Log.e(request.tag, "Exception parsing DNS server: " + e, e);
                    }
                }
                break;
            default:
                Log.i(request.traceTag, "Service.addDNSServers skip: dns mode default/disabled");
                break;
        }
        Log.i(request.traceTag, "Service.addDNSServers summary mode=" + dnsMode + ", count=" + addedDnsCount);
    }
}
