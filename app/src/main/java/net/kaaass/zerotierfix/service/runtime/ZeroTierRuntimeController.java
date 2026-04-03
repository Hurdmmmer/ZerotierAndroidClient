package net.kaaass.zerotierfix.service.runtime;

import com.zerotier.sdk.EventListener;
import com.zerotier.sdk.Node;
import com.zerotier.sdk.Peer;
import com.zerotier.sdk.ResultCode;
import com.zerotier.sdk.VirtualNetworkConfig;
import com.zerotier.sdk.VirtualNetworkConfigListener;

import net.kaaass.zerotierfix.service.DataStore;
import net.kaaass.zerotierfix.service.TunTapAdapter;
import net.kaaass.zerotierfix.service.UdpCom;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * ZeroTier 运行时控制器。
 * <p>
 * 该组件负责 Runtime 资源的启停与 Node 操作封装：
 * 1) Runtime 初始化（Node/UDP/TUN 线程）；
 * 2) Runtime 停止（资源回收）；
 * 3) Node 级操作（join/leave/config/peers）。
 * Service 仅负责生命周期与事件编排，不直接散落 Node API 调用细节。
 */
public class ZeroTierRuntimeController {

    /**
     * Socket 保护器（由 VpnService.protect 适配）。
     */
    public interface SocketProtector {
        /**
         * 将指定 UDP Socket 排除在 VPN 外，避免回环。
         *
         * @param socket UDP Socket
         * @return 是否保护成功
         */
        boolean protect(DatagramSocket socket);
    }

    /**
     * UDP 桥接器工厂。
     */
    public interface UdpComFactory {
        /**
         * 创建 UDP 桥接器。
         *
         * @param socket UDP Socket
         * @return UdpCom 实例
         */
        UdpCom create(DatagramSocket socket);
    }

    /**
     * TunTap 适配器工厂。
     */
    public interface TunTapAdapterFactory {
        /**
         * 创建 TunTap 适配器。
         *
         * @param networkId 网络 ID
         * @return TunTapAdapter 实例
         */
        TunTapAdapter create(long networkId);
    }

    /**
     * Runtime 启动入参。
     */
    public static final class StartRuntimeRequest {
        /** 目标虚拟网络 ID。 */
        public final long networkId;
        /** 现有 UDP Socket（可复用）。 */
        public final DatagramSocket existingSocket;
        /** 现有 UDP 桥接器（可复用）。 */
        public final UdpCom existingUdpCom;
        /** 现有 TUN/TAP 适配器（可复用）。 */
        public final TunTapAdapter existingTunTapAdapter;
        /** 现有 ZeroTier Node（可复用）。 */
        public final Node existingNode;
        /** 现有后台任务线程（可复用）。 */
        public final Thread existingVpnThread;
        /** 现有 UDP 收发线程（可复用）。 */
        public final Thread existingUdpThread;
        /** ZeroTier SDK 数据存储实现。 */
        public final DataStore dataStore;
        /** 后台任务执行体。 */
        public final Runnable vpnRunnable;
        /** SDK 事件监听器。 */
        public final EventListener eventListener;
        /** SDK 网络配置回调监听器。 */
        public final VirtualNetworkConfigListener configListener;
        /** Socket 保护适配器。 */
        public final SocketProtector socketProtector;
        /** UdpCom 构造工厂。 */
        public final UdpComFactory udpComFactory;
        /** TunTapAdapter 构造工厂。 */
        public final TunTapAdapterFactory tunTapAdapterFactory;

