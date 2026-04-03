package net.kaaass.zerotierfix.service;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
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
import com.zerotier.sdk.VirtualNetworkStatus;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.ZerotierFixApplication;
import net.kaaass.zerotierfix.events.AfterJoinNetworkEvent;
import net.kaaass.zerotierfix.events.ErrorEvent;
import net.kaaass.zerotierfix.events.IntranetCheckStateEvent;
import net.kaaass.zerotierfix.events.IsServiceRunningReplyEvent;
import net.kaaass.zerotierfix.events.IsServiceRunningRequestEvent;
import net.kaaass.zerotierfix.events.ManualDisconnectEvent;
import net.kaaass.zerotierfix.events.NetworkConfigChangedByUserEvent;
import net.kaaass.zerotierfix.events.NetworkListReplyEvent;
import net.kaaass.zerotierfix.events.NetworkListRequestEvent;
import net.kaaass.zerotierfix.events.NetworkReconfigureEvent;
import net.kaaass.zerotierfix.events.NodeDestroyedEvent;
import net.kaaass.zerotierfix.events.NodeIDEvent;
import net.kaaass.zerotierfix.events.NodeStatusEvent;
import net.kaaass.zerotierfix.events.NodeStatusRequestEvent;
import net.kaaass.zerotierfix.events.OrbitMoonEvent;
import net.kaaass.zerotierfix.events.PeerInfoReplyEvent;
import net.kaaass.zerotierfix.events.PeerInfoRequestEvent;
import net.kaaass.zerotierfix.events.StopEvent;
import net.kaaass.zerotierfix.events.VPNErrorEvent;
import net.kaaass.zerotierfix.events.VirtualNetworkConfigChangedEvent;
import net.kaaass.zerotierfix.events.VirtualNetworkConfigReplyEvent;
import net.kaaass.zerotierfix.events.VirtualNetworkConfigRequestEvent;
import net.kaaass.zerotierfix.model.AppNode;
import net.kaaass.zerotierfix.model.MoonOrbit;
import net.kaaass.zerotierfix.model.Network;
import net.kaaass.zerotierfix.model.NetworkDao;
import net.kaaass.zerotierfix.model.type.DNSMode;
import net.kaaass.zerotierfix.model.type.NetworkStatus;
import net.kaaass.zerotierfix.service.network.NetworkChangeObserver;
import net.kaaass.zerotierfix.service.notification.ServiceNotificationController;
import net.kaaass.zerotierfix.service.policy.RoutePolicyEngine;
import net.kaaass.zerotierfix.service.runtime.ZeroTierRuntimeController;
import net.kaaass.zerotierfix.util.Constants;
import net.kaaass.zerotierfix.util.DatabaseUtils;
import net.kaaass.zerotierfix.util.InetAddressUtils;
import net.kaaass.zerotierfix.util.NetworkInfoUtils;
import net.kaaass.zerotierfix.util.StringUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * ZeroTier 核心前台服务。
 * <p>
 * 负责节点生命周期、VPN 建链、数据收发与状态广播。
 */
