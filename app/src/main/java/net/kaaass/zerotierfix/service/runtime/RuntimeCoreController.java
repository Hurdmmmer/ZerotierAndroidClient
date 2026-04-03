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
import android.os.ParcelFileDescriptor;

/**
 * Runtime 统一入口控制器。
 * <p>
 * Service 仅通过本控制器访问运行时能力，避免同时依赖多个 Runtime Core。
 */
public final class RuntimeCoreController {
    /** 节点运行时核心：负责 Node/UDP/TUN 生命周期与节点操作。 */
    private final NodeRuntimeCore nodeRuntimeCore = new NodeRuntimeCore();
    /** 隧道运行时核心：负责 VPN 隧道构建与重配置。 */
    private final TunnelRuntimeCore tunnelRuntimeCore = new TunnelRuntimeCore();
    /** 当前 UDP Socket（由 runtime 持有）。 */
    private DatagramSocket socket;
    /** 当前 UDP 桥接器（由 runtime 持有）。 */
    private UdpCom udpCom;
    /** 当前 TUN/TAP 适配器（由 runtime 持有）。 */
    private TunTapAdapter tunTapAdapter;
    /** 当前 ZeroTier Node（由 runtime 持有）。 */
    private Node node;
    /** 当前后台线程（由 runtime 持有）。 */
    private Thread vpnThread;
    /** 当前 UDP 线程（由 runtime 持有）。 */
    private Thread udpThread;
    /** 当前 VPN FD（由 runtime 持有）。 */
    private ParcelFileDescriptor vpnSocket;
    /** 当前 VPN 输入流（由 runtime 持有）。 */
    private FileInputStream in;
    /** 当前 VPN 输出流（由 runtime 持有）。 */
    private FileOutputStream out;
    /** 当前活动网络 ID（由 runtime 持有）。 */
    private long activeNetworkId = 0L;

    /**
     * 启动节点运行时所需依赖。
     */
    public static final class StartDependencies {
        /** ZeroTier SDK 数据存储实现。 */
        public final DataStore dataStore;
        /** 后台任务执行体（通常为 Service 自身）。 */
        public final Runnable vpnRunnable;
        /** SDK 事件监听器。 */
        public final EventListener eventListener;
        /** SDK 虚拟网络配置监听器。 */
        public final VirtualNetworkConfigListener configListener;
        /** Socket 保护回调（由 VpnService.protect 适配）。 */
        public final NodeRuntimeCore.SocketProtector socketProtector;
        /** UdpCom 构造工厂。 */
        public final NodeRuntimeCore.UdpComFactory udpComFactory;
        /** TunTapAdapter 构造工厂。 */
        public final NodeRuntimeCore.TunTapAdapterFactory tunTapAdapterFactory;