        /**
         * 构造 Runtime 启动请求。
         *
         * @param networkId          目标网络 ID
         * @param existingSocket     当前 UDP Socket（可为空）
         * @param existingUdpCom     当前 UDP 桥接器（可为空）
         * @param existingTunTapAdapter 当前 TUN/TAP 适配器（可为空）
         * @param existingNode       当前 Node（可为空）
         * @param existingVpnThread  当前后台线程（可为空）
         * @param existingUdpThread  当前 UDP 线程（可为空）
         * @param dataStore          ZeroTier 数据存储
         * @param vpnRunnable        后台任务 Runnable（通常为 Service 自身）
         * @param eventListener      SDK EventListener
         * @param configListener     SDK 虚拟网络配置监听器
         * @param socketProtector    Socket 保护器
         * @param udpComFactory      UdpCom 工厂
         * @param tunTapAdapterFactory TunTapAdapter 工厂
         */
        public StartRuntimeRequest(long networkId,
                                   DatagramSocket existingSocket,
                                   UdpCom existingUdpCom,
                                   TunTapAdapter existingTunTapAdapter,
                                   Node existingNode,
                                   Thread existingVpnThread,
                                   Thread existingUdpThread,
                                   DataStore dataStore,
                                   Runnable vpnRunnable,
                                   EventListener eventListener,
                                   VirtualNetworkConfigListener configListener,
                                   SocketProtector socketProtector,
                                   UdpComFactory udpComFactory,
                                   TunTapAdapterFactory tunTapAdapterFactory) {
            this.networkId = networkId;
            this.existingSocket = existingSocket;
            this.existingUdpCom = existingUdpCom;
            this.existingTunTapAdapter = existingTunTapAdapter;
            this.existingNode = existingNode;
            this.existingVpnThread = existingVpnThread;
            this.existingUdpThread = existingUdpThread;
            this.dataStore = dataStore;
            this.vpnRunnable = vpnRunnable;
            this.eventListener = eventListener;
            this.configListener = configListener;
            this.socketProtector = socketProtector;
            this.udpComFactory = udpComFactory;
            this.tunTapAdapterFactory = tunTapAdapterFactory;
        }
    }

    /**
     * Runtime 启动结果。
     */
    public static final class StartRuntimeResult {
        /** 实际使用的 UDP Socket。 */
        public final DatagramSocket socket;
        /** 实际使用的 UDP 桥接器。 */
        public final UdpCom udpCom;
        /** 实际使用的 TUN/TAP 适配器。 */
        public final TunTapAdapter tunTapAdapter;
        /** 实际使用的 Node。 */
        public final Node node;
        /** 实际运行的后台任务线程。 */
        public final Thread vpnThread;
        /** 实际运行的 UDP 收发线程。 */
        public final Thread udpThread;
        /** 是否在本次调用中创建了新 Node。 */
        public final boolean nodeCreated;
        /** Node 地址（仅 nodeCreated=true 时有效）。 */
        public final long nodeAddress;

        /**
         * 构造 Runtime 启动结果。
         *
         * @param socket        UDP Socket
         * @param udpCom        UDP 桥接器
         * @param tunTapAdapter TUN/TAP 适配器
         * @param node          Node
         * @param vpnThread     后台线程
         * @param udpThread     UDP 线程
         * @param nodeCreated   是否本次新建 Node
         * @param nodeAddress   Node 地址（仅 nodeCreated=true 时有效）
         */
        public StartRuntimeResult(DatagramSocket socket,
                                  UdpCom udpCom,
                                  TunTapAdapter tunTapAdapter,
                                  Node node,
                                  Thread vpnThread,
                                  Thread udpThread,
                                  boolean nodeCreated,
                                  long nodeAddress) {
            this.socket = socket;
            this.udpCom = udpCom;
            this.tunTapAdapter = tunTapAdapter;
            this.node = node;
            this.vpnThread = vpnThread;
            this.udpThread = udpThread;
            this.nodeCreated = nodeCreated;
            this.nodeAddress = nodeAddress;
        }
    }

    /**
     * Runtime 停止入参。
     */
    public static final class StopRuntimeRequest {
        /** 当前 UDP Socket。 */
        public final DatagramSocket socket;
        /** 当前 UDP 桥接器。 */
        public final UdpCom udpCom;
        /** 当前 TUN/TAP 适配器。 */
        public final TunTapAdapter tunTapAdapter;
        /** 当前 UDP 收发线程。 */
        public final Thread udpThread;
        /** 当前后台任务线程。 */
        public final Thread vpnThread;
        /** IPv4 多播扫描线程。 */
        public final Thread v4MulticastScanner;
        /** IPv6 多播扫描线程。 */
        public final Thread v6MulticastScanner;
        /** VPN 文件描述符包装对象。 */
        public final android.os.ParcelFileDescriptor vpnSocket;
        /** VPN 输入流。 */
        public final FileInputStream in;
        /** VPN 输出流。 */
        public final FileOutputStream out;
        /** 当前 ZeroTier Node。 */
        public final Node node;