public class ZeroTierOneService extends VpnService implements Runnable, EventListener, VirtualNetworkConfigListener {
    public static final int MSG_JOIN_NETWORK = 1;
    public static final int MSG_LEAVE_NETWORK = 2;
    public static final String ZT1_NETWORK_ID = "com.zerotier.one.network_id";
    public static final String ZT1_USE_DEFAULT_ROUTE = "com.zerotier.one.use_default_route";
    private static final String[] DISALLOWED_APPS = {"com.android.vending"};
    private static final String TAG = "ZT1_Service";
    private static final String TRACE = "CONNECT_TRACE";
    private static final int ZT_NOTIFICATION_TAG = 5919812;
    private static final long NOTIFICATION_UPDATE_INTERVAL_MS = 1000L;
    private static final long MANUAL_CONNECT_PROTECTION_MS = 15000L;
    private final IBinder mBinder = new ZeroTierBinder();
    private final DataStore dataStore = new DataStore(this);
    private final EventBus eventBus = EventBus.getDefault();
    private final ZeroTierRuntimeController runtimeController = new ZeroTierRuntimeController();
    private final Map<Long, VirtualNetworkConfig> virtualNetworkConfigMap = new HashMap();
    FileInputStream in;
    FileOutputStream out;
    DatagramSocket svrSocket;
    ParcelFileDescriptor vpnSocket;
    private int bindCount = 0;
    private boolean disableIPv6 = false;
    private int mStartID = -1;
    private long networkId = 0;
    private long nextBackgroundTaskDeadline = 0;
    private Node node;
    private TunTapAdapter tunTapAdapter;
    private UdpCom udpCom;
    private Thread udpThread;
    private NetworkChangeObserver networkChangeObserver;
    private final ServiceNotificationController notificationController =
            new ServiceNotificationController(this, TRACE);
    private final Object autoRoutePolicyLock = new Object();
    private boolean autoRouteCheckRunning = false;
    /**
     * 手动连接保护截止时间（elapsedRealtime）。
     * 在保护窗口内，自动策略不会把刚手动建立的连接立即切回监听态。
     */
    private volatile long manualConnectProtectionDeadlineMs = 0L;
    /**
     * 当前是否处于“仅监听网络变化，不启用 ZeroTier 转发”的模式。
     * true: 服务保活，但不建立 ZeroTier 转发链路；
     * false: 服务可按正常流程连接并转发。
     */
    private volatile boolean monitorOnlyMode = false;
    private Thread v4MulticastScanner = new Thread() {
        /* class com.zerotier.one.service.ZeroTierOneService.AnonymousClass1 */
        List<String> subscriptions = new ArrayList<>();

        @Override
        public void run() {
            Log.d(ZeroTierOneService.TAG, "IPv4 Multicast Scanner Thread Started.");
            while (!isInterrupted()) {
                try {
                    List<String> groups = NetworkInfoUtils.listMulticastGroupOnInterface("tun0", false);

                    ArrayList<String> arrayList2 = new ArrayList<>(this.subscriptions);
                    ArrayList<String> arrayList3 = new ArrayList<>(groups);
                    arrayList3.removeAll(arrayList2);
                    for (String str : arrayList3) {
                        try {
                            byte[] hexStringToByteArray = StringUtils.hexStringToBytes(str);
                            for (int i = 0; i < hexStringToByteArray.length / 2; i++) {
                                byte b = hexStringToByteArray[i];
                                hexStringToByteArray[i] = hexStringToByteArray[(hexStringToByteArray.length - i) - 1];
                                hexStringToByteArray[(hexStringToByteArray.length - i) - 1] = b;
                            }
                            ResultCode multicastSubscribe = ZeroTierOneService.this.node.multicastSubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(hexStringToByteArray)));
                            if (multicastSubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastSubscribe: " + multicastSubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    arrayList2.removeAll(new ArrayList<>(groups));
                    for (String str2 : arrayList2) {
                        try {
                            byte[] hexStringToByteArray2 = StringUtils.hexStringToBytes(str2);
                            for (int i2 = 0; i2 < hexStringToByteArray2.length / 2; i2++) {
                                byte b2 = hexStringToByteArray2[i2];
                                hexStringToByteArray2[i2] = hexStringToByteArray2[(hexStringToByteArray2.length - i2) - 1];
                                hexStringToByteArray2[(hexStringToByteArray2.length - i2) - 1] = b2;
                            }
                            ResultCode multicastUnsubscribe = ZeroTierOneService.this.node.multicastUnsubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(hexStringToByteArray2)));
                            if (multicastUnsubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastUnsubscribe: " + multicastUnsubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    this.subscriptions = groups;
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.d(ZeroTierOneService.TAG, "V4 Multicast Scanner Thread Interrupted", e);
                    break;
                }
            }
            Log.d(ZeroTierOneService.TAG, "IPv4 Multicast Scanner Thread Ended.");
        }
    };
    private Thread v6MulticastScanner = new Thread() {
        /* class com.zerotier.one.service.ZeroTierOneService.AnonymousClass2 */
        List<String> subscriptions = new ArrayList<>();

        @Override
        public void run() {
            Log.d(ZeroTierOneService.TAG, "IPv6 Multicast Scanner Thread Started.");
            while (!isInterrupted()) {
                try {
                    List<String> groups = NetworkInfoUtils.listMulticastGroupOnInterface("tun0", true);

                    ArrayList<String> arrayList2 = new ArrayList<>(this.subscriptions);
                    ArrayList<String> arrayList3 = new ArrayList<>(groups);
                    arrayList3.removeAll(arrayList2);
                    for (String str : arrayList3) {
                        try {
                            ResultCode multicastSubscribe = ZeroTierOneService.this.node.multicastSubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(StringUtils.hexStringToBytes(str))));
                            if (multicastSubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastSubscribe: " + multicastSubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    arrayList2.removeAll(new ArrayList<>(groups));
                    for (String str2 : arrayList2) {
                        try {
                            ResultCode multicastUnsubscribe = ZeroTierOneService.this.node.multicastUnsubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(StringUtils.hexStringToBytes(str2))));
                            if (multicastUnsubscribe != ResultCode.RESULT_OK) {
                                Log.e(ZeroTierOneService.TAG, "Error when calling multicastUnsubscribe: " + multicastUnsubscribe);
                            }
                        } catch (Exception e) {
                            Log.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    this.subscriptions = groups;
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.d(ZeroTierOneService.TAG, "V6 Multicast Scanner Thread Interrupted", e);
                    break;
                }
            }
            Log.d(ZeroTierOneService.TAG, "IPv6 Multicast Scanner Thread Ended.");
        }
    };
    private Thread vpnThread;

    public VirtualNetworkConfig getVirtualNetworkConfig(long j) {
        VirtualNetworkConfig virtualNetworkConfig;
        synchronized (this.virtualNetworkConfigMap) {
            virtualNetworkConfig = this.virtualNetworkConfigMap.get(Long.valueOf(j));
        }
        return virtualNetworkConfig;
    }

    public VirtualNetworkConfig setVirtualNetworkConfig(long j, VirtualNetworkConfig virtualNetworkConfig) {
        VirtualNetworkConfig put;
        synchronized (this.virtualNetworkConfigMap) {
            put = this.virtualNetworkConfigMap.put(Long.valueOf(j), virtualNetworkConfig);
        }
        return put;
    }

    public VirtualNetworkConfig clearVirtualNetworkConfig(long j) {
        VirtualNetworkConfig remove;
        synchronized (this.virtualNetworkConfigMap) {
            remove = this.virtualNetworkConfigMap.remove(Long.valueOf(j));
        }
        return remove;
    }

    private void logBindCount() {
        Log.i(TAG, "Bind Count: " + this.bindCount);
    }

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
     * 2) 初始化 Node / UDP / VPN 工作线程；
     * 3) 调用 {@link #joinNetwork(long)} 发起入网；
     * 4) 后续由 {@link #onNetworkConfigurationUpdated(long, VirtualNetworkConfigOperation, VirtualNetworkConfig)}
     * 回调驱动隧道配置与通知刷新。
     *
     * @param intent  启动参数，包含可选网络 ID
     * @param flags   Service 启动标记
     * @param startId 系统分配的启动序号
     * @return Service 重启策略
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long networkId;
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
        if (!this.eventBus.isRegistered(this)) {
            this.eventBus.register(this);
        }

        // 手动连接（携带 networkId）与自动连接（不携带 networkId）需要区分策略
        boolean hasExplicitNetworkId = intent.hasExtra(ZT1_NETWORK_ID);
        Log.i(TRACE, "Service.onStartCommand hasExplicitNetworkId=" + hasExplicitNetworkId);

        // 确定待启动的网络 ID
        if (hasExplicitNetworkId) {
            // Intent 中指定了目标网络，直接使用此 ID
            networkId = intent.getLongExtra(ZT1_NETWORK_ID, 0);
        } else {
            // 默认启用最近一次启动的网络
            DatabaseUtils.readLock.lock();
            try {
                var daoSession = ((ZerotierFixApplication) getApplication()).getDaoSession();
                daoSession.clear();
                var lastActivatedNetworks = daoSession.getNetworkDao().queryBuilder()
                        .where(NetworkDao.Properties.LastActivated.eq(true))
                        .list();
                if (lastActivatedNetworks == null || lastActivatedNetworks.isEmpty()) {
                    Log.e(TAG, "Couldn't find last activated connection");
                    return START_NOT_STICKY;
                } else if (lastActivatedNetworks.size() > 1) {
                    Log.e(TAG, "Multiple networks marked as last connected: " + lastActivatedNetworks.size());
                    for (Network network : lastActivatedNetworks) {
                        Log.e(TAG, "ID: " + Long.toHexString(network.getNetworkId()));
                    }
                    throw new IllegalStateException("Database is inconsistent");
                } else {
                    networkId = lastActivatedNetworks.get(0).getNetworkId();
                    Log.i(TAG, "Got Always On request for ZeroTier");
                }
            } finally {
                DatabaseUtils.readLock.unlock();
            }
        }
        if (networkId == 0) {
            Log.e(TAG, "Network ID not provided to service");
            Log.e(TRACE, "Service.onStartCommand abort: networkId=0");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        this.networkId = networkId;
        Log.i(TRACE, "Service.onStartCommand resolvedNetworkId="
                + com.zerotier.sdk.util.StringUtils.networkIdToString(networkId));
        if (hasExplicitNetworkId) {
            this.manualConnectProtectionDeadlineMs = SystemClock.elapsedRealtime() + MANUAL_CONNECT_PROTECTION_MS;
            Log.i(TRACE, "Service.onStartCommand manualConnectProtection until="
                    + this.manualConnectProtectionDeadlineMs);
        } else {
            this.manualConnectProtectionDeadlineMs = 0L;
        }
        registerNetworkChangeMonitorIfNeeded();

        // 检查当前的网络环境
        var preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useCellularData = preferences.getBoolean(Constants.PREF_NETWORK_USE_CELLULAR_DATA, false);
        this.disableIPv6 = preferences.getBoolean(Constants.PREF_NETWORK_DISABLE_IPV6, false);
        var currentNetworkInfo = NetworkInfoUtils.getNetworkInfoCurrentConnection(this);

        if (currentNetworkInfo == NetworkInfoUtils.CurrentConnection.CONNECTION_NONE) {
            // 未连接网络
            Toast.makeText(this, R.string.toast_no_network, Toast.LENGTH_SHORT).show();
            Log.w(TRACE, "Service.onStartCommand abort: no network");
            stopSelf(this.mStartID);
            return START_NOT_STICKY;
        } else if (currentNetworkInfo == NetworkInfoUtils.CurrentConnection.CONNECTION_MOBILE &&
                !useCellularData) {
            // 使用移动网络，但未在设置中允许移动网络访问
            Toast.makeText(this, R.string.toast_mobile_data, Toast.LENGTH_LONG).show();
            Log.w(TRACE, "Service.onStartCommand abort: mobile disallowed");
            stopSelf(this.mStartID);
            return START_NOT_STICKY;
        }

        // 启动策略：先进行内网探测。命中内网则进入监听态并跳过 ZeroTier 转发。
        if (handleIntranetPolicy("service_start")) {
            return START_STICKY;
        }

        if (!startOrResumeZeroTierAndJoin(networkId, "service_start")) {
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    /**
     * 按“内网优先”策略执行探测并处理服务模式切换。
     * <p>
     * 返回 true 表示已进入监听态并跳过 ZeroTier 转发；
     * 返回 false 表示应继续正常启动/恢复 ZeroTier 转发。
     *
     * @param reason 触发原因（用于日志与事件追踪）
     * @return 是否应跳过 ZeroTier 转发
     */
    private boolean handleIntranetPolicy(String reason) {
        // 统一通过策略引擎完成判定，Service 仅消费决策结果并执行动作
        RoutePolicyEngine.Decision decision = RoutePolicyEngine.evaluate(this, reason);
        boolean policyEnabled = !("auto_route_check_disabled".equals(decision.getDetail()));
        this.eventBus.post(new IntranetCheckStateEvent(
                policyEnabled,
                decision.getInIntranet(),
                reason,
                decision.getDetail()
        ));

        if (!policyEnabled) {
            this.monitorOnlyMode = false;
            return false;
        }
        if (decision.getInIntranet() == null) {
            this.monitorOnlyMode = false;
            return false;
        }
        if (decision.getAction() == RoutePolicyEngine.PolicyAction.ENTER_MONITOR_ONLY) {
            // 命中内网：切换到监听态（服务保活、ZeroTier 转发停止）
            enterMonitorOnlyMode(reason, decision.getDetail());
            return true;
        }
        // 不在内网：若此前处于监听态，则允许后续恢复 ZeroTier 转发
        if (this.monitorOnlyMode) {
            Log.i(TRACE, "Service.handleIntranetPolicy resumeZeroTier reason=" + reason
                    + ", detail=" + decision.getDetail());
            this.monitorOnlyMode = false;
        }
        return false;
    }

    /**
     * 启动或恢复 ZeroTier 运行时，并加入指定网络。
     *
     * @param networkId 目标网络 ID
     * @param reason    触发原因（日志用途）
     * @return true 表示成功触发加入流程；false 表示运行时初始化失败
     */
    private boolean startOrResumeZeroTierAndJoin(long networkId, String reason) {
        this.networkId = networkId;
        synchronized (this) {
            try {
                // 仅初始化运行时，不在此处做网络策略判断，避免职责混乱
                ensureZeroTierRuntime(networkId);
                Log.i(TRACE, "Service.startOrResumeZeroTierAndJoin runtimeReady reason=" + reason
                        + ", node=" + (this.node != null)
                        + ", vpnThreadAlive=" + (this.vpnThread != null && this.vpnThread.isAlive())
                        + ", udpThreadAlive=" + (this.udpThread != null && this.udpThread.isAlive()));
            } catch (Exception e) {
                Log.e(TAG, e.toString(), e);
                Log.e(TRACE, "Service.startOrResumeZeroTierAndJoin exception=" + e.getClass().getSimpleName()
                        + ", message=" + e.getMessage());
                return false;
            }
        }
        this.monitorOnlyMode = false;
        // 运行时就绪后由 joinNetwork 触发 SDK 入网流程与后续配置回调
        Log.i(TRACE, "Service.startOrResumeZeroTierAndJoin joinNetwork="
                + com.zerotier.sdk.util.StringUtils.networkIdToString(networkId)
                + ", reason=" + reason);
        joinNetwork(networkId);
        return true;
    }

    /**
     * 确保 ZeroTier 运行时已初始化（节点、UDP、后台任务线程）。
     *
     * @param networkId 目标网络 ID，用于初始化 TunTapAdapter
     * @throws Exception 初始化过程中的异常
     */
    private void ensureZeroTierRuntime(long networkId) throws Exception {
        ZeroTierRuntimeController.StartRuntimeRequest request =
                new ZeroTierRuntimeController.StartRuntimeRequest(
                        networkId,
                        this.svrSocket,
                        this.udpCom,
                        this.tunTapAdapter,
                        this.node,
                        this.vpnThread,
                        this.udpThread,
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
        ZeroTierRuntimeController.StartRuntimeResult result = this.runtimeController.startRuntime(request);
        this.svrSocket = result.socket;
        this.udpCom = result.udpCom;
        this.tunTapAdapter = result.tunTapAdapter;
        this.node = result.node;
        this.vpnThread = result.vpnThread;
        this.udpThread = result.udpThread;
        // 关键时序：先把 node/thread 回填到 Service 字段，再启动线程，
        // 避免线程启动后回调访问到尚未赋值的 this.node（导致 NPE）。
        if (this.vpnThread != null && this.vpnThread.getState() == Thread.State.NEW) {
            this.vpnThread.start();
        }
        if (this.udpThread != null && this.udpThread.getState() == Thread.State.NEW) {
            this.udpThread.start();
        }
        if (result.nodeCreated) {
            Log.d(TAG, "ZeroTierOne Node Initialized");
            this.onNodeStatusRequest(null);
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
     * 进入“仅监听”模式：停止 ZeroTier 转发运行时，但保持服务与网络变化监听存活。
     *
     * @param reason 触发原因（日志用途）
     * @param detail 探测诊断信息
     */
    private void enterMonitorOnlyMode(String reason, String detail) {
        // 监听态定义：保留 Service 与网络回调，仅停止 ZeroTier 数据转发运行时
        this.monitorOnlyMode = true;
        Log.i(TAG, "Intranet detected, enter monitor-only mode. reason=" + reason + ", detail=" + detail);
        Log.w(TRACE, "Service.enterMonitorOnlyMode reason=" + reason + ", detail=" + detail);
        stopZeroTierRuntime(true);
    }

    /**
     * 确保 UDP 监听线程处于运行状态。
     * <p>
     * 注意：Java Thread 对象不可重复 start，因此在线程终止后必须新建实例。
     */
    private void ensureUdpThreadRunning() {
        if (this.udpCom == null) {
            return;
        }
        if (this.udpThread != null && this.udpThread.isAlive()) {
            return;
        }
        Thread thread = new Thread(this.udpCom, "UDP Communication Thread");
        this.udpThread = thread;
        thread.start();
    }

    /**
     * 停止 ZeroTier 运行时资源。
     *
     * @param keepServiceAlive true: 仅停止 ZeroTier 转发运行时，保留服务与网络监听；
     *                         false: 按完整停服流程回收并退出服务
     */
    private void stopZeroTierRuntime(boolean keepServiceAlive) {
        Log.i(TRACE, "Service.stopZeroTierRuntime enter keepServiceAlive=" + keepServiceAlive
                + ", node=" + (this.node != null)
                + ", vpnSocket=" + (this.vpnSocket != null)
                + ", udpThreadAlive=" + (this.udpThread != null && this.udpThread.isAlive())
                + ", vpnThreadAlive=" + (this.vpnThread != null && this.vpnThread.isAlive()));
        boolean hadNode = this.node != null;
        ZeroTierRuntimeController.StopRuntimeRequest request =
                new ZeroTierRuntimeController.StopRuntimeRequest(
                        this.svrSocket,
                        this.udpCom,
                        this.tunTapAdapter,
                        this.udpThread,
                        this.vpnThread,
                        this.v4MulticastScanner,
                        this.v6MulticastScanner,
                        this.vpnSocket,
                        this.in,
                        this.out,
                        this.node
                );
        this.runtimeController.stopRuntime(request);
        this.svrSocket = null;
        this.udpCom = null;
        this.tunTapAdapter = null;
        this.udpThread = null;
        this.vpnThread = null;
        this.v4MulticastScanner = null;
        this.v6MulticastScanner = null;
        this.vpnSocket = null;
        this.in = null;
        this.out = null;
        this.node = null;
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
            this.manualConnectProtectionDeadlineMs = 0L;
            // 完整停服：注销网络监听并请求系统停止当前 Service
            unregisterNetworkChangeMonitor();
            if (!stopSelfResult(this.mStartID)) {
                Log.e(TAG, "stopSelfResult() failed!");
            }
            if (this.eventBus.isRegistered(this)) {
                this.eventBus.unregister(this);
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
            if (this.vpnSocket != null) {
                try {
                    this.vpnSocket.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing VPN socket: " + e, e);
                }
                this.vpnSocket = null;
            }
            stopSelf(this.mStartID);
            if (this.eventBus.isRegistered(this)) {
                this.eventBus.unregister(this);
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
        } finally {
            super.onDestroy();
        }
    }

    public void onRevoke() {
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket: " + e, e);
            }
            this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
        if (this.eventBus.isRegistered(this)) {
            this.eventBus.unregister(this);
        }
        super.onRevoke();
    }

    public void run() {
        Log.d(TAG, "ZeroTierOne Service Started");
        Log.d(TAG, "This Node Address: " + com.zerotier.sdk.util.StringUtils.addressToString(this.node.address()));
        while (!Thread.interrupted()) {
            try {
                // 在后台任务截止期前循环进行后台任务
                var taskDeadline = this.nextBackgroundTaskDeadline;
                long currentTime = System.currentTimeMillis();
                int cmp = Long.compare(taskDeadline, currentTime);
                if (cmp <= 0) {
                    long[] newDeadline = {0};
                    var taskResult = this.node.processBackgroundTasks(currentTime, newDeadline);
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
                () -> this.tunTapAdapter == null ? new long[]{0L, 0L} : this.tunTapAdapter.consumeTrafficStats(),
                this.vpnSocket != null,
                ZT_NOTIFICATION_TAG
        );
    }

    /**
     * 停止事件回调（通常由 UI 主动关闭触发）。
     *
     * @param stopEvent 停止事件
     */
    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onStopEvent(StopEvent stopEvent) {
        Log.w(TRACE, "Service.onStopEvent received");
        stopZeroTier();
    }

    /**
     * 手动断开事件回调。
     *
     * @param manualDisconnectEvent 手动断开事件
     */
    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onManualDisconnect(ManualDisconnectEvent manualDisconnectEvent) {
        Log.w(TRACE, "Service.onManualDisconnect received");
        stopZeroTier();
    }

    /**
     * 响应“服务是否运行”查询。
     * <p>
     * 保持与原项目行为一致：收到查询即回复 true，由界面侧尝试 bind 获取服务实例。
     *
     * @param event 查询事件
     */
    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onIsServiceRunningRequest(IsServiceRunningRequestEvent event) {
        this.eventBus.post(new IsServiceRunningReplyEvent(true));
    }

    /**
     * 加入 ZT 网络。
     *
     * @param networkId 目标网络 ID
     */
    public void joinNetwork(long networkId) {
        Log.i(TRACE, "Service.joinNetwork enter networkId="
                + com.zerotier.sdk.util.StringUtils.networkIdToString(networkId)
                + ", nodeReady=" + (this.node != null));
        if (this.node == null) {
            // 兜底自恢复：监听态/重连竞态下可能出现 node 为空，先尝试恢复 runtime 再 join。
            try {
                Log.w(TRACE, "Service.joinNetwork node null, try ensure runtime first");
                ensureZeroTierRuntime(networkId);
            } catch (Exception e) {
                Log.e(TAG, "Can't join network if ZeroTier isn't running", e);
                Log.e(TRACE, "Service.joinNetwork abort: ensure runtime failed "
                        + e.getClass().getSimpleName());
                return;
            }
            if (this.node == null) {
                Log.e(TAG, "Can't join network if ZeroTier isn't running");
                Log.e(TRACE, "Service.joinNetwork abort: node still null after ensure");
                return;
            }
        }
        // 连接到新网络
        var result = this.runtimeController.join(this.node, networkId);
        Log.i(TRACE, "Service.joinNetwork result=" + result);
        if (result != ResultCode.RESULT_OK) {
            this.eventBus.post(new ErrorEvent(result));
            return;
        }
        // 连接后事件
        this.eventBus.post(new AfterJoinNetworkEvent());
    }

    /**
     * 离开 ZT 网络
     */
    public void leaveNetwork(long networkId) {
        if (this.node == null) {
            Log.e(TAG, "Can't leave network if ZeroTier isn't running");
            return;
        }
        ZeroTierRuntimeController.LeaveResult leaveResult = this.runtimeController.leave(this.node, networkId);
        var result = leaveResult.getResultCode();
        if (result != ResultCode.RESULT_OK) {
            this.eventBus.post(new ErrorEvent(result));
            return;
        }
        if (!leaveResult.isNoNetworksLeft()) {
            return;
        }
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket", e);
            }
            this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNetworkListRequest(NetworkListRequestEvent requestNetworkListEvent) {
        VirtualNetworkConfig[] networks = this.runtimeController.networkConfigs(this.node);
        if (networks != null && networks.length > 0) {
            this.eventBus.post(new NetworkListReplyEvent(networks));
        }
    }

    /**
     * 请求节点状态事件回调
     *
     * @param event 事件
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNodeStatusRequest(NodeStatusRequestEvent event) {
        // 返回节点状态
        if (this.node != null) {
            this.eventBus.post(new NodeStatusEvent(this.node.status(), this.node.getVersion()));
        }
    }

    /**
     * 请求 Peer 信息事件回调
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRequestPeerInfo(PeerInfoRequestEvent event) {
        var peers = this.runtimeController.peers(this.node);
        if (peers == null) {
            this.eventBus.post(new PeerInfoReplyEvent(null));
            return;
        }
        this.eventBus.post(new PeerInfoReplyEvent(peers));
    }

    /**
     * 请求网络配置事件回调
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onVirtualNetworkConfigRequest(VirtualNetworkConfigRequestEvent event) {
        var config = this.runtimeController.networkConfig(this.node, event.getNetworkId());
        this.eventBus.post(new VirtualNetworkConfigReplyEvent(config));
    }

    /**
     * 收到网络配置变更后执行重配置。
     * <p>
     * 只有配置实际变化时才重建隧道；若网络状态非 OK，仍会上报 UI 便于提示错误状态。
     *
     * @param event 重配置事件（包含变更标记、网络实体与虚拟网络配置）
     */
    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onNetworkReconfigure(NetworkReconfigureEvent event) {
        boolean isChanged = event.isChanged();
        var network = event.getNetwork();
        var networkConfig = event.getVirtualNetworkConfig();
        Log.i(TRACE, "Service.onNetworkReconfigure enter networkId="
                + com.zerotier.sdk.util.StringUtils.networkIdToString(network.getNetworkId())
                + ", isChanged=" + isChanged + ", status=" + networkConfig.getStatus());
        boolean configUpdated = isChanged && updateTunnelConfig(network);
        boolean networkIsOk = networkConfig.getStatus() == VirtualNetworkStatus.NETWORK_STATUS_OK;
        Log.i(TRACE, "Service.onNetworkReconfigure result configUpdated=" + configUpdated
                + ", networkIsOk=" + networkIsOk);

        if (configUpdated || !networkIsOk) {
            this.eventBus.post(new VirtualNetworkConfigChangedEvent(networkConfig));
        } else {
            // 配置未变化但内核仍为 OK 时，也要刷新首页列表，避免卡片状态滞后。
            VirtualNetworkConfig[] networks = this.runtimeController.networkConfigs(this.node);
            if (networks != null) {
                this.eventBus.post(new NetworkListReplyEvent(networks));
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onNetworkConfigChangedByUser(NetworkConfigChangedByUserEvent event) {
        Network network = event.getNetwork();
        if (network.getNetworkId() != this.networkId) {
            return;
        }
        updateTunnelConfig(network);
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
        // 启动/停止切换瞬间 this.node 可能短暂为空，必须做空值保护。
        if (this.node != null && this.node.isInited()) {
            this.eventBus.post(new NodeStatusEvent(this.node.status(), this.node.getVersion()));
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
        DatabaseUtils.writeLock.lock();
        try {
            // 查找网络 ID 对应的配置
            var networkDao = ((ZerotierFixApplication) getApplication())
                    .getDaoSession()
                    .getNetworkDao();
            var matchedNetwork = networkDao.queryBuilder()
                    .where(NetworkDao.Properties.NetworkId.eq(networkId))
                    .list();
            if (matchedNetwork.size() != 1) {
                throw new IllegalStateException("Database is inconsistent");
            }
            var network = matchedNetwork.get(0);
            // 根据当前网络状态确定更改配置的行为
            switch (op) {
                case VIRTUAL_NETWORK_CONFIG_OPERATION_UP:
                    Log.d(TAG, "Network Type: " + config.getType() + " Network Status: " + config.getStatus() + " Network Name: " + config.getName() + " ");
                    // 将网络配置的更新交给第一次 Update
                    break;
                case VIRTUAL_NETWORK_CONFIG_OPERATION_CONFIG_UPDATE:
                    Log.i(TAG, "Network Config Update!");
                    boolean isChanged = setVirtualNetworkConfigAndUpdateDatabase(network, config);
                    Log.i(TRACE, "Service.onNetworkConfigurationUpdated configUpdate isChanged=" + isChanged);
                    this.eventBus.post(new NetworkReconfigureEvent(isChanged, network, config));
                    break;
                case VIRTUAL_NETWORK_CONFIG_OPERATION_DOWN:
                case VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY:
                    Log.d(TAG, "Network Down!");
                    clearVirtualNetworkConfig(networkId);
                    break;
            }
            return 0;
        } finally {
            DatabaseUtils.writeLock.unlock();
        }
    }

    private boolean setVirtualNetworkConfigAndUpdateDatabase(Network network, VirtualNetworkConfig virtualNetworkConfig) {
        if ((DatabaseUtils.writeLock instanceof ReentrantReadWriteLock.WriteLock) && !((ReentrantReadWriteLock.WriteLock) DatabaseUtils.writeLock).isHeldByCurrentThread()) {
            throw new IllegalStateException("DatabaseUtils.writeLock not held");
        }
        VirtualNetworkConfig virtualNetworkConfig2 = getVirtualNetworkConfig(network.getNetworkId());
        setVirtualNetworkConfig(network.getNetworkId(), virtualNetworkConfig);
        var networkConfig = network.getNetworkConfig();
        if (networkConfig != null) {
            try {
                networkConfig.setStatus(NetworkStatus.fromVirtualNetworkStatus(virtualNetworkConfig.getStatus()));
                networkConfig.update();
            } catch (Exception e) {
                Log.e(TAG, "Failed to persist kernel network status", e);
            }
        }
        var networkName = virtualNetworkConfig.getName();
        if (networkName != null && !networkName.isEmpty()) {
            network.setNetworkName(networkName);
        }
        network.update();
        return !virtualNetworkConfig.equals(virtualNetworkConfig2);
    }

    protected void shutdown() {
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket", e);
            }
            this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
    }

    /**
     * 根据当前网络配置重建 VPN 隧道，并刷新前台通知。
     *
     * @param network 目标网络
     * @return 是否成功建立/更新隧道
     */
    private boolean updateTunnelConfig(Network network) {
        long networkId = network.getNetworkId();
        var networkConfig = network.getNetworkConfig();
        var virtualNetworkConfig = getVirtualNetworkConfig(networkId);
        Log.i(TRACE, "Service.updateTunnelConfig enter networkId="
                + com.zerotier.sdk.util.StringUtils.networkIdToString(networkId)
                + ", hasConfig=" + (virtualNetworkConfig != null));
        if (virtualNetworkConfig == null) {
            Log.w(TRACE, "Service.updateTunnelConfig skip: virtualNetworkConfig is null");
            return false;
        }

        // 重启 TUN TAP
        if (this.tunTapAdapter.isRunning()) {
            this.tunTapAdapter.interrupt();
            try {
                this.tunTapAdapter.join();
            } catch (InterruptedException ignored) {
            }
        }
        this.tunTapAdapter.clearRouteMap();

        // 重启 VPN Socket
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
                this.in.close();
                this.out.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN socket: " + e, e);
            }
            this.vpnSocket = null;
            this.in = null;
            this.out = null;
        }

        // 配置 VPN
        Log.i(TAG, "Configuring VpnService.Builder");
        var builder = new VpnService.Builder();
        var assignedAddresses = virtualNetworkConfig.getAssignedAddresses();
        Log.i(TAG, "address length: " + assignedAddresses.length);
        Log.i(TRACE, "Service.updateTunnelConfig assignedAddresses=" + assignedAddresses.length);
        boolean isRouteViaZeroTier = networkConfig.getRouteViaZeroTier();
        Log.i(TRACE, "Service.updateTunnelConfig routingFlags routeViaZeroTier=" + isRouteViaZeroTier
                + ", disableIPv6=" + this.disableIPv6);
        int addedAddressCount = 0;
        int addedDirectRouteCount = 0;
        int addedManagedRouteCount = 0;

        // 遍历 ZT 网络中当前设备的 IP 地址，组播配置
        for (var vpnAddress : assignedAddresses) {
            Log.d(TAG, "Adding VPN Address: " + vpnAddress.getAddress()
                    + " Mac: " + com.zerotier.sdk.util.StringUtils.macAddressToString(virtualNetworkConfig.getMac()));
            byte[] rawAddress = vpnAddress.getAddress().getAddress();

            if (!this.disableIPv6 || !(vpnAddress.getAddress() instanceof Inet6Address)) {
                var address = vpnAddress.getAddress();
                var port = vpnAddress.getPort();
                var route = InetAddressUtils.addressToRoute(address, port);
                if (route == null) {
                    Log.e(TAG, "NULL route calculated!");
                    continue;
                }

                // 计算 VPN 地址相关的组播 MAC 与 ADI
                long multicastGroup;
                long multicastAdi;
                if (rawAddress.length == 4) {
                    // IPv4
                    multicastGroup = InetAddressUtils.BROADCAST_MAC_ADDRESS;
                    multicastAdi = ByteBuffer.wrap(rawAddress).getInt();
                } else {
                    // IPv6
                    multicastGroup = ByteBuffer.wrap(new byte[]{
                                    0, 0, 0x33, 0x33, (byte) 0xFF, rawAddress[13], rawAddress[14], rawAddress[15]})
                            .getLong();
                    multicastAdi = 0;
                }

                // 订阅组播并添加至 TUN TAP 路由
                var result = this.node.multicastSubscribe(networkId, multicastGroup, multicastAdi);
                if (result != ResultCode.RESULT_OK) {
                    Log.e(TAG, "Error joining multicast group");
                } else {
                    Log.d(TAG, "Joined multicast group");
                }
                builder.addAddress(address, port);
                builder.addRoute(route, port);
                this.tunTapAdapter.addRouteAndNetwork(new Route(route, port), networkId);
                addedAddressCount++;
                addedDirectRouteCount++;
                Log.i(TRACE, "Service.updateTunnelConfig addAssignedRoute addr="
                        + address.getHostAddress() + "/" + port
                        + ", route=" + route.getHostAddress() + "/" + port);
            }
        }

        // 遍历网络的路由规则，将网络负责路由的地址路由至 VPN
        try {
            var v4Loopback = InetAddress.getByName("0.0.0.0");
            var v6Loopback = InetAddress.getByName("::");
            if (virtualNetworkConfig.getRoutes().length > 0) {
                for (var routeConfig : virtualNetworkConfig.getRoutes()) {
                    var target = routeConfig.getTarget();
                    var via = routeConfig.getVia();
                    var targetAddress = target.getAddress();
                    var targetPort = target.getPort();
                    var viaAddress = InetAddressUtils.addressToRoute(targetAddress, targetPort);
                    boolean isIPv6Route = (targetAddress instanceof Inet6Address) || (viaAddress instanceof Inet6Address);
                    boolean isDisabledV6Route = this.disableIPv6 && isIPv6Route;
                    boolean shouldRouteToZerotier = viaAddress != null && (
                            isRouteViaZeroTier
                                    || (!viaAddress.equals(v4Loopback) && !viaAddress.equals(v6Loopback))
                    );
                    String targetText = targetAddress == null ? "null" : targetAddress.getHostAddress();
                    String viaText = viaAddress == null ? "null" : viaAddress.getHostAddress();
                    String gatewayText = (via == null || via.getAddress() == null) ? "null" : via.getAddress().getHostAddress();
                    Log.i(TRACE, "Service.updateTunnelConfig routeDecision target=" + targetText + "/" + targetPort
                            + ", via=" + viaText
                            + ", gateway=" + gatewayText
                            + ", isIPv6=" + isIPv6Route
                            + ", disableIPv6=" + this.disableIPv6
                            + ", routeViaZeroTier=" + isRouteViaZeroTier
                            + ", shouldRouteToZerotier=" + shouldRouteToZerotier);
                    if (!isDisabledV6Route && shouldRouteToZerotier) {
                        builder.addRoute(viaAddress, targetPort);
                        Route route = new Route(viaAddress, targetPort);
                        if (via != null) {
                            route.setGateway(via.getAddress());
                        }
                        this.tunTapAdapter.addRouteAndNetwork(route, networkId);
                        addedManagedRouteCount++;
                        Log.i(TRACE, "Service.updateTunnelConfig addManagedRoute via="
                                + viaAddress.getHostAddress() + "/" + targetPort
                                + ", gateway=" + gatewayText);
                    }
                }
            }
            builder.addRoute(InetAddress.getByName("224.0.0.0"), 4);
            Log.i(TRACE, "Service.updateTunnelConfig addMulticastRoute route=224.0.0.0/4");
        } catch (Exception e) {
            this.eventBus.post(new VPNErrorEvent(e.getLocalizedMessage()));
            return false;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false);
        }
        addDNSServers(builder, network);
        Log.i(TRACE, "Service.updateTunnelConfig routeSummary addresses=" + addedAddressCount
                + ", directRoutes=" + addedDirectRouteCount
                + ", managedRoutes=" + addedManagedRouteCount
                + ", routeViaZeroTier=" + isRouteViaZeroTier);

        // 配置 MTU
        int mtu = virtualNetworkConfig.getMtu();
        Log.i(TAG, "MTU from Network Config: " + mtu);
        if (mtu == 0) {
            mtu = 2800;
        }
        Log.i(TAG, "MTU Set: " + mtu);
        builder.setMtu(mtu);

        builder.setSession(Constants.VPN_SESSION_NAME);

        // 设置部分 APP 不经过 VPN
        if (!isRouteViaZeroTier) {
            for (var app : DISALLOWED_APPS) {
                try {
                    builder.addDisallowedApplication(app);
                } catch (Exception e3) {
                    Log.e(TAG, "Cannot disallow app", e3);
                }
            }
        }

        // 建立 VPN 连接
        this.vpnSocket = builder.establish();
        Log.i(TRACE, "Service.updateTunnelConfig establish vpnSocketNull=" + (this.vpnSocket == null));
        if (this.vpnSocket == null) {
            this.eventBus.post(new VPNErrorEvent(getString(R.string.toast_vpn_application_not_prepared)));
            return false;
        }
        this.in = new FileInputStream(this.vpnSocket.getFileDescriptor());
        this.out = new FileOutputStream(this.vpnSocket.getFileDescriptor());
        this.tunTapAdapter.setVpnSocket(this.vpnSocket);
        this.tunTapAdapter.setFileStreams(this.in, this.out);
        this.tunTapAdapter.startThreads();
        String connectedNetworkName = network.getNetworkName() == null ? "" : network.getNetworkName();
        String connectedNetworkIdText = network.getNetworkIdStr();
        if (connectedNetworkIdText == null || connectedNetworkIdText.isEmpty()) {
            connectedNetworkIdText = com.zerotier.sdk.util.StringUtils.networkIdToString(network.getNetworkId());
        }
        this.notificationController.bindConnectedNetwork(connectedNetworkName, connectedNetworkIdText);

        // 状态栏提示
        ensureNotificationReady();
        startForeground(ZT_NOTIFICATION_TAG, buildConnectedNotification());
        Log.i(TAG, "ZeroTier One Connected");
        Log.i(TRACE, "Service.updateTunnelConfig connected networkId="
                + com.zerotier.sdk.util.StringUtils.networkIdToString(networkId)
                + ", networkName=" + connectedNetworkName);

        // 旧版本 Android 多播处理
        if (Build.VERSION.SDK_INT < 29) {
            if (this.v4MulticastScanner != null && !this.v4MulticastScanner.isAlive()) {
                this.v4MulticastScanner.start();
            }
            if (!this.disableIPv6 && this.v6MulticastScanner != null && !this.v6MulticastScanner.isAlive()) {
                this.v6MulticastScanner.start();
            }
        }
        return true;
    }

    private void ensureNotificationReady() {
        this.notificationController.ensureReady();
    }

    private android.app.Notification buildConnectedNotification() {
        return this.notificationController.buildConnectedNotification();
    }

    private void addDNSServers(VpnService.Builder builder, Network network) {
        var networkConfig = network.getNetworkConfig();
        var virtualNetworkConfig = getVirtualNetworkConfig(network.getNetworkId());
        var dnsMode = DNSMode.fromInt(networkConfig.getDnsMode());
        int addedDnsCount = 0;
        Log.i(TRACE, "Service.addDNSServers mode=" + dnsMode + ", disableIPv6=" + this.disableIPv6);

        switch (dnsMode) {
            case NETWORK_DNS:
                if (virtualNetworkConfig.getDns() == null) {
                    Log.i(TRACE, "Service.addDNSServers skip: virtual dns config is null");
                    return;
                }
                builder.addSearchDomain(virtualNetworkConfig.getDns().getDomain());
                Log.i(TRACE, "Service.addDNSServers searchDomain=" + virtualNetworkConfig.getDns().getDomain());
                for (var inetSocketAddress : virtualNetworkConfig.getDns().getServers()) {
                    InetAddress address = inetSocketAddress.getAddress();
                    if (address instanceof Inet4Address) {
                        builder.addDnsServer(address);
                        addedDnsCount++;
                        Log.i(TRACE, "Service.addDNSServers add=" + address.getHostAddress());
                    } else if ((address instanceof Inet6Address) && !this.disableIPv6) {
                        builder.addDnsServer(address);
                        addedDnsCount++;
                        Log.i(TRACE, "Service.addDNSServers add=" + address.getHostAddress());
                    }
                }
                break;
            case CUSTOM_DNS:
                for (var dnsServer : networkConfig.getDnsServers()) {
                    try {
                        InetAddress byName = InetAddress.getByName(dnsServer.getNameserver());
                        if (byName instanceof Inet4Address) {
                            builder.addDnsServer(byName);
                            addedDnsCount++;
                            Log.i(TRACE, "Service.addDNSServers addCustom=" + byName.getHostAddress());
                        } else if ((byName instanceof Inet6Address) && !this.disableIPv6) {
                            builder.addDnsServer(byName);
                            addedDnsCount++;
                            Log.i(TRACE, "Service.addDNSServers addCustom=" + byName.getHostAddress());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception parsing DNS server: " + e, e);
                    }
                }
                break;
            default:
                Log.i(TRACE, "Service.addDNSServers skip: dns mode default/disabled");
                break;
        }
        Log.i(TRACE, "Service.addDNSServers summary mode=" + dnsMode + ", count=" + addedDnsCount);
    }

    /**
     * 入轨事件
     */
    @Subscribe
    public void onOrbitMoonEvent(OrbitMoonEvent event) {
        if (this.node == null) {
            Log.e(TAG, "Can't orbit network if ZeroTier isn't running");
            return;
        }
        // 入轨
        for (MoonOrbit moonOrbit : event.getMoonOrbits()) {
            Log.i(TAG, "Orbiting moon: " + Long.toHexString(moonOrbit.getMoonWorldId()));
            this.orbitNetwork(moonOrbit.getMoonWorldId(), moonOrbit.getMoonSeed());
        }
    }

    /**
     * 当前网络入轨 Moon
     *
     * @param moonWorldId Moon 节点地址
     * @param moonSeed    Moon 种子节点地址
     */
    public void orbitNetwork(Long moonWorldId, Long moonSeed) {
        if (this.node == null) {
            Log.e(TAG, "Can't orbit network if ZeroTier isn't running");
            return;
        }
        // 入轨
        ResultCode result = this.node.orbit(moonWorldId, moonSeed);
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
        this.networkChangeObserver.start(this::triggerAutoRoutePolicyCheck);
    }

    /**
     * 注销网络变化监听器。
     */
    private synchronized void unregisterNetworkChangeMonitor() {
        if (this.networkChangeObserver != null) {
            this.networkChangeObserver.stop();
        }
        synchronized (this.autoRoutePolicyLock) {
            this.autoRouteCheckRunning = false;
        }
    }

    /**
     * 触发一次自动路由策略检测。
     * <p>
     * 策略行为：
     * 1) 判定在内网：进入监听态（服务保活，不启用 ZeroTier 转发）；
     * 2) 判定不在内网：若当前为监听态则恢复 ZeroTier 转发。
     *
     * @param reason 触发原因（日志用途）
     */
    private void triggerAutoRoutePolicyCheck(String reason) {
        Log.i(TRACE, "Service.triggerAutoRoutePolicyCheck reason=" + reason);
        synchronized (this.autoRoutePolicyLock) {
            // 防并发：同一时刻只允许一个探测线程执行
            if (this.autoRouteCheckRunning) {
                Log.i(TRACE, "Service.triggerAutoRoutePolicyCheck skip: running=true");
                return;
            }
            this.autoRouteCheckRunning = true;
        }
        Thread checker = new Thread(() -> {
            try {
                RoutePolicyEngine.Decision decision = RoutePolicyEngine.evaluate(this, reason);
                boolean policyEnabled = !("auto_route_check_disabled".equals(decision.getDetail()));
                Log.i(TRACE, "Service.triggerAutoRoutePolicyCheck result enabled=" + policyEnabled
                        + ", action=" + decision.getAction()
                        + ", inIntranet=" + decision.getInIntranet() + ", detail=" + decision.getDetail());
                this.eventBus.post(new IntranetCheckStateEvent(
                        policyEnabled,
                        decision.getInIntranet(),
                        reason,
                        decision.getDetail()
                ));
                if (!policyEnabled || decision.getInIntranet() == null) {
                    this.monitorOnlyMode = false;
                    return;
                }
                if (decision.getAction() == RoutePolicyEngine.PolicyAction.ENTER_MONITOR_ONLY) {
                    long now = SystemClock.elapsedRealtime();
                    if (now < this.manualConnectProtectionDeadlineMs) {
                        Log.i(TRACE, "Service.triggerAutoRoutePolicyCheck skip ENTER_MONITOR_ONLY by manual protection"
                                + " now=" + now + ", deadline=" + this.manualConnectProtectionDeadlineMs);
                        return;
                    }
                    // 同内网：降级到监听态（不退出服务）
                    enterMonitorOnlyMode(reason, decision.getDetail());
                } else if (this.monitorOnlyMode) {
                    // 非内网且当前在监听态：恢复 ZeroTier 转发并重新 join
                    long targetNetworkId = this.networkId;
                    if (targetNetworkId != 0L) {
                        Log.i(TRACE, "Service.triggerAutoRoutePolicyCheck resume from monitor-only reason=" + reason);
                        startOrResumeZeroTierAndJoin(targetNetworkId, reason);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Auto route policy check failed. reason=" + reason, e);
                Log.e(TRACE, "Service.triggerAutoRoutePolicyCheck exception: " + e.getClass().getSimpleName());
                this.eventBus.post(new IntranetCheckStateEvent(true, null, reason, "check_exception"));
            } finally {
                synchronized (this.autoRoutePolicyLock) {
                    this.autoRouteCheckRunning = false;
                }
                Log.i(TRACE, "Service.triggerAutoRoutePolicyCheck finish reason=" + reason);
            }
        }, "AutoRoutePolicyCheck");
        checker.start();
    }
}