        /**
         * @param dataStore            ZeroTier 数据存储
         * @param vpnRunnable          后台任务执行体
         * @param eventListener        SDK 事件监听器
         * @param configListener       SDK 网络配置监听器
         * @param socketProtector      Socket 保护回调
         * @param udpComFactory        UdpCom 工厂
         * @param tunTapAdapterFactory TunTapAdapter 工厂
         */
        public StartDependencies(
                DataStore dataStore,
                Runnable vpnRunnable,
                EventListener eventListener,
                VirtualNetworkConfigListener configListener,
                NodeRuntimeCore.SocketProtector socketProtector,
                NodeRuntimeCore.UdpComFactory udpComFactory,
                NodeRuntimeCore.TunTapAdapterFactory tunTapAdapterFactory
        ) {
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
     * 启动/复用节点运行时并加入目标网络结果。
     */
    public static final class StartOrResumeNetworkResult {
        /** 节点运行时启动结果。 */
        public final NodeRuntimeCore.StartRuntimeResult startRuntimeResult;
        /** 节点 join 网络的 SDK 返回码。 */
        public final ResultCode joinResultCode;

        /**
         * @param startRuntimeResult 运行时启动结果
         * @param joinResultCode     join 网络的 SDK 返回码
         */
        public StartOrResumeNetworkResult(
                NodeRuntimeCore.StartRuntimeResult startRuntimeResult,
                ResultCode joinResultCode
        ) {
            this.startRuntimeResult = startRuntimeResult;
            this.joinResultCode = joinResultCode;
        }
    }

    /**
     * runtime 当前状态快照。
     */
    public static final class RuntimeState {
        /** 当前 UDP Socket。 */
        public final DatagramSocket socket;
        /** 当前 UDP 桥接器。 */
        public final UdpCom udpCom;
        /** 当前 TUN/TAP 适配器。 */
        public final TunTapAdapter tunTapAdapter;
        /** 当前 ZeroTier Node。 */
        public final Node node;
        /** 当前后台线程。 */
        public final Thread vpnThread;
        /** 当前 UDP 线程。 */
        public final Thread udpThread;
        /** 当前 VPN FD。 */
        public final ParcelFileDescriptor vpnSocket;
        /** 当前 VPN 输入流。 */
        public final FileInputStream in;
        /** 当前 VPN 输出流。 */
        public final FileOutputStream out;
        /** 当前活动网络 ID。 */
        public final long activeNetworkId;

        /**
         * @param socket        当前 UDP Socket
         * @param udpCom        当前 UDP 桥接器
         * @param tunTapAdapter 当前 TUN/TAP 适配器
         * @param node          当前 ZeroTier Node
         * @param vpnThread     当前后台线程
         * @param udpThread     当前 UDP 线程
         */
        public RuntimeState(
                DatagramSocket socket,
                UdpCom udpCom,
                TunTapAdapter tunTapAdapter,
                Node node,
                Thread vpnThread,
                Thread udpThread,
                ParcelFileDescriptor vpnSocket,
                FileInputStream in,
                FileOutputStream out,
                long activeNetworkId
        ) {
            this.socket = socket;
            this.udpCom = udpCom;
            this.tunTapAdapter = tunTapAdapter;
            this.node = node;
            this.vpnThread = vpnThread;
            this.udpThread = udpThread;
            this.vpnSocket = vpnSocket;
            this.in = in;
            this.out = out;
            this.activeNetworkId = activeNetworkId;
        }
    }

    /**
     * 执行隧道重配置。
     *
     * @param request 隧道构建请求
     * @return 隧道构建结果
     */
    public TunnelRuntimeCore.BuildResult reconfigureTunnel(TunnelRuntimeCore.BuildRequest request) {
        return this.tunnelRuntimeCore.buildAndStartTunnel(request);
    }

    /**
     * 执行“启动/复用运行时 + join 网络”组合动作。
     *
     * @param networkId    目标网络 ID
     * @param dependencies 运行时启动依赖
     * @return 组合执行结果
     * @throws Exception 节点运行时启动失败时抛出
     */
    public StartOrResumeNetworkResult startOrResumeNetwork(
            long networkId,
            StartDependencies dependencies
    ) throws Exception {
        this.activeNetworkId = networkId;
        // 先确保 runtime 可用，再执行 join；两步均在 runtime 层收口。
        NodeRuntimeCore.StartRuntimeResult runtimeResult = startNodeRuntime(networkId, dependencies);
        ResultCode joinResult = this.nodeRuntimeCore.join(runtimeResult.node, networkId);
        return new StartOrResumeNetworkResult(runtimeResult, joinResult);
    }

    /**
     * 启动或复用节点运行时（不执行 join）。
     *
     * @param networkId    目标网络 ID
     * @param dependencies 运行时启动依赖
     * @return 启动结果
     * @throws Exception 初始化失败时抛出
     */
    public NodeRuntimeCore.StartRuntimeResult startNodeRuntime(
            long networkId,
            StartDependencies dependencies
    ) throws Exception {
        this.activeNetworkId = networkId;
        NodeRuntimeCore.StartRuntimeRequest request = new NodeRuntimeCore.StartRuntimeRequest(
                networkId,
                this.socket,
                this.udpCom,
                this.tunTapAdapter,
                this.node,
                this.vpnThread,
                this.udpThread,
                dependencies.dataStore,
                dependencies.vpnRunnable,
                dependencies.eventListener,
                dependencies.configListener,
                dependencies.socketProtector,
                dependencies.udpComFactory,
                dependencies.tunTapAdapterFactory
        );
        NodeRuntimeCore.StartRuntimeResult result = this.nodeRuntimeCore.startRuntime(request);
        applyRuntimeState(result);
        return result;
    }

    /**
     * 停止 Node 运行时资源。
     *
     * @param request Node Runtime 停止请求
     * @return 停止结果
     */
    public NodeRuntimeCore.StopRuntimeResult stopNodeRuntime(
            NodeRuntimeCore.StopRuntimeRequest request
    ) {
        // 停止底层资源后清空 runtime 缓存状态，避免 Service 读取到陈旧引用。
        NodeRuntimeCore.StopRuntimeResult result = this.nodeRuntimeCore.stopRuntime(request);
        this.socket = null;
        this.udpCom = null;
        this.tunTapAdapter = null;
        this.node = null;
        this.vpnThread = null;
        this.udpThread = null;
        this.vpnSocket = null;
        this.in = null;
        this.out = null;
        this.activeNetworkId = 0L;
        return result;
    }

    /**
     * 使用 runtime 当前持有资源执行停止。
     *
     * @return 停止结果
     */
    public NodeRuntimeCore.StopRuntimeResult stopNodeRuntime() {
        NodeRuntimeCore.StopRuntimeRequest request = new NodeRuntimeCore.StopRuntimeRequest(
                this.socket,
                this.udpCom,
                this.tunTapAdapter,
                this.udpThread,
                this.vpnThread,
                this.vpnSocket,
                this.in,
                this.out,
                this.node
        );
        return stopNodeRuntime(request);
    }

    /**
     * 获取 runtime 当前状态快照。
     *
     * @return 当前状态快照
     */
    public RuntimeState getRuntimeState() {
        return new RuntimeState(
                this.socket,
                this.udpCom,
                this.tunTapAdapter,
                this.node,
                this.vpnThread,
                this.udpThread,
                this.vpnSocket,
                this.in,
                this.out,
                this.activeNetworkId
        );
    }

    /**
     * 写入当前隧道 IO 资源。
     */
    public void applyTunnelIo(TunnelRuntimeCore.BuildResult result) {
        this.vpnSocket = result.vpnSocket;
        this.in = result.in;
        this.out = result.out;
    }

    /**
     * 关闭并清理当前隧道 IO 资源。
     */
    public void closeTunnelIo() {
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception ignored) {
            }
        }
        if (this.in != null) {
            try {
                this.in.close();
            } catch (Exception ignored) {
            }
        }
        if (this.out != null) {
            try {
                this.out.close();
            } catch (Exception ignored) {
            }
        }
        this.vpnSocket = null;
        this.in = null;
        this.out = null;
    }