        /**
         * 构造 Runtime 停止请求。
         *
         * @param socket            UDP Socket
         * @param udpCom            UDP 桥接器
         * @param tunTapAdapter     TUN/TAP 适配器
         * @param udpThread         UDP 线程
         * @param vpnThread         后台线程
         * @param v4MulticastScanner IPv4 多播线程
         * @param v6MulticastScanner IPv6 多播线程
         * @param vpnSocket         VPN FD
         * @param in                VPN 输入流
         * @param out               VPN 输出流
         * @param node              Node
         */
        public StopRuntimeRequest(DatagramSocket socket,
                                  UdpCom udpCom,
                                  TunTapAdapter tunTapAdapter,
                                  Thread udpThread,
                                  Thread vpnThread,
                                  Thread v4MulticastScanner,
                                  Thread v6MulticastScanner,
                                  android.os.ParcelFileDescriptor vpnSocket,
                                  FileInputStream in,
                                  FileOutputStream out,
                                  Node node) {
            this.socket = socket;
            this.udpCom = udpCom;
            this.tunTapAdapter = tunTapAdapter;
            this.udpThread = udpThread;
            this.vpnThread = vpnThread;
            this.v4MulticastScanner = v4MulticastScanner;
            this.v6MulticastScanner = v6MulticastScanner;
            this.vpnSocket = vpnSocket;
            this.in = in;
            this.out = out;
            this.node = node;
        }
    }

    /**
     * Runtime 停止结果。
     */
    public static final class StopRuntimeResult {
        /** Node 是否已被 close。 */
        public final boolean nodeClosed;
        /** 停止后的 IPv4 多播线程引用（当前固定为 null）。 */
        public final Thread v4ScannerStopped;
        /** 停止后的 IPv6 多播线程引用（当前固定为 null）。 */
        public final Thread v6ScannerStopped;

        /**
         * 构造 Runtime 停止结果。
         *
         * @param nodeClosed       Node 是否已关闭
         * @param v4ScannerStopped 停止后的 IPv4 多播线程引用（固定为 null）
         * @param v6ScannerStopped 停止后的 IPv6 多播线程引用（固定为 null）
         */
        public StopRuntimeResult(boolean nodeClosed, Thread v4ScannerStopped, Thread v6ScannerStopped) {
            this.nodeClosed = nodeClosed;
            this.v4ScannerStopped = v4ScannerStopped;
            this.v6ScannerStopped = v6ScannerStopped;
        }
    }

    /**
     * 启动 Runtime，并在已有资源存在时复用（幂等启动语义）。
     *
     * @param request Runtime 启动请求
     * @return Runtime 启动结果
     * @throws Exception 初始化失败时抛出
     */
    public StartRuntimeResult startRuntime(StartRuntimeRequest request) throws Exception {
        DatagramSocket socket = request.existingSocket;
        UdpCom udpCom = request.existingUdpCom;
        TunTapAdapter tunTapAdapter = request.existingTunTapAdapter;
        Node node = request.existingNode;
        Thread vpnThread = request.existingVpnThread;
        Thread udpThread = request.existingUdpThread;

        if (socket == null) {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setSoTimeout(1000);
            socket.bind(new InetSocketAddress(9994));
        }
        if (request.socketProtector != null) {
            request.socketProtector.protect(socket);
        }

        boolean nodeCreated = false;
        long nodeAddress = 0L;
        if (node == null) {
            if (request.udpComFactory == null || request.tunTapAdapterFactory == null) {
                throw new IllegalStateException("Runtime factories are required when node is null");
            }
            udpCom = request.udpComFactory.create(socket);
            tunTapAdapter = request.tunTapAdapterFactory.create(request.networkId);
            node = new Node(System.currentTimeMillis());
            ResultCode result = node.init(
                    request.dataStore,
                    request.dataStore,
                    udpCom,
                    request.eventListener,
                    tunTapAdapter,
                    request.configListener,
                    null
            );
            if (result != ResultCode.RESULT_OK) {
                throw new IllegalStateException("Error starting ZT1 Node: " + result);
            }
            nodeCreated = true;
            nodeAddress = node.address();
        }

        if (udpCom != null) {
            udpCom.setNode(node);
        }
        if (tunTapAdapter != null) {
            tunTapAdapter.setNode(node);
        }

        if (vpnThread == null || !vpnThread.isAlive()) {
            vpnThread = new Thread(request.vpnRunnable, "ZeroTier Service Thread");
        }
        if (udpCom != null && (udpThread == null || !udpThread.isAlive())) {
            udpThread = new Thread(udpCom, "UDP Communication Thread");
        }

        return new StartRuntimeResult(
                socket,
                udpCom,
                tunTapAdapter,
                node,
                vpnThread,
                udpThread,
                nodeCreated,
                nodeAddress
        );
    }

