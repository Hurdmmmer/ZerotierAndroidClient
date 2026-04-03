package net.kaaass.zerotierfix.service;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.zerotier.sdk.Event;
import com.zerotier.sdk.EventListener;
import com.zerotier.sdk.Node;
import com.zerotier.sdk.ResultCode;
import com.zerotier.sdk.VirtualNetworkConfig;
import com.zerotier.sdk.VirtualNetworkConfigListener;
import com.zerotier.sdk.VirtualNetworkConfigOperation;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.ZerotierFixApplication;
import net.kaaass.zerotierfix.events.AfterJoinNetworkEvent;
import net.kaaass.zerotierfix.events.ErrorEvent;
import net.kaaass.zerotierfix.events.NetworkReconfigureEvent;
import net.kaaass.zerotierfix.events.NodeDestroyedEvent;
import net.kaaass.zerotierfix.events.NodeIDEvent;
import net.kaaass.zerotierfix.events.NodeStatusEvent;
import net.kaaass.zerotierfix.events.VPNErrorEvent;
import net.kaaass.zerotierfix.model.AppNode;
import net.kaaass.zerotierfix.model.Network;
import net.kaaass.zerotierfix.model.NetworkDao;
import net.kaaass.zerotierfix.service.network.NetworkChangeObserver;
import net.kaaass.zerotierfix.service.notification.ServiceNotificationController;
import net.kaaass.zerotierfix.service.policy.RoutePolicyEngine;
import net.kaaass.zerotierfix.service.runtime.RuntimeCoreController;
import net.kaaass.zerotierfix.service.runtime.TunnelRuntimeCore;
import net.kaaass.zerotierfix.service.runtime.NodeRuntimeCore;
import net.kaaass.zerotierfix.util.Constants;
import net.kaaass.zerotierfix.util.DatabaseUtils;
import net.kaaass.zerotierfix.util.NetworkInfoUtils;

import org.greenrobot.eventbus.EventBus;

/**
 * ZeroTier 核心前台服务。
 * <p>
 * 负责节点生命周期、VPN 建链、数据收发与状态广播。
 */
public class ZeroTierOneService extends VpnService implements Runnable, EventListener, VirtualNetworkConfigListener {
    public static final String ZT1_NETWORK_ID = "com.zerotier.one.network_id";
    private static final String[] DISALLOWED_APPS = {"com.android.vending"};
    static final String TAG = "ZT1_Service";
    static final String TRACE = "CONNECT_TRACE";
    private static final int ZT_NOTIFICATION_TAG = 5919812;
    private static final long NOTIFICATION_UPDATE_INTERVAL_MS = 1000L;
    private static final long MANUAL_CONNECT_PROTECTION_MS = 15000L;
    /** Binder 接口，用于 Activity/组件绑定服务实例。 */
    private final IBinder mBinder = new ZeroTierBinder();
    /** ZeroTier 数据存储实现。 */
    private final DataStore dataStore = new DataStore(this);
    /** 应用内事件总线。 */
    private final EventBus eventBus = EventBus.getDefault();
    /** Runtime 统一入口控制器（运行时资源与执行链路）。 */
    private final RuntimeCoreController runtimeController = new RuntimeCoreController();
    /** Service 事件处理器（承接 EventBus 订阅逻辑）。 */
    private final ZeroTierOneServiceEventHandler zeroTierOneServiceEventHandler = new ZeroTierOneServiceEventHandler(this, this.eventBus);
    /** 网络配置缓存与落库同步控制器。 */
    private final NetworkConfigSyncController networkConfigSyncController = new NetworkConfigSyncController();
    /** 自动路由策略协调器。 */
    private final RoutePolicyEngine.Coordinator autoRoutePolicyCoordinator = new RoutePolicyEngine.Coordinator();
    /** 策略执行回调实现（Service -> RoutePolicyEngine）。 */
    private final RoutePolicyEngine.PolicyRuntimeDelegate policyRuntimeDelegate =
            new RoutePolicyEngine.PolicyRuntimeDelegate() {
                @Override
                public void setMonitorOnlyMode(boolean monitorOnlyMode) {
                    ZeroTierOneService.this.setMonitorOnlyMode(monitorOnlyMode);
                }

                @Override
                public boolean isMonitorOnlyMode() {
                    return ZeroTierOneService.this.isMonitorOnlyMode();
                }

                @Override
                public void enterMonitorOnlyMode(String reason, String detail) {
                    ZeroTierOneService.this.enterMonitorOnlyMode(reason, detail);
                }

                @Override
                public long getActiveNetworkId() {
                    return ZeroTierOneService.this.runtimeController.getActiveNetworkId();
                }

                @Override
                public boolean startOrResumeZeroTierAndJoin(long networkId, String reason) {
                    return ZeroTierOneService.this.startOrResumeZeroTierAndJoin(networkId, reason);
                }
            };
    /** 当前绑定到服务的客户端计数。 */
    private int bindCount = 0;
    /** 是否禁用 IPv6（来自用户配置）。 */
    private boolean disableIPv6 = false;
    /** 当前服务启动序号。 */
    private int mStartID = -1;
    /** Node 后台任务下一次执行截止时间。 */
    private long nextBackgroundTaskDeadline = 0;
    /** 网络变化监听器。 */
    private NetworkChangeObserver networkChangeObserver;
    /** 前台通知控制器。 */
    private final ServiceNotificationController notificationController =
            new ServiceNotificationController(this, TRACE);
    /**
     * 当前是否处于“仅监听网络变化，不启用 ZeroTier 转发”的模式。
     * true: 服务保活，但不建立 ZeroTier 转发链路；
     * false: 服务可按正常流程连接并转发。
     */
    private volatile boolean monitorOnlyMode = false;
    /** 旧版 Android IPv4 组播扫描句柄。 */
    private MulticastScannerThreadFactory.ScannerHandle v4MulticastScanner;
    /** 旧版 Android IPv6 组播扫描句柄。 */
    private MulticastScannerThreadFactory.ScannerHandle v6MulticastScanner;

