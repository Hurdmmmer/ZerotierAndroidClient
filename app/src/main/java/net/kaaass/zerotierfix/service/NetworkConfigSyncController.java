package net.kaaass.zerotierfix.service;

import android.util.Log;

import com.zerotier.sdk.VirtualNetworkConfig;
import com.zerotier.sdk.VirtualNetworkConfigOperation;

import net.kaaass.zerotierfix.ZerotierFixApplication;
import net.kaaass.zerotierfix.model.Network;
import net.kaaass.zerotierfix.model.NetworkDao;
import net.kaaass.zerotierfix.model.type.NetworkStatus;
import net.kaaass.zerotierfix.util.DatabaseUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 网络配置同步控制器。
 * <p>
 * 负责内核配置缓存、数据库状态落库与重配置触发条件判定。
 */
final class NetworkConfigSyncController {
    /** 配置缓存（networkId -> config）。 */
    private final Map<Long, VirtualNetworkConfig> virtualNetworkConfigMap = new HashMap<>();

    /**
     * 内核配置更新后的分发结果。
     */
    static final class UpdateDispatch {
        /** 是否需要下发重配置事件。 */
        final boolean shouldDispatchReconfigure;
        /** 配置是否发生变化。 */
        final boolean changed;
        /** 对应网络实体。 */
        final Network network;

        UpdateDispatch(boolean shouldDispatchReconfigure, boolean changed, Network network) {
            this.shouldDispatchReconfigure = shouldDispatchReconfigure;
            this.changed = changed;
            this.network = network;
        }
    }

    /**
     * 读取缓存配置。
     *
     * @param networkId 网络 ID
     * @return 当前缓存配置
     */
    VirtualNetworkConfig getVirtualNetworkConfig(long networkId) {
        synchronized (this.virtualNetworkConfigMap) {
            return this.virtualNetworkConfigMap.get(networkId);
        }
    }

    /**
     * 清理缓存配置。
     *
     * @param networkId 网络 ID
     * @return 被移除的配置
     */
    VirtualNetworkConfig clearVirtualNetworkConfig(long networkId) {
        synchronized (this.virtualNetworkConfigMap) {
            return this.virtualNetworkConfigMap.remove(networkId);
        }
    }

    /**
     * 处理内核网络配置更新。
     *
     * @param service   服务上下文
     * @param networkId 网络 ID
     * @param op        更新类型
     * @param config    新配置
     * @return 分发结果（仅 CONFIG_UPDATE 会返回 shouldDispatchReconfigure=true）
     */
    UpdateDispatch onNetworkConfigurationUpdated(
            ZeroTierOneService service,
            long networkId,
            VirtualNetworkConfigOperation op,
            VirtualNetworkConfig config
    ) {
        DatabaseUtils.writeLock.lock();
        try {
            var networkDao = ((ZerotierFixApplication) service.getApplication())
                    .getDaoSession()
                    .getNetworkDao();
            var matchedNetwork = networkDao.queryBuilder()
                    .where(NetworkDao.Properties.NetworkId.eq(networkId))
                    .list();
            if (matchedNetwork.size() != 1) {
                throw new IllegalStateException("Database is inconsistent");
            }
            var network = matchedNetwork.get(0);
            switch (op) {
                case VIRTUAL_NETWORK_CONFIG_OPERATION_UP:
                    Log.d(ZeroTierOneService.TAG, "Network Type: " + config.getType()
                            + " Network Status: " + config.getStatus()
                            + " Network Name: " + config.getName() + " ");
                    return new UpdateDispatch(false, false, network);
                case VIRTUAL_NETWORK_CONFIG_OPERATION_CONFIG_UPDATE:
                    Log.i(ZeroTierOneService.TAG, "Network Config Update!");
                    boolean isChanged = setVirtualNetworkConfigAndUpdateDatabase(network, config);
                    return new UpdateDispatch(true, isChanged, network);
                case VIRTUAL_NETWORK_CONFIG_OPERATION_DOWN:
                case VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY:
                    Log.d(ZeroTierOneService.TAG, "Network Down!");
                    clearVirtualNetworkConfig(networkId);
                    return new UpdateDispatch(false, false, network);
                default:
                    return new UpdateDispatch(false, false, network);
            }
        } finally {
            DatabaseUtils.writeLock.unlock();
        }
    }

    /**
     * 同步缓存并持久化网络配置。
     *
     * @param network              目标网络
     * @param virtualNetworkConfig 新配置
     * @return 配置是否变化
     */
    private boolean setVirtualNetworkConfigAndUpdateDatabase(
            Network network,
            VirtualNetworkConfig virtualNetworkConfig
    ) {
        if ((DatabaseUtils.writeLock instanceof ReentrantReadWriteLock.WriteLock)
                && !((ReentrantReadWriteLock.WriteLock) DatabaseUtils.writeLock).isHeldByCurrentThread()) {
            throw new IllegalStateException("DatabaseUtils.writeLock not held");
        }
        VirtualNetworkConfig oldConfig = getVirtualNetworkConfig(network.getNetworkId());
        synchronized (this.virtualNetworkConfigMap) {
            this.virtualNetworkConfigMap.put(network.getNetworkId(), virtualNetworkConfig);
        }
        var networkConfig = network.getNetworkConfig();
        if (networkConfig != null) {
            try {
                networkConfig.setStatus(NetworkStatus.fromVirtualNetworkStatus(virtualNetworkConfig.getStatus()));
                networkConfig.update();
            } catch (Exception e) {
                Log.e(ZeroTierOneService.TAG, "Failed to persist kernel network status", e);
            }
        }
        var networkName = virtualNetworkConfig.getName();
        if (networkName != null && !networkName.isEmpty()) {
            network.setNetworkName(networkName);
        }
        network.update();
        return !virtualNetworkConfig.equals(oldConfig);
    }
}