    /**
     * 停止 Runtime 资源。
     *
     * @param request Runtime 停止请求
     * @return Runtime 停止结果
     */
    public StopRuntimeResult stopRuntime(StopRuntimeRequest request) {
        if (request.socket != null) {
            request.socket.close();
        }
        if (request.udpThread != null && request.udpThread.isAlive()) {
            request.udpThread.interrupt();
            try {
                request.udpThread.join();
            } catch (InterruptedException ignored) {
            }
        }
        if (request.tunTapAdapter != null && request.tunTapAdapter.isRunning()) {
            request.tunTapAdapter.interrupt();
            try {
                request.tunTapAdapter.join();
            } catch (InterruptedException ignored) {
            }
        }
        if (request.vpnThread != null && request.vpnThread.isAlive()) {
            request.vpnThread.interrupt();
            try {
                request.vpnThread.join();
            } catch (InterruptedException ignored) {
            }
        }
        if (request.v4MulticastScanner != null) {
            request.v4MulticastScanner.interrupt();
            try {
                request.v4MulticastScanner.join();
            } catch (InterruptedException ignored) {
            }
        }
        if (request.v6MulticastScanner != null) {
            request.v6MulticastScanner.interrupt();
            try {
                request.v6MulticastScanner.join();
            } catch (InterruptedException ignored) {
            }
        }
        if (request.vpnSocket != null) {
            try {
                request.vpnSocket.close();
            } catch (Exception ignored) {
            }
        }
        if (request.in != null) {
            try {
                request.in.close();
            } catch (Exception ignored) {
            }
        }
        if (request.out != null) {
            try {
                request.out.close();
            } catch (Exception ignored) {
            }
        }
        boolean nodeClosed = false;
        if (request.node != null) {
            request.node.close();
            nodeClosed = true;
        }
        return new StopRuntimeResult(nodeClosed, null, null);
    }

    /**
     * 离网操作结果。
     */
    public static final class LeaveResult {
        private final ResultCode resultCode;
        private final boolean noNetworksLeft;

        /**
         * 构造离网结果。
         *
         * @param resultCode     SDK 返回码
         * @param noNetworksLeft 离网后是否已无网络配置
         */
        public LeaveResult(ResultCode resultCode, boolean noNetworksLeft) {
            this.resultCode = resultCode;
            this.noNetworksLeft = noNetworksLeft;
        }

        /**
         * @return SDK 返回码
         */
        public ResultCode getResultCode() {
            return resultCode;
        }

        /**
         * @return 是否已无网络配置
         */
        public boolean isNoNetworksLeft() {
            return noNetworksLeft;
        }
    }

    /**
     * 请求加入指定网络。
     *
     * @param node      ZeroTier 节点实例
     * @param networkId 目标网络 ID
     * @return SDK 返回码
     */
    public ResultCode join(Node node, long networkId) {
        if (node == null) {
            return null;
        }
        return node.join(networkId);
    }

    /**
     * 请求离开指定网络。
     *
     * @param node      ZeroTier 节点实例
     * @param networkId 目标网络 ID
     * @return 离网结果（包含 SDK 返回码与是否无网络配置）
     */
    public LeaveResult leave(Node node, long networkId) {
        if (node == null) {
            return new LeaveResult(null, false);
        }
        ResultCode result = node.leave(networkId);
        if (result != ResultCode.RESULT_OK) {
            return new LeaveResult(result, false);
        }
        VirtualNetworkConfig[] networkConfigs = node.networkConfigs();
        boolean noNetworksLeft = networkConfigs == null || networkConfigs.length == 0;
        return new LeaveResult(result, noNetworksLeft);
    }

    /**
     * 获取当前网络配置列表。
     *
     * @param node ZeroTier 节点实例
     * @return 网络配置数组（可能为空）
     */
    public VirtualNetworkConfig[] networkConfigs(Node node) {
        if (node == null) {
            return null;
        }
        return node.networkConfigs();
    }

    /**
     * 获取指定网络配置。
     *
     * @param node      ZeroTier 节点实例
     * @param networkId 网络 ID
     * @return 网络配置，若不存在则为 null
     */
    public VirtualNetworkConfig networkConfig(Node node, long networkId) {
        if (node == null) {
            return null;
        }
        return node.networkConfig(networkId);
    }

    /**
     * 获取 Peer 列表。
     *
     * @param node ZeroTier 节点实例
     * @return Peer 数组（可能为空）
     */
    public Peer[] peers(Node node) {
        if (node == null) {
            return null;
        }
        return node.peers();
    }
}