    /**
     * 读取指定网络的虚拟网络配置缓存。
     * <p>
     * 兼容现有调用方（例如 TunTapAdapter），底层实现已下沉到配置同步控制器。
     *
     * @param networkId 网络 ID
     * @return 虚拟网络配置
     */
    public VirtualNetworkConfig getVirtualNetworkConfig(long networkId) {
        return this.networkConfigSyncController.getVirtualNetworkConfig(networkId);
    }

    private void logBindCount() {
        Log.i(TAG, "Bind Count: " + this.bindCount);
    }

    @SuppressLint("BinderGetCallingInMainThread")
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Bound by: " + getPackageManager().getNameForUid(Binder.getCallingUid()));
        this.bindCount++;
        logBindCount();
        return this.mBinder;
    }

    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "Unbound by: " + getPackageManager().getNameForUid(Binder.getCallingUid()));
        this.bindCount--;
        logBindCount();
        return false;
    }

    /* access modifiers changed from: protected */
    public void setNextBackgroundTaskDeadline(long j) {
        synchronized (this) {
            this.nextBackgroundTaskDeadline = j;
        }
    }

    /**
     * 启动 ZT 服务，连接至给定网络或最近连接的网络。
     * <p>
     * 连接主链路：
     * 1) 解析目标网络 ID（显式传入或最近激活）；
     * 2) 通过 runtime 入口初始化/复用 Node / UDP / VPN 工作线程；
     * 3) 由 runtime 入口发起 join 入网；
     * 4) 后续由 {@link #onNetworkConfigurationUpdated(long, VirtualNetworkConfigOperation, VirtualNetworkConfig)}
     * 回调驱动隧道配置与通知刷新。
     *
     * @param intent  启动参数，包含可选网络 ID
     * @param flags   Service 启动标记
     * @param startId 系统分配的启动序号
     * @return Service 重启策略
     */
    @Override
    public void onCreate() {
        super.onCreate();
        // 通知系统仅在服务创建时初始化一次，避免运行期重复调用。
        this.notificationController.initNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        Log.i(TRACE, "Service.onStartCommand enter startId=" + startId + ", flags=" + flags
                + ", intentNull=" + (intent == null));
        if (startId == 3) {
            Log.i(TAG, "Authorizing VPN");
            Log.w(TRACE, "Service.onStartCommand shortReturnByStartId startId=3");
            return START_NOT_STICKY;
        } else if (intent == null) {
            Log.e(TAG, "NULL intent.  Cannot start");
            Log.e(TRACE, "Service.onStartCommand abort: intent is null");
            return START_NOT_STICKY;
        }
        this.mStartID = startId;

        // 注册事件总线监听器
        if (!this.eventBus.isRegistered(this.zeroTierOneServiceEventHandler)) {
            this.eventBus.register(this.zeroTierOneServiceEventHandler);
        }

        // 手动连接（携带 networkId）与自动连接（不携带 networkId）需要区分策略
        boolean hasExplicitNetworkId = intent.hasExtra(ZT1_NETWORK_ID);
        Log.i(TRACE, "Service.onStartCommand hasExplicitNetworkId=" + hasExplicitNetworkId);

        // 第一步：解析目标网络 ID（显式指定优先，否则取最近激活网络）。
        Long resolvedNetworkId = resolveTargetNetworkId(intent, hasExplicitNetworkId);
        if (resolvedNetworkId == null) {
            return START_NOT_STICKY;
        }
        long networkId = resolvedNetworkId;
        if (networkId == 0) {
            Log.e(TAG, "Network ID not provided to service");
            Log.e(TRACE, "Service.onStartCommand abort: networkId=0");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        this.runtimeController.setActiveNetworkId(networkId);

        Log.i(TRACE, "Service.onStartCommand resolvedNetworkId="
                + com.zerotier.sdk.util.StringUtils.networkIdToString(networkId));
        // 第二步：更新“手动连接保护窗口”。
        long manualProtectionDeadline = this.autoRoutePolicyCoordinator.updateManualProtectionDeadline(
                hasExplicitNetworkId,
                MANUAL_CONNECT_PROTECTION_MS
        );
        if (hasExplicitNetworkId) {
            Log.i(TRACE, "Service.onStartCommand manualConnectProtection until="
                    + manualProtectionDeadline);
        }
        registerNetworkChangeMonitorIfNeeded();

        // 第三步：读取网络偏好并校验当前网络环境。
        if (!validateStartNetworkEnvironmentAndApplyEffects()) {
            return START_NOT_STICKY;
        }

        // 启动策略：先进行内网探测。命中内网则进入监听态并跳过 ZeroTier 转发。
        if (this.autoRoutePolicyCoordinator.handleStartPolicy(
                this,
                this.eventBus,
                this.policyRuntimeDelegate,
                "service_start"
        )) {
            return START_STICKY;
        }

        if (!startOrResumeZeroTierAndJoin(networkId, "service_start")) {
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    /**
     * 解析本次启动目标网络 ID。
     *
     * @param intent               启动 Intent
     * @param hasExplicitNetworkId 是否显式携带 networkId
     * @return 目标网络 ID；若无法解析则返回 null
     */
    private Long resolveTargetNetworkId(Intent intent, boolean hasExplicitNetworkId) {
        if (hasExplicitNetworkId) {
            return intent.getLongExtra(ZT1_NETWORK_ID, 0);
        }
        DatabaseUtils.readLock.lock();
        try {
            var daoSession = ((ZerotierFixApplication) getApplication()).getDaoSession();
            daoSession.clear();
            var lastActivatedNetworks = daoSession.getNetworkDao().queryBuilder()
                    .where(NetworkDao.Properties.LastActivated.eq(true))
                    .list();
            if (lastActivatedNetworks == null || lastActivatedNetworks.isEmpty()) {
                Log.e(TAG, "Couldn't find last activated connection");
                return null;
            }
            if (lastActivatedNetworks.size() > 1) {
                Log.e(TAG, "Multiple networks marked as last connected: " + lastActivatedNetworks.size());
                for (Network network : lastActivatedNetworks) {
                    Log.e(TAG, "ID: " + Long.toHexString(network.getNetworkId()));
                }
                throw new IllegalStateException("Database is inconsistent");
            }
            Log.i(TAG, "Got Always On request for ZeroTier");
            return lastActivatedNetworks.get(0).getNetworkId();
        } finally {
            DatabaseUtils.readLock.unlock();
        }
    }

    /**
     * 校验启动时网络环境，并同步加载网络偏好配置。
     *
     * @return true 表示可继续启动；false 表示应终止本次启动
     */
    private boolean validateStartNetworkEnvironmentAndApplyEffects() {
        var preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useCellularData = preferences.getBoolean(Constants.PREF_NETWORK_USE_CELLULAR_DATA, false);
        this.disableIPv6 = preferences.getBoolean(Constants.PREF_NETWORK_DISABLE_IPV6, false);
        var currentNetworkInfo = NetworkInfoUtils.getNetworkInfoCurrentConnection(this);
        if (currentNetworkInfo == NetworkInfoUtils.CurrentConnection.CONNECTION_NONE) {
            Toast.makeText(this, R.string.toast_no_network, Toast.LENGTH_SHORT).show();
            Log.w(TRACE, "Service.onStartCommand abort: no network");
            stopSelf(this.mStartID);
            return false;
        }
        if (currentNetworkInfo == NetworkInfoUtils.CurrentConnection.CONNECTION_MOBILE && !useCellularData) {
            Toast.makeText(this, R.string.toast_mobile_data, Toast.LENGTH_LONG).show();
            Log.w(TRACE, "Service.onStartCommand abort: mobile disallowed");
            stopSelf(this.mStartID);
            return false;
        }
        return true;
    }

    /**
     * 启动或恢复 ZeroTier 运行时，并加入指定网络。
     *
     * @param networkId 目标网络 ID
     * @param reason    触发原因（日志用途）
     * @return true 表示成功触发加入流程；false 表示运行时初始化失败
     */
    boolean startOrResumeZeroTierAndJoin(long networkId, String reason) {
        this.runtimeController.setActiveNetworkId(networkId);
        synchronized (this) {
            try {
                // 运行时统一入口：在 runtime 层完成“启动/复用 + join”组合动作。
                RuntimeCoreController.StartOrResumeNetworkResult runtimeStartResult =
                        this.runtimeController.startOrResumeNetwork(
                                networkId,
                                buildRuntimeStartDependencies()
                        );
                handleNodeCreatedSideEffects(runtimeStartResult.startRuntimeResult);
                if (runtimeStartResult.joinResultCode != ResultCode.RESULT_OK) {
                    this.eventBus.post(new ErrorEvent(runtimeStartResult.joinResultCode));
                    return false;
                }
                Log.i(TRACE, "Service.startOrResumeZeroTierAndJoin runtimeReady reason=" + reason
                        + ", node=" + (runtimeNode() != null)
                        + ", vpnThreadAlive=" + (runtimeVpnThread() != null && runtimeVpnThread().isAlive())
                        + ", udpThreadAlive=" + (runtimeUdpThread() != null && runtimeUdpThread().isAlive()));
            } catch (Exception e) {
                Log.e(TAG, e.toString(), e);
                Log.e(TRACE, "Service.startOrResumeZeroTierAndJoin exception=" + e.getClass().getSimpleName()
                        + ", message=" + e.getMessage());
                return false;
            }
        }
        this.monitorOnlyMode = false;
        // join 动作已在 runtime 入口完成，这里仅补充成功事件与追踪日志。
        Log.i(TRACE, "Service.startOrResumeZeroTierAndJoin joinedByRuntime networkId="
                + com.zerotier.sdk.util.StringUtils.networkIdToString(networkId)
                + ", reason=" + reason);
        this.eventBus.post(new AfterJoinNetworkEvent());
        return true;
    }

    /**
     * 确保 ZeroTier 运行时已初始化（节点、UDP、后台任务线程）。
     *
     * @param networkId 目标网络 ID，用于初始化 TunTapAdapter
     * @throws Exception 初始化过程中的异常
     */
    private void prepareRuntimeForNetwork(long networkId) throws Exception {
        handleNodeCreatedSideEffects(
                this.runtimeController.startNodeRuntime(networkId, buildRuntimeStartDependencies())
        );
    }

    /**
     * 构造 runtime 启动依赖。
     * <p>
     * 说明：运行时状态（socket/node/thread 等）由 runtime 内部持有，
     * Service 仅提供上下文回调与工厂依赖。
     */
    private RuntimeCoreController.StartDependencies buildRuntimeStartDependencies() {
        return new RuntimeCoreController.StartDependencies(
                this.dataStore,
                this,
                this,
                this,
                socket -> {
                    boolean protectedOk = protect(socket);
                    if (!protectedOk) {
                        Log.e(TAG, "Error protecting UDP socket from feedback loop.");
                    }
                    return protectedOk;
                },
                socket -> new UdpCom(this, socket),
                targetNetworkId -> new TunTapAdapter(this, targetNetworkId)
        );
    }

    /**
     * 处理“首次创建节点”后的业务副作用（存库与事件通知）。
     *
     * @param result 运行时启动结果
     */
    private void handleNodeCreatedSideEffects(NodeRuntimeCore.StartRuntimeResult result) {
        if (result.nodeCreated) {
            Log.d(TAG, "ZeroTierOne Node Initialized");
            this.zeroTierOneServiceEventHandler.publishNodeStatus();
            // 持久化当前节点信息
            long address = result.nodeAddress;
            DatabaseUtils.writeLock.lock();
            try {
                var appNodeDao = ((ZerotierFixApplication) getApplication())
                        .getDaoSession().getAppNodeDao();
                var nodesList = appNodeDao.queryBuilder().build()
                        .forCurrentThread().list();
                if (nodesList.isEmpty()) {
                    var appNode = new AppNode();
                    appNode.setNodeId(address);
                    appNode.setNodeIdStr(String.format("%10x", address));
                    appNodeDao.insert(appNode);
                } else {
                    var appNode = nodesList.get(0);
                    appNode.setNodeId(address);
                    appNode.setNodeIdStr(String.format("%10x", address));
                    appNodeDao.save(appNode);
                }
            } finally {
                DatabaseUtils.writeLock.unlock();
            }
            this.eventBus.post(new NodeIDEvent(address));
        }
    }

    /**
     * @return runtime 当前 Node（可能为 null）
     */
    Node runtimeNode() {
        return this.runtimeController.getRuntimeState().node;
    }

    /**
     * @return runtime 控制器（仅包内事件处理器使用）
     */
    RuntimeCoreController runtimeController() {
        return this.runtimeController;
    }

    /**
     * @return runtime 当前 TUN/TAP 适配器（可能为 null）
     */
    private TunTapAdapter runtimeTunTapAdapter() {
        return this.runtimeController.getRuntimeState().tunTapAdapter;
    }

    /**
     * @return runtime 当前 UDP 桥接器（可能为 null）
     */
    private Thread runtimeUdpThread() {
        return this.runtimeController.getRuntimeState().udpThread;
    }

    /**
     * @return runtime 当前后台线程（可能为 null）
     */
    private Thread runtimeVpnThread() {
        return this.runtimeController.getRuntimeState().vpnThread;
    }

    /**
     * 进入“仅监听”模式：停止 ZeroTier 转发运行时，但保持服务与网络变化监听存活。
     *
     * @param reason 触发原因（日志用途）
     * @param detail 探测诊断信息
     */
    void enterMonitorOnlyMode(String reason, String detail) {
        // 监听态定义：保留 Service 与网络回调，仅停止 ZeroTier 数据转发运行时
        this.monitorOnlyMode = true;
        Log.i(TAG, "Intranet detected, enter monitor-only mode. reason=" + reason + ", detail=" + detail);
        Log.w(TRACE, "Service.enterMonitorOnlyMode reason=" + reason + ", detail=" + detail);
        stopZeroTierRuntime(true);
    }

    /**
     * @return 当前是否为监听态
     */
    boolean isMonitorOnlyMode() {
        return this.monitorOnlyMode;
    }

    /**
     * 设置监听态标记。
     *
     * @param monitorOnlyMode 监听态标记
     */
    void setMonitorOnlyMode(boolean monitorOnlyMode) {
        this.monitorOnlyMode = monitorOnlyMode;
    }

    /**
     * 按需准备旧版本 Android 的组播扫描线程。
     */
    private void ensureLegacyMulticastScannersPrepared() {
        if (Build.VERSION.SDK_INT >= 29) {
            return;
        }
        if (this.v4MulticastScanner == null) {
            this.v4MulticastScanner = MulticastScannerThreadFactory.createIpv4(
                    this::runtimeNode,
                    () -> this.runtimeController.getActiveNetworkId(),
                    TAG
            );
        }
        if (!this.disableIPv6 &&
                this.v6MulticastScanner == null) {
            this.v6MulticastScanner = MulticastScannerThreadFactory.createIpv6(
                    this::runtimeNode,
                    () -> this.runtimeController.getActiveNetworkId(),
                    TAG
            );
        }
    }

    /**
     * 停止 ZeroTier 运行时资源。
     *
     * @param keepServiceAlive true: 仅停止 ZeroTier 转发运行时，保留服务与网络监听；
     *                         false: 按完整停服流程回收并退出服务
     */
    private void stopZeroTierRuntime(boolean keepServiceAlive) {
        Log.i(TRACE, "Service.stopZeroTierRuntime enter keepServiceAlive=" + keepServiceAlive
                + ", node=" + (runtimeNode() != null)
                + ", vpnSocket=" + this.runtimeController.hasVpnSocket()
                + ", udpThreadAlive=" + (runtimeUdpThread() != null && runtimeUdpThread().isAlive())
                + ", vpnThreadAlive=" + (runtimeVpnThread() != null && runtimeVpnThread().isAlive())
                + ", v4ScannerRunning=" + (this.v4MulticastScanner != null && this.v4MulticastScanner.isRunning())
                + ", v6ScannerRunning=" + (this.v6MulticastScanner != null && this.v6MulticastScanner.isRunning()));
        boolean hadNode = runtimeNode() != null;
        if (this.v4MulticastScanner != null) {
            this.v4MulticastScanner.stop();
        }
        if (this.v6MulticastScanner != null) {
            this.v6MulticastScanner.stop();
        }
        this.runtimeController.stopNodeRuntime();
        this.v4MulticastScanner = null;
        this.v6MulticastScanner = null;
        if (hadNode) {
            this.eventBus.post(new NodeDestroyedEvent(keepServiceAlive));
        }
        this.notificationController.resetState();
        this.notificationController.cancel(ZT_NOTIFICATION_TAG);
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        if (!keepServiceAlive) {
            this.autoRoutePolicyCoordinator.clearManualProtectionDeadline();
            // 完整停服：注销网络监听并请求系统停止当前 Service
            unregisterNetworkChangeMonitor();
            if (!stopSelfResult(this.mStartID)) {
                Log.e(TAG, "stopSelfResult() failed!");
            }
            if (this.eventBus.isRegistered(this.zeroTierOneServiceEventHandler)) {
                this.eventBus.unregister(this.zeroTierOneServiceEventHandler);
            }
        }
        Log.i(TRACE, "Service.stopZeroTierRuntime exit keepServiceAlive=" + keepServiceAlive);
    }

    /**
     * 停止 ZeroTier 服务相关资源与线程，并退出服务。
     */
    public void stopZeroTier() {
        stopZeroTierRuntime(false);
    }

    public void onDestroy() {
        try {
            unregisterNetworkChangeMonitor();
            stopZeroTier();
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            this.runtimeController.closeTunnelIo();
            stopSelf(this.mStartID);
            if (this.eventBus.isRegistered(this.zeroTierOneServiceEventHandler)) {
                this.eventBus.unregister(this.zeroTierOneServiceEventHandler);
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
        } finally {
            super.onDestroy();
        }
    }

    public void onRevoke() {
        stopZeroTier();
        this.runtimeController.closeTunnelIo();
        stopSelf(this.mStartID);
        if (this.eventBus.isRegistered(this.zeroTierOneServiceEventHandler)) {
            this.eventBus.unregister(this.zeroTierOneServiceEventHandler);
        }
        super.onRevoke();
    }

    public void run() {
        Log.d(TAG, "ZeroTierOne Service Started");
        Node node = runtimeNode();
        if (node == null) {
            Log.w(TAG, "Node is null when service run loop starts.");
            return;
        }
        Log.d(TAG, "This Node Address: " + com.zerotier.sdk.util.StringUtils.addressToString(node.address()));
        while (!Thread.interrupted()) {
            try {
                // 在后台任务截止期前循环进行后台任务
                var taskDeadline = this.nextBackgroundTaskDeadline;
                long currentTime = System.currentTimeMillis();
                int cmp = Long.compare(taskDeadline, currentTime);
                if (cmp <= 0) {
                    long[] newDeadline = {0};
                    var taskResult = node.processBackgroundTasks(currentTime, newDeadline);
                    synchronized (this) {
                        this.nextBackgroundTaskDeadline = newDeadline[0];
                    }
                    if (taskResult != ResultCode.RESULT_OK) {
                        Log.e(TAG, "Error on processBackgroundTasks: " + taskResult.toString());
                        shutdown();
                    }
                }
                refreshForegroundNotificationIfNeeded(currentTime);
                Thread.sleep(cmp > 0 ? taskDeadline - currentTime : 100);
            } catch (InterruptedException ignored) {
                break;
            } catch (Exception e) {
                Log.e(TAG, e.toString(), e);
            }
        }
        Log.d(TAG, "ZeroTierOne Service Ended");
    }

    private void refreshForegroundNotificationIfNeeded(long now) {
        this.notificationController.refreshTrafficIfNeeded(
                now,
                NOTIFICATION_UPDATE_INTERVAL_MS,
                () -> runtimeTunTapAdapter() == null ? new long[]{0L, 0L} : runtimeTunTapAdapter().consumeTrafficStats(),
                this.runtimeController.hasVpnSocket(),
                ZT_NOTIFICATION_TAG
        );
    }

    /**
     * 离开 ZT 网络
     */
    public void leaveNetwork(long networkId) {
        if (runtimeNode() == null) {
            Log.e(TAG, "Can't leave network if ZeroTier isn't running");
            return;
        }
        NodeRuntimeCore.LeaveResult leaveResult = this.runtimeController.leave(runtimeNode(), networkId);
        var result = leaveResult.getResultCode();
        if (result != ResultCode.RESULT_OK) {
            this.eventBus.post(new ErrorEvent(result));
            return;
        }
        if (!leaveResult.isNoNetworksLeft()) {
            return;
        }
        stopZeroTier();
        this.runtimeController.closeTunnelIo();
        stopSelf(this.mStartID);
    }

    /**
     * Zerotier 事件回调
     *
     * @param event {@link Event} enum
     */
    @Override
    public void onEvent(Event event) {
        Log.d(TAG, "Event: " + event.toString());
        // 更新节点状态
        // 启动/停止切换瞬间 node 可能短暂为空，必须做空值保护。
        if (runtimeNode() != null && runtimeNode().isInited()) {
            this.eventBus.post(new NodeStatusEvent(runtimeNode().status(), runtimeNode().getVersion()));
        }
    }

    @Override // com.zerotier.sdk.EventListener
    public void onTrace(String str) {
        Log.d(TAG, "Trace: " + str);
    }

    /**
     * 当 ZT 网络配置发生更新。
     * <p>
     * 与原始实现保持一致：UP 仅记录，CONFIG_UPDATE 才触发配置入库和网络重配置。
     *
     * @param networkId 网络 ID
     * @param op        配置操作类型
     * @param config    新的网络配置
     * @return SDK 回调返回码（当前固定 0）
     */
    @Override
    public int onNetworkConfigurationUpdated(long networkId, VirtualNetworkConfigOperation op, VirtualNetworkConfig config) {
        Log.i(TAG, "Virtual Network Config Operation: " + op);
        Log.i(TRACE, "Service.onNetworkConfigurationUpdated networkId="
                + com.zerotier.sdk.util.StringUtils.networkIdToString(networkId)
                + ", op=" + op + ", status=" + (config == null ? "null" : config.getStatus())
                + ", name=" + (config == null ? "null" : config.getName()));
        NetworkConfigSyncController.UpdateDispatch dispatch =
                this.networkConfigSyncController.onNetworkConfigurationUpdated(this, networkId, op, config);
        if (dispatch.shouldDispatchReconfigure) {
            Log.i(TRACE, "Service.onNetworkConfigurationUpdated configUpdate isChanged=" + dispatch.changed);
            this.eventBus.post(new NetworkReconfigureEvent(dispatch.changed, dispatch.network, config));
        }
        return 0;
    }

    protected void shutdown() {
        stopZeroTier();
        this.runtimeController.closeTunnelIo();
        stopSelf(this.mStartID);
    }

    /**
     * 根据网络配置重新配置 VPN 隧道，并刷新前台通知。
     *
     * @param network 目标网络
     * @return 是否成功完成隧道重配置
     */
    @SuppressLint("ForegroundServiceType")
    boolean reconfigureTunnelForNetwork(Network network) {
        long networkId = network.getNetworkId();
        var virtualNetworkConfig = this.networkConfigSyncController.getVirtualNetworkConfig(networkId);
        Log.i(TRACE, "Service.reconfigureTunnelForNetwork enter networkId="
                + com.zerotier.sdk.util.StringUtils.networkIdToString(networkId)
                + ", hasConfig=" + (virtualNetworkConfig != null));
        if (virtualNetworkConfig == null) {
            Log.w(TRACE, "Service.reconfigureTunnelForNetwork skip: virtualNetworkConfig is null");
            return false;
        }

        // 第一步：停止旧 TUN/TAP 线程并清理路由映射，避免旧配置残留。
        TunTapAdapter tunTapAdapter = runtimeTunTapAdapter();
        if (tunTapAdapter == null) {
            Log.e(TAG, "TunTapAdapter is null during tunnel reconfigure");
            return false;
        }
        if (tunTapAdapter.isRunning()) {
            tunTapAdapter.interrupt();
            try {
                tunTapAdapter.join();
            } catch (InterruptedException ignored) {
            }
        }
        tunTapAdapter.clearRouteMap();

        // 第二步：关闭旧 VPN FD 与 IO 流，保证后续 re-establish 为干净状态。
        this.runtimeController.closeTunnelIo();

        // 第三步：调用 runtime core 执行隧道重配置，Service 仅负责编排。
        TunnelRuntimeCore.BuildResult buildResult = this.runtimeController.reconfigureTunnel(
                new TunnelRuntimeCore.BuildRequest(
                        this,
                        runtimeNode(),
                        tunTapAdapter,
                        network,
                        virtualNetworkConfig,
                        this.disableIPv6,
                        DISALLOWED_APPS,
                        TAG,
                        TRACE
                )
        );
        if (!buildResult.success) {
            // 构建失败统一转为 VPNErrorEvent，保持 UI 错误处理链路不变。
            this.eventBus.post(new VPNErrorEvent(buildResult.errorMessage));
            return false;
        }
        this.runtimeController.applyTunnelIo(buildResult);
        String connectedNetworkName = buildResult.connectedNetworkName;
        String connectedNetworkIdText = buildResult.connectedNetworkIdText;
        this.notificationController.bindConnectedNetwork(connectedNetworkName, connectedNetworkIdText);

        // 第四步：更新前台通知状态，向用户反馈连接成功。
        startForeground(ZT_NOTIFICATION_TAG, this.notificationController.buildConnectedNotification());
        Log.i(TAG, "ZeroTier One Connected");
        Log.i(TRACE, "Service.reconfigureTunnelForNetwork connected networkId="
                + com.zerotier.sdk.util.StringUtils.networkIdToString(networkId)
                + ", networkName=" + connectedNetworkName);

        // 旧版本 Android 多播处理
        if (Build.VERSION.SDK_INT < 29) {
            ensureLegacyMulticastScannersPrepared();
            if (this.v4MulticastScanner != null && !this.v4MulticastScanner.isRunning()) {
                this.v4MulticastScanner.start();
            }
            if (!this.disableIPv6 && this.v6MulticastScanner != null && !this.v6MulticastScanner.isRunning()) {
                this.v6MulticastScanner.start();
            }
        }
        return true;
    }

    /**
     * 当前网络入轨 Moon
     *
     * @param moonWorldId Moon 节点地址
     * @param moonSeed    Moon 种子节点地址
     */
    public void orbitNetwork(Long moonWorldId, Long moonSeed) {
        if (runtimeNode() == null) {
            Log.e(TAG, "Can't orbit network if ZeroTier isn't running");
            return;
        }
        // 入轨
        ResultCode result = runtimeNode().orbit(moonWorldId, moonSeed);
        if (result != ResultCode.RESULT_OK) {
            Log.e(TAG, "Failed to orbit " + Long.toHexString(moonWorldId));
            this.eventBus.post(new ErrorEvent(result));
        }
    }

    public class ZeroTierBinder extends Binder {
        public ZeroTierBinder() {
        }

        public ZeroTierOneService getService() {
            return ZeroTierOneService.this;
        }
    }

    /**
     * 注册网络变化监听器。网络发生变化后触发一次自动路由策略检测。
     */
    private synchronized void registerNetworkChangeMonitorIfNeeded() {
        if (this.networkChangeObserver == null) {
            this.networkChangeObserver = new NetworkChangeObserver(this, TAG, TRACE);
        }
        this.networkChangeObserver.start(reason ->
                this.autoRoutePolicyCoordinator.triggerAutoRoutePolicyCheckAsync(
                        this,
                        this.eventBus,
                        this.policyRuntimeDelegate,
                        reason
                ));
    }

    /**
     * 注销网络变化监听器。
     */
    private synchronized void unregisterNetworkChangeMonitor() {
        if (this.networkChangeObserver != null) {
            this.networkChangeObserver.stop();
        }
        this.autoRoutePolicyCoordinator.resetRunningState();
    }

}
