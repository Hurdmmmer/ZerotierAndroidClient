package net.kaaass.zerotierfix.service;

import android.util.Log;

import com.zerotier.sdk.VirtualNetworkConfig;
import com.zerotier.sdk.VirtualNetworkStatus;

import net.kaaass.zerotierfix.events.IsServiceRunningReplyEvent;
import net.kaaass.zerotierfix.events.IsServiceRunningRequestEvent;
import net.kaaass.zerotierfix.events.ManualDisconnectEvent;
import net.kaaass.zerotierfix.events.NetworkConfigChangedByUserEvent;
import net.kaaass.zerotierfix.events.NetworkListReplyEvent;
import net.kaaass.zerotierfix.events.NetworkListRequestEvent;
import net.kaaass.zerotierfix.events.NetworkReconfigureEvent;
import net.kaaass.zerotierfix.events.NodeStatusEvent;
import net.kaaass.zerotierfix.events.NodeStatusRequestEvent;
import net.kaaass.zerotierfix.events.OrbitMoonEvent;
import net.kaaass.zerotierfix.events.PeerInfoReplyEvent;
import net.kaaass.zerotierfix.events.PeerInfoRequestEvent;
import net.kaaass.zerotierfix.events.StopEvent;
import net.kaaass.zerotierfix.events.VirtualNetworkConfigChangedEvent;
import net.kaaass.zerotierfix.events.VirtualNetworkConfigReplyEvent;
import net.kaaass.zerotierfix.events.VirtualNetworkConfigRequestEvent;
import net.kaaass.zerotierfix.model.MoonOrbit;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Service 事件处理器。
 * <p>
 * 仅负责 EventBus 事件消费与分发，避免将事件逻辑堆积在 Service 本体中。
 */
final class ZeroTierOneServiceEventHandler {
    /** Service 编排入口。 */
    private final ZeroTierOneService service;
    /** 事件总线。 */
    private final EventBus eventBus;

    /**
     * 构造事件处理器。
     *
     * @param service Service 实例
     * @param eventBus 事件总线
     */
    ZeroTierOneServiceEventHandler(ZeroTierOneService service, EventBus eventBus) {
        this.service = service;
        this.eventBus = eventBus;
    }

    /**
     * 发送当前节点状态。
     * <p>
     * 提供给 Service 内部主动调用（例如节点刚创建完成时）。
     */
    void publishNodeStatus() {
        if (this.service.runtimeNode() != null) {
            this.eventBus.post(new NodeStatusEvent(
                    this.service.runtimeNode().status(),
                    this.service.runtimeNode().getVersion()
            ));
        }
    }

    /**
     * 停止事件回调（通常由 UI 主动关闭触发）。
     *
     * @param stopEvent 停止事件
     */
    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onStopEvent(StopEvent stopEvent) {
        Log.w(ZeroTierOneService.TRACE, "Service.onStopEvent received");
        this.service.stopZeroTier();
    }

    /**
     * 手动断开事件回调。
     *
     * @param manualDisconnectEvent 手动断开事件
     */
    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onManualDisconnect(ManualDisconnectEvent manualDisconnectEvent) {
        Log.w(ZeroTierOneService.TRACE, "Service.onManualDisconnect received");
        this.service.stopZeroTier();
    }

    /**
     * 响应“服务是否运行”查询。
     *
     * @param event 查询事件
     */
    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onIsServiceRunningRequest(IsServiceRunningRequestEvent event) {
        this.eventBus.post(new IsServiceRunningReplyEvent(true));
    }

    /**
     * 请求网络列表事件回调。
     *
     * @param requestNetworkListEvent 请求事件
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNetworkListRequest(NetworkListRequestEvent requestNetworkListEvent) {
        VirtualNetworkConfig[] networks = this.service.runtimeController()
                .networkConfigs(this.service.runtimeNode());
        if (networks != null && networks.length > 0) {
            this.eventBus.post(new NetworkListReplyEvent(networks));
        }
    }

    /**
     * 请求节点状态事件回调。
     *
     * @param event 请求事件
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNodeStatusRequest(NodeStatusRequestEvent event) {
        publishNodeStatus();
    }

    /**
     * 请求 Peer 信息事件回调。
     *
     * @param event 请求事件
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRequestPeerInfo(PeerInfoRequestEvent event) {
        var peers = this.service.runtimeController().peers(this.service.runtimeNode());
        if (peers == null) {
            this.eventBus.post(new PeerInfoReplyEvent(null));
            return;
        }
        this.eventBus.post(new PeerInfoReplyEvent(peers));
    }

    /**
     * 请求网络配置事件回调。
     *
     * @param event 请求事件
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onVirtualNetworkConfigRequest(VirtualNetworkConfigRequestEvent event) {
        var config = this.service.runtimeController().networkConfig(this.service.runtimeNode(), event.getNetworkId());
        this.eventBus.post(new VirtualNetworkConfigReplyEvent(config));
    }

    /**
     * 收到网络配置变更后执行重配置。
     *
     * @param event 重配置事件
     */
    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onNetworkReconfigure(NetworkReconfigureEvent event) {
        boolean isChanged = event.isChanged();
        var network = event.getNetwork();
        var networkConfig = event.getVirtualNetworkConfig();
        Log.i(ZeroTierOneService.TRACE, "Service.onNetworkReconfigure enter networkId="
                + com.zerotier.sdk.util.StringUtils.networkIdToString(network.getNetworkId())
                + ", isChanged=" + isChanged + ", status=" + networkConfig.getStatus());
        boolean configUpdated = isChanged && this.service.reconfigureTunnelForNetwork(network);
        boolean networkIsOk = networkConfig.getStatus() == VirtualNetworkStatus.NETWORK_STATUS_OK;
        Log.i(ZeroTierOneService.TRACE, "Service.onNetworkReconfigure result configUpdated=" + configUpdated
                + ", networkIsOk=" + networkIsOk);

        if (configUpdated || !networkIsOk) {
            this.eventBus.post(new VirtualNetworkConfigChangedEvent(networkConfig));
            return;
        }
        // 配置未变化但内核仍为 OK 时，也要刷新首页列表，避免卡片状态滞后。
        VirtualNetworkConfig[] networks = this.service.runtimeController().networkConfigs(this.service.runtimeNode());
        if (networks != null) {
            this.eventBus.post(new NetworkListReplyEvent(networks));
        }
    }

    /**
     * 用户侧网络配置变更事件回调。
     *
     * @param event 事件
     */
    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onNetworkConfigChangedByUser(NetworkConfigChangedByUserEvent event) {
        var network = event.getNetwork();
        if (network.getNetworkId() != this.service.runtimeController().getActiveNetworkId()) {
            return;
        }
        this.service.reconfigureTunnelForNetwork(network);
    }

    /**
     * 入轨事件回调。
     *
     * @param event 入轨事件
     */
    @Subscribe
    public void onOrbitMoonEvent(OrbitMoonEvent event) {
        if (this.service.runtimeNode() == null) {
            Log.e(ZeroTierOneService.TAG, "Can't orbit network if ZeroTier isn't running");
            return;
        }
        for (MoonOrbit moonOrbit : event.getMoonOrbits()) {
            Log.i(ZeroTierOneService.TAG, "Orbiting moon: " + Long.toHexString(moonOrbit.getMoonWorldId()));
            this.service.orbitNetwork(moonOrbit.getMoonWorldId(), moonOrbit.getMoonSeed());
        }
    }
}