    /**
     * @return 当前是否已建立 VPN FD
     */
    public boolean hasVpnSocket() {
        return this.vpnSocket != null;
    }

    /**
     * @return 当前活动网络 ID
     */
    public long getActiveNetworkId() {
        return this.activeNetworkId;
    }

    /**
     * 设置当前活动网络 ID。
     *
     * @param activeNetworkId 活动网络 ID
     */
    public void setActiveNetworkId(long activeNetworkId) {
        this.activeNetworkId = activeNetworkId;
    }

    /**
     * 加入网络。
     *
     * @param node      Node 实例
     * @param networkId 网络 ID
     * @return SDK 返回码
     */
    public ResultCode join(Node node, long networkId) {
        return this.nodeRuntimeCore.join(node, networkId);
    }

    /**
     * 离开网络。
     *
     * @param node      Node 实例
     * @param networkId 网络 ID
     * @return 离网结果
     */
    public NodeRuntimeCore.LeaveResult leave(Node node, long networkId) {
        return this.nodeRuntimeCore.leave(node, networkId);
    }

    /**
     * 获取全部网络配置。
     *
     * @param node Node 实例
     * @return 网络配置数组
     */
    public VirtualNetworkConfig[] networkConfigs(Node node) {
        return this.nodeRuntimeCore.networkConfigs(node);
    }

    /**
     * 获取指定网络配置。
     *
     * @param node      Node 实例
     * @param networkId 网络 ID
     * @return 网络配置
     */
    public VirtualNetworkConfig networkConfig(Node node, long networkId) {
        return this.nodeRuntimeCore.networkConfig(node, networkId);
    }

    /**
     * 获取 Peer 列表。
     *
     * @param node Node 实例
     * @return Peer 数组
     */
    public Peer[] peers(Node node) {
        return this.nodeRuntimeCore.peers(node);
    }

    /**
     * 应用 runtime 启动结果并更新内部状态缓存。
     *
     * @param result runtime 启动结果
     */
    private void applyRuntimeState(NodeRuntimeCore.StartRuntimeResult result) {
        this.socket = result.socket;
        this.udpCom = result.udpCom;
        this.tunTapAdapter = result.tunTapAdapter;
        this.node = result.node;
        this.vpnThread = result.vpnThread;
        this.udpThread = result.udpThread;
        // 运行时线程的启动时序由 runtime 统一负责，避免 Service 侧重复处理线程生命周期。
        if (this.vpnThread != null && this.vpnThread.getState() == Thread.State.NEW) {
            this.vpnThread.start();
        }
        if (this.udpThread != null && this.udpThread.getState() == Thread.State.NEW) {
            this.udpThread.start();
        }
    }
}

