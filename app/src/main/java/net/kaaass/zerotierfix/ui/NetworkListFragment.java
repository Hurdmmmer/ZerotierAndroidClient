package net.kaaass.zerotierfix.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.zerotier.sdk.Node;
import com.zerotier.sdk.NodeStatus;
import com.zerotier.sdk.Peer;
import com.zerotier.sdk.PeerPhysicalPath;
import com.zerotier.sdk.Version;

import net.kaaass.zerotierfix.BuildConfig;
import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.ZerotierFixApplication;
import net.kaaass.zerotierfix.events.AfterJoinNetworkEvent;
import net.kaaass.zerotierfix.events.IntranetCheckStateEvent;
import net.kaaass.zerotierfix.events.IsServiceRunningReplyEvent;
import net.kaaass.zerotierfix.events.IsServiceRunningRequestEvent;
import net.kaaass.zerotierfix.events.NetworkListCheckedChangeEvent;
import net.kaaass.zerotierfix.events.NetworkListReplyEvent;
import net.kaaass.zerotierfix.events.NetworkListRequestEvent;
import net.kaaass.zerotierfix.events.NodeDestroyedEvent;
import net.kaaass.zerotierfix.events.NodeStatusEvent;
import net.kaaass.zerotierfix.events.NodeStatusRequestEvent;
import net.kaaass.zerotierfix.events.OrbitMoonEvent;
import net.kaaass.zerotierfix.events.PeerInfoReplyEvent;
import net.kaaass.zerotierfix.events.PeerInfoRequestEvent;
import net.kaaass.zerotierfix.events.StopEvent;
import net.kaaass.zerotierfix.events.VPNErrorEvent;
import net.kaaass.zerotierfix.events.VirtualNetworkConfigChangedEvent;
import net.kaaass.zerotierfix.model.AssignedAddress;
import net.kaaass.zerotierfix.model.AssignedAddressDao;
import net.kaaass.zerotierfix.model.DaoSession;
import net.kaaass.zerotierfix.model.MoonOrbit;
import net.kaaass.zerotierfix.model.Network;
import net.kaaass.zerotierfix.model.NetworkConfig;
import net.kaaass.zerotierfix.model.NetworkConfigDao;
import net.kaaass.zerotierfix.model.NetworkDao;
import net.kaaass.zerotierfix.model.type.NetworkStatus;
import net.kaaass.zerotierfix.service.ZeroTierOneService;
import net.kaaass.zerotierfix.ui.view.NetworkDetailActivity;
import net.kaaass.zerotierfix.ui.viewmodel.NetworkListModel;
import net.kaaass.zerotierfix.util.AutoConnectPolicy;
import net.kaaass.zerotierfix.util.Constants;
import net.kaaass.zerotierfix.util.DatabaseUtils;
import net.kaaass.zerotierfix.util.StringUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import lombok.ToString;

// TODO: clear up
public class NetworkListFragment extends Fragment {
    public static final String NETWORK_ID_MESSAGE = "com.zerotier.one.network-id";
    public static final String TAG = "NetworkListFragment";
    private static final String TRACE = "CONNECT_TRACE";
    private final EventBus eventBus;
    private final List<Network> mNetworks = new ArrayList<>();
    boolean mIsBound = false;
    private RecyclerViewAdapter recyclerViewAdapter;
    private RecyclerView recyclerView;
    private ZeroTierOneService mBoundService;
    private final ServiceConnection mConnection = new ServiceConnection() {
        /* class com.zerotier.one.ui.NetworkListFragment.AnonymousClass1 */

        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            NetworkListFragment.this.mBoundService = ((ZeroTierOneService.ZeroTierBinder) iBinder).getService();
        }

        public void onServiceDisconnected(ComponentName componentName) {
            NetworkListFragment.this.mBoundService = null;
            NetworkListFragment.this.setIsBound(false);
        }
    };
    private TextView nodeStatusView;
    private TextView nodeClientVersionView;
    private MaterialToolbar topAppBar;
    private TextView topConnectionSummary;
    private TextView topIntranetSummary;
    private String p2pSummary = "";
    private String intranetSummary = "";
    /**
     * 当前内网判定（true=内网，false=非内网，null=未知/未更新）。
     */
    private Boolean intranetDetected = null;
    /**
     * true 表示当前处于“监听态”（ZeroTier 运行时已停，但服务保持监听网络变化）。
     */
    private boolean monitorOnlyModeActive = false;
    private static final long SWITCH_INTERACTION_LOCK_MS = 2500L;
    private volatile long switchInteractionLockedUntilMs = 0L;

    private View emptyView = null;
    final private RecyclerView.AdapterDataObserver checkIfEmptyObserver = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            checkIfEmpty();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            checkIfEmpty();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            checkIfEmpty();
        }

        /**
         * 检查列表是否为空
         */
        void checkIfEmpty() {
            if (emptyView != null && recyclerViewAdapter != null) {
                final boolean emptyViewVisible = recyclerViewAdapter.getItemCount() == 0;
                emptyView.setVisibility(emptyViewVisible ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(emptyViewVisible ? View.GONE : View.VISIBLE);
            }
        }
    };
    private ActivityResultLauncher<Intent> vpnAuthLauncher;
    private NetworkListModel viewModel;

    public NetworkListFragment() {
        Log.d(TAG, "Network List Fragment created");
        this.eventBus = EventBus.getDefault();
    }

    /* access modifiers changed from: package-private */
    public synchronized void setIsBound(boolean z) {
        this.mIsBound = z;
    }

    /* access modifiers changed from: package-private */
    public synchronized boolean isBound() {
        return this.mIsBound;
    }

    /* access modifiers changed from: package-private */
    public void doBindService() {
        if (!isBound()) {
            if (requireActivity().bindService(new Intent(getActivity(), ZeroTierOneService.class), this.mConnection, Context.BIND_NOT_FOREGROUND | Context.BIND_DEBUG_UNBIND)) {
                setIsBound(true);
            }
        }
    }

    /* access modifiers changed from: package-private */
    public void doUnbindService() {
        if (isBound()) {
            try {
                requireActivity().unbindService(this.mConnection);
            } catch (Exception e) {
                Log.e(TAG, "", e);
            } catch (Throwable th) {
                setIsBound(false);
                throw th;
            }
            setIsBound(false);
        }
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // 初始化 VPN 授权结果回调
        vpnAuthLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), (activityResult) -> {
            var result = activityResult.getResultCode();
            Log.d(TAG, "Returned from AUTH_VPN");
            if (result == -1) {
                // 得到授权，连接网络
                startService(this.viewModel.getNetworkId());
            } else if (result == 0) {
                // 未授权
                updateNetworkListAndNotify();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        this.eventBus.post(new NetworkListRequestEvent());

        // 初始化节点及服务状态
        this.eventBus.post(new NodeStatusRequestEvent());
        this.eventBus.post(new IsServiceRunningRequestEvent());
        this.eventBus.post(new PeerInfoRequestEvent());
        requestIntranetProbeForUi("ui_start");

        // 检查通知权限
        var notificationManager = NotificationManagerCompat.from(requireContext());
        if (!notificationManager.areNotificationsEnabled()) {
            // 无通知权限
            showNoNotificationAlertDialog();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        doUnbindService();
    }

    @Override
    public void onPause() {
        super.onPause();
        this.eventBus.unregister(this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_network_list, container, false);
        this.topAppBar = view.findViewById(R.id.top_app_bar);
        this.topConnectionSummary = view.findViewById(R.id.top_connection_summary);
        this.topIntranetSummary = view.findViewById(R.id.top_intranet_summary);
        initTopAppBar();

        // 空列表提示
        this.emptyView = view.findViewById(R.id.no_data);

        // 列表、适配器设置
        this.recyclerView = view.findViewById(R.id.joined_networks_list);
        this.recyclerView.setClickable(true);
        this.recyclerView.setLongClickable(true);
        this.recyclerViewAdapter = new RecyclerViewAdapter(this.mNetworks);
        this.recyclerViewAdapter.registerAdapterDataObserver(checkIfEmptyObserver);
        this.recyclerView.setAdapter(this.recyclerViewAdapter);

        // 网络状态栏设置
        this.nodeStatusView = view.findViewById(R.id.node_status);
        this.nodeClientVersionView = view.findViewById(R.id.client_version);
        this.nodeClientVersionView.setText(resolveCoreVersionDisplayText());
        TextView appVersionView = view.findViewById(R.id.app_version);
        appVersionView.setText(getString(R.string.bottom_app_prefix, BuildConfig.VERSION_NAME));

        // 加载网络数据
        updateNetworkListAndNotify();

        // 处理系统栏安全区，避免顶部标题栏和底部状态栏与系统栏重叠
        applyWindowInsets(view);

        // 当前连接网络变更时更新列表
        this.viewModel.getConnectNetworkId().observe(getViewLifecycleOwner(), networkId -> {
            this.recyclerViewAdapter.notifyDataSetChanged();
            refreshTopAppBarSubtitle();
        });

        return view;
    }

    /**
     * 处理状态栏与导航栏的安全区间距。
     * 顶部给 AppBar 增加状态栏高度，底部给列表与底栏增加导航栏高度。
     */
    private void applyWindowInsets(View root) {
        View appBar = root.findViewById(R.id.app_bar);
        View statusBar = root.findViewById(R.id.status_bar);
        View noData = root.findViewById(R.id.no_data);
        if (appBar == null || statusBar == null || this.recyclerView == null || noData == null) {
            return;
        }

        final int appBarPaddingTop = appBar.getPaddingTop();
        final int statusBarPaddingBottom = statusBar.getPaddingBottom();
        final int recyclerPaddingBottom = this.recyclerView.getPaddingBottom();
        final int noDataPaddingBottom = noData.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            // 顶部同时考虑状态栏和挖孔区域，兼容刘海/打孔屏机型
            Insets statusInsets = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.displayCutout()
            );
            Insets navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars());

            // 顶部区域下沉，避开状态栏（时间、电量、信号）
            appBar.setPadding(
                    appBar.getPaddingLeft(),
                    appBarPaddingTop + statusInsets.top,
                    appBar.getPaddingRight(),
                    appBar.getPaddingBottom()
            );

            // 底部状态栏贴底显示，仅给列表和空态补齐手势条安全区
            statusBar.setPadding(
                    statusBar.getPaddingLeft(),
                    statusBar.getPaddingTop(),
                    statusBar.getPaddingRight(),
                    statusBarPaddingBottom
            );
            this.recyclerView.setPadding(
                    this.recyclerView.getPaddingLeft(),
                    this.recyclerView.getPaddingTop(),
                    this.recyclerView.getPaddingRight(),
                    recyclerPaddingBottom + navInsets.bottom
            );
            noData.setPadding(
                    noData.getPaddingLeft(),
                    noData.getPaddingTop(),
                    noData.getPaddingRight(),
                    noDataPaddingBottom + navInsets.bottom
            );
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /**
     * 发送连接至指定网络的 Intent。将请求 VPN 权限后启动 ZT 服务
     *
     * @param networkId 网络号
     */
    private void sendStartServiceIntent(long networkId) {
        var prepare = VpnService.prepare(requireActivity());
        Log.i(TAG, TRACE + " sendStartServiceIntent networkId=" + Long.toHexString(networkId)
                + " needVpnAuth=" + (prepare != null));
        if (prepare != null) {
            // 等待 VPN 授权后连接网络
            this.viewModel.setNetworkId(networkId);
            vpnAuthLauncher.launch(prepare);
            return;
        }
        Log.d(TAG, "Intent is NULL.  Already approved.");
        startService(networkId);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "NetworkListFragment.onCreate");
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(getActivity(), R.xml.preferences, false);
        this.p2pSummary = getString(R.string.network_p2p_offline);
        this.intranetSummary = getString(R.string.network_intranet_no);

        // 获取 ViewModel
        this.viewModel = new ViewModelProvider(requireActivity()).get(NetworkListModel.class);
    }

    @Override
    public void onResume() {
        super.onResume();
        this.nodeStatusView.setText(R.string.bottom_node_placeholder);
        if (this.nodeClientVersionView != null) {
            this.nodeClientVersionView.setText(resolveCoreVersionDisplayText());
        }
        this.eventBus.register(this);
        this.eventBus.post(new IsServiceRunningRequestEvent());
        updateNetworkListAndNotify();
        this.eventBus.post(new NetworkListRequestEvent());
        this.eventBus.post(new NodeStatusRequestEvent());
        this.eventBus.post(new PeerInfoRequestEvent());
    }

    private boolean onTopAppBarMenuItemSelected(MenuItem menuItem) {
        int menuId = menuItem.getItemId();
        if (menuId == R.id.menu_item_add_network) {
            Log.d(TAG, "Selected Join Network");
            startActivity(new Intent(getActivity(), JoinNetworkActivity.class));
            return true;
        } else if (menuId == R.id.menu_item_settings) {
            Log.d(TAG, "Selected Settings");
            startActivity(new Intent(getActivity(), PrefsActivity.class));
            return true;
        } else if (menuId == R.id.menu_item_peers) {
            Log.d(TAG, "Selected peers");
            startActivity(new Intent(getActivity(), PeerListActivity.class));
            return true;
        } else if (menuId == R.id.menu_item_orbit) {
            Log.d(TAG, "Selected orbit");
            startActivity(new Intent(getActivity(), MoonOrbitActivity.class));
            return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        doUnbindService();
    }

    private List<Network> getNetworkList() {
        DaoSession daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
        daoSession.clear();
        return daoSession.getNetworkDao().queryBuilder().orderAsc(NetworkDao.Properties.NetworkId).build().forCurrentThread().list();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onIsServiceRunningReply(IsServiceRunningReplyEvent event) {
        if (event.isRunning()) {
            doBindService();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNetworkListReply(NetworkListReplyEvent event) {
        Log.d(TAG, "Got connecting network list");
        // 更新当前连接的网络
        var networks = event.getNetworkList();
        StringBuilder ids = new StringBuilder();
        if (networks != null) {
            for (var network : networks) {
                if (ids.length() > 0) {
                    ids.append(",");
                }
                ids.append(Long.toHexString(network.getNwid()));
            }
        }
        Log.i(TAG, TRACE + " onNetworkListReply count=" + (networks == null ? 0 : networks.length)
                + " nwids=" + ids);
        if (networks == null || networks.length == 0) {
            // 监听态下网络列表为空属于预期，不应清空用户的连接意图（开关保持开启）
            if (!this.monitorOnlyModeActive) {
                this.viewModel.doChangeConnectNetwork(null);
            }
        } else {
            // 当前服务只连接一个网络，取第一项作为当前连接网络即可。
            this.viewModel.doChangeConnectNetwork(networks[0].getNwid());
            this.monitorOnlyModeActive = false;
        }
        // 更新网络列表
        updateNetworkListAndNotify();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onVirtualNetworkConfigChanged(VirtualNetworkConfigChangedEvent event) {
        Log.d(TAG, "Got Network Info");
        var config = event.getVirtualNetworkConfig();
        Log.i(TAG, TRACE + " onVirtualNetworkConfigChanged nwid=" + Long.toHexString(config.getNwid())
                + " status=" + config.getStatus() + " name=" + config.getName());

        // Toast 提示网络状态
        var status = NetworkStatus.fromVirtualNetworkStatus(config.getStatus());
        var networkId = com.zerotier.sdk.util.StringUtils.networkIdToString(config.getNwid());
        String message = null;
        switch (status) {
            case OK:
                message = getString(R.string.toast_network_status_ok, networkId);
                break;
            case ACCESS_DENIED:
                message = getString(R.string.toast_network_status_access_denied, networkId);
                break;
            case NOT_FOUND:
                message = getString(R.string.toast_network_status_not_found, networkId);
                break;
            case PORT_ERROR:
                message = getString(R.string.toast_network_status_port_error, networkId);
                break;
            case CLIENT_TOO_OLD:
                message = getString(R.string.toast_network_status_client_too_old, networkId);
                break;
            case AUTHENTICATION_REQUIRED:
                message = getString(R.string.toast_network_status_authentication_required, networkId);
                break;
            case REQUESTING_CONFIGURATION:
            default:
                break;
        }
        if (message != null) {
            Toast.makeText(requireActivity(), message, Toast.LENGTH_SHORT).show();
        }

        // 更新网络列表
        updateNetworkListAndNotify();
    }

    /**
     * 节点状态事件回调
     *
     * @param event 事件
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNodeStatus(NodeStatusEvent event) {
        NodeStatus status = event.getStatus();
        Version clientVersion = event.getClientVersion();
        Log.i(TAG, TRACE + " onNodeStatus online=" + status.isOnline()
                + " address=" + Long.toHexString(status.getAddress()));
        if (status.isOnline()) {
            this.monitorOnlyModeActive = false;
            unlockSwitchInteraction();
        }
        this.nodeStatusView.setText(getString(R.string.bottom_node_prefix, Long.toHexString(status.getAddress())));
        // 更新客户端版本
        if (this.nodeClientVersionView != null && clientVersion != null) {
            String coreVersion = StringUtils.toString(clientVersion);
            this.nodeClientVersionView.setText(getString(R.string.bottom_core_prefix, coreVersion));
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putString(Constants.PREF_UI_LAST_CORE_VERSION, coreVersion)
                    .apply();
        }
        refreshTopAppBarSubtitle();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNodeDestroyed(NodeDestroyedEvent event) {
        Log.i(TAG, TRACE + " onNodeDestroyed keepServiceAlive=" + event.isKeepServiceAlive());
        this.monitorOnlyModeActive = event.isKeepServiceAlive();
        // 仅在“完整停服/用户主动关闭”时清空连接状态；
        // 监听态下保留用户连接意图（开关保持开启）。
        if (!event.isKeepServiceAlive()) {
            this.viewModel.doChangeConnectNetwork(null);
        }
        unlockSwitchInteraction();
        setOfflineState();
        if (this.recyclerViewAdapter != null) {
            this.recyclerViewAdapter.notifyDataSetChanged();
        }
    }

    /**
     * 收到 Peer 信息后，按“是否存在 P2P 直连”更新状态摘要。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPeerInfoReplyEvent(PeerInfoReplyEvent event) {
        this.p2pSummary = buildP2PSummary(event.getPeers());
        var peers = event.getPeers();
        Log.i(TAG, TRACE + " onPeerInfoReply count=" + (peers == null ? 0 : peers.length)
                + " p2pSummary=" + this.p2pSummary);
        if (this.recyclerViewAdapter != null) {
            this.recyclerViewAdapter.notifyDataSetChanged();
        }
        refreshTopAppBarSubtitle();
    }

    /**
     * 接收内网判定事件并刷新顶部摘要。
     *
     * @param event 内网判定事件
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onIntranetCheckStateEvent(IntranetCheckStateEvent event) {
        // “未知”统一视为“否”；是否展示由 refreshTopAppBarSubtitle 中的配置判定控制。
        this.intranetDetected = event.getInIntranet();
        if (event.isEnabled() && Boolean.TRUE.equals(event.getInIntranet())) {
            this.monitorOnlyModeActive = true;
        }
        if (Boolean.TRUE.equals(event.getInIntranet())) {
            this.intranetSummary = getString(R.string.network_intranet_yes);
        } else {
            this.intranetSummary = getString(R.string.network_intranet_no);
        }
        updateNetworkListAndNotify();
    }

    /**
     * 页面启动时主动执行一次内网探测，确保“未启动服务”场景也能显示当前内网状态。
     *
     * @param reason 探测触发原因
     */
    private void requestIntranetProbeForUi(String reason) {
        Context appContext = requireContext().getApplicationContext();
        Thread checker = new Thread(() -> {
            boolean enabled = AutoConnectPolicy.isAutoRouteCheckEnabled(appContext);
            AutoConnectPolicy.ProbeResult result = AutoConnectPolicy.detectIntranetState(appContext);
            this.eventBus.post(new IntranetCheckStateEvent(enabled, result.getInIntranet(), reason, result.getDetail()));
        }, "UIIntranetProbe");
        checker.start();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onVPNError(VPNErrorEvent event) {
        var errorMessage = event.getMessage();
        Log.e(TAG, TRACE + " onVPNError message=" + errorMessage);
        var message = getString(R.string.toast_vpn_error, errorMessage);
        Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        // VPN 建链失败时回退连接状态，保证 UI 与实际状态一致
        this.viewModel.doChangeConnectNetwork(null);
        unlockSwitchInteraction();
        updateNetworkListAndNotify();
    }

    private void setOfflineState() {
        TextView textView = this.nodeStatusView;
        if (textView != null) {
            textView.setText(R.string.bottom_node_placeholder);
        }
        this.p2pSummary = getString(R.string.network_p2p_offline);
        refreshTopAppBarSubtitle();
    }

    /**
     * 根据网络状态映射状态色。
     *
     * @param status 内核网络状态
     * @return 对应颜色值
     */
    private int resolveStatusColor(NetworkStatus status) {
        if (status == null) {
            return ContextCompat.getColor(requireContext(), R.color.status_neutral);
        }
        switch (status) {
            case OK:
                return ContextCompat.getColor(requireContext(), R.color.status_ok);
            case REQUESTING_CONFIGURATION:
                return ContextCompat.getColor(requireContext(), R.color.status_progress);
            case ACCESS_DENIED:
            case NOT_FOUND:
            case PORT_ERROR:
            case CLIENT_TOO_OLD:
            case AUTHENTICATION_REQUIRED:
                return ContextCompat.getColor(requireContext(), R.color.status_error);
            default:
                return ContextCompat.getColor(requireContext(), R.color.status_neutral);
        }
    }

    /**
     * 规范字段标签文本，避免出现双冒号。
     *
     * @param rawLabel 原始资源标签
     * @return 去尾部冒号后的标签
     */
    private String normalizeFieldLabel(String rawLabel) {
        if (rawLabel == null) {
            return "";
        }
        String label = rawLabel.trim();
        while (label.endsWith(":") || label.endsWith("：")) {
            label = label.substring(0, label.length() - 1).trim();
        }
        return label;
    }

    /**
     * 解析 Core 版本显示文案：
     * 1) 优先直接读取 JNI Core 版本（不依赖连接状态）；
     * 2) 失败时回退到缓存版本；
     * 3) 仍无结果时展示占位文案。
     */
    private String resolveCoreVersionDisplayText() {
        String coreVersion = "";
        try {
            Version version = new Node(System.currentTimeMillis()).getVersion();
            if (version != null) {
                coreVersion = StringUtils.toString(version);
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit()
                        .putString(Constants.PREF_UI_LAST_CORE_VERSION, coreVersion)
                        .apply();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Read core version from JNI failed", t);
        }
        if (coreVersion == null || coreVersion.isEmpty()) {
            coreVersion = PreferenceManager
                    .getDefaultSharedPreferences(requireContext())
                    .getString(Constants.PREF_UI_LAST_CORE_VERSION, "");
        }
        if (coreVersion == null || coreVersion.isEmpty()) {
            return getString(R.string.bottom_core_placeholder);
        }
        return getString(R.string.bottom_core_prefix, coreVersion);
    }

    /**
     * 根据 Peer 列表判断 P2P 是否启用。
     * 规则：只要存在一个可用首选路径（preferred path 且 address 非空），即判定为“已启用”。
     *
     * @param peers 当前节点可见的 Peer 列表
     * @return P2P 状态文案（已启用/未启用）
     */
    private String buildP2PSummary(Peer[] peers) {
        if (peers == null || peers.length == 0) {
            return getString(R.string.network_p2p_offline);
        }
        for (Peer peer : peers) {
            if (peer == null) {
                continue;
            }
            PeerPhysicalPath preferred = null;
            PeerPhysicalPath[] paths = peer.getPaths();
            if (paths != null) {
                for (PeerPhysicalPath path : paths) {
                    if (path != null && path.isPreferred()) {
                        preferred = path;
                        break;
                    }
                }
            }
            if (preferred != null && preferred.getAddress() != null) {
                return getString(R.string.network_p2p_waiting);
            }
        }
        return getString(R.string.network_p2p_offline);
    }

    /**
     * 更新网络列表
     */
    private void updateNetworkList() {
        List<Network> networkList = getNetworkList();
        if (networkList != null) {
            // 更新列表
            this.mNetworks.clear();
            this.mNetworks.addAll(networkList);
        }
    }

    /**
     * 更新网络列表与 UI
     */
    public void updateNetworkListAndNotify() {
        // 更新数据
        updateNetworkList();
        // 更新列表
        if (this.recyclerViewAdapter != null) {
            this.recyclerViewAdapter.notifyDataSetChanged();
        }
        refreshTopAppBarSubtitle();
    }

    /**
     * 启动 ZT 服务连接至指定网络
     *
     * @param networkId 网络号
     */
    private void startService(long networkId) {
        Log.i(TAG, TRACE + " startService networkId=" + Long.toHexString(networkId));
        var intent = new Intent(requireActivity(), ZeroTierOneService.class);
        intent.putExtra(ZeroTierOneService.ZT1_NETWORK_ID, networkId);
        doBindService();
        requireActivity().startService(intent);
    }

    /**
     * 停止 ZT 服务
     */
    private void stopService() {
        this.monitorOnlyModeActive = false;
        if (this.mBoundService != null) {
            this.mBoundService.stopZeroTier();
        }
        var intent = new Intent(requireActivity(), ZeroTierOneService.class);
        this.eventBus.post(new StopEvent());
        if (!requireActivity().stopService(intent)) {
            Log.e(TAG, "stopService() returned false");
        }
        doUnbindService();
    }

    /**
     * 获得 Moon 入轨配置列表
     */
    private List<MoonOrbit> getMoonOrbitList() {
        DaoSession daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
        return daoSession.getMoonOrbitDao().loadAll();
    }

    /**
     * 加入网络后事件回调
     *
     * @param event 事件
     */
    @Subscribe
    public void onAfterJoinNetworkEvent(AfterJoinNetworkEvent event) {
        Log.d(TAG, "Event on: AfterJoinNetworkEvent");
        // 设置网络 orbit
        List<MoonOrbit> moonOrbits = NetworkListFragment.this.getMoonOrbitList();
        this.eventBus.post(new OrbitMoonEvent(moonOrbits));
    }

    /**
     * 处理首页网络卡片的连接开关事件。
     * <p>
     * 链路规则：
     * 1) 先校验当前外网可用性与移动网络策略，避免“先断后判”导致误断线；
     * 2) 仅在“已有其他网络连接”时才执行停服切换；
     * 3) 最后再启动/加入目标网络并同步 UI 状态。
     *
     * @param event 开关事件（包含开关句柄、目标状态和目标网络）
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNetworkListCheckedChangeEvent(NetworkListCheckedChangeEvent event) {
        var switchHandle = event.getSwitchHandle();
        var checked = event.isChecked();
        var selectedNetwork = event.getSelectedNetwork();
        if (isSwitchInteractionLocked()) {
            Log.w(TAG, TRACE + " switchChange ignored: operation in progress");
            requireActivity().runOnUiThread(this::updateNetworkListAndNotify);
            return;
        }
        lockSwitchInteraction();
        Log.i(TAG, TRACE + " switchChange checked=" + checked + " selectedNwid="
                + Long.toHexString(selectedNetwork.getNetworkId()) + " isBound=" + this.isBound()
                + " hasService=" + (this.mBoundService != null));
        if (checked) {
            // 先校验外网与策略，避免在不可连接时先断开当前网络
            var context = requireContext();
            boolean useCellularData = PreferenceManager
                    .getDefaultSharedPreferences(context)
                    .getBoolean(Constants.PREF_NETWORK_USE_CELLULAR_DATA, false);
            var activeNetworkInfo = ((ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE))
                    .getActiveNetworkInfo();
            if (activeNetworkInfo == null || !activeNetworkInfo.isConnectedOrConnecting()) {
                Log.w(TAG, TRACE + " switchOn blocked: no network");
                // 设备无网络
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(NetworkListFragment.this.getContext(), R.string.toast_no_network, Toast.LENGTH_SHORT).show();
                    switchHandle.setChecked(false);
                });
            } else if (useCellularData || !(activeNetworkInfo.getType() == 0)) {
                Long connectedNetworkId = this.viewModel.getConnectNetworkId().getValue();
                boolean hasConnectedNetwork = connectedNetworkId != null;
                boolean switchingAnotherNetwork = hasConnectedNetwork
                        && !connectedNetworkId.equals(selectedNetwork.getNetworkId());
                // 仅当切换到“另一个网络”时才先断旧连接，避免自连接场景被无意义 stopService 打断
                if (switchingAnotherNetwork) {
                    Log.i(TAG, TRACE + " switching network oldNwid=" + Long.toHexString(connectedNetworkId)
                            + " newNwid=" + Long.toHexString(selectedNetwork.getNetworkId()));
                    if (this.mBoundService != null) {
                        this.mBoundService.leaveNetwork(connectedNetworkId);
                    }
                    stopService();
                    this.viewModel.doChangeConnectNetwork(null);
                }
                // 可以连接至网络
                // 更新 DB 中的网络状态
                DatabaseUtils.writeLock.lock();
                try {
                    for (var network : this.mNetworks) {
                        network.setLastActivated(false);
                        network.update();
                    }
                    selectedNetwork.setLastActivated(true);
                    selectedNetwork.update();
                } finally {
                    DatabaseUtils.writeLock.unlock();
                }
                // 连接目标网络统一走 startService 主链路，避免绑定态下 node 为空导致 join 直接失败。
                Log.i(TAG, TRACE + " join via startService nwid="
                        + Long.toHexString(selectedNetwork.getNetworkId()));
                this.sendStartServiceIntent(selectedNetwork.getNetworkId());
                this.viewModel.doChangeConnectNetwork(selectedNetwork.getNetworkId());
                Log.d(TAG, "Joining Network: " + selectedNetwork.getNetworkIdStr());
                requireActivity().runOnUiThread(this::updateNetworkListAndNotify);
            } else {
                Log.w(TAG, TRACE + " switchOn blocked: mobile data disabled");
                // 移动数据且未确认
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(this.getContext(), R.string.toast_mobile_data, Toast.LENGTH_SHORT).show();
                    switchHandle.setChecked(false);
                });
            }
        } else {
            // 关闭网络
            Log.i(TAG, TRACE + " switchOff nwid=" + Long.toHexString(selectedNetwork.getNetworkId()));
            Log.d(TAG, "Leaving Leaving Network: " + selectedNetwork.getNetworkIdStr());
            if (this.isBound() && this.mBoundService != null) {
                this.mBoundService.leaveNetwork(selectedNetwork.getNetworkId());
                this.doUnbindService();
            }
            this.stopService();
            this.viewModel.doChangeConnectNetwork(null);
            requireActivity().runOnUiThread(this::updateNetworkListAndNotify);
        }
    }

    /**
     * 显示无通知权限的提示框。若用户选择过 “不再提示”，则此方法将不进行任何操作
     */
    private void showNoNotificationAlertDialog() {
        // 检查是否选择过 “不再提示”，若是则不显示
        var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());

        if (sharedPreferences.getBoolean(Constants.PREF_DISABLE_NO_NOTIFICATION_ALERT, false)) {
            return;
        }

        // 显示提示框
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_no_notification_alert, null);

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                .setView(view)
                .setTitle(R.string.dialog_no_notification_alert_title)
                .setPositiveButton(R.string.open_notification_settings, (dialog, which) -> {
                    // 打开 APP 的通知设置
                    var intent = new Intent();

                    intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.putExtra("app_package", requireContext().getPackageName());
                    intent.putExtra("app_uid", requireContext().getApplicationInfo().uid);
                    intent.putExtra("android.provider.extra.APP_PACKAGE", requireContext().getPackageName());
                    startActivity(intent);
                })
                .setNegativeButton(R.string.dont_show_again, (dialog, which) -> {
                    // 设置不再提示此对话框
                    sharedPreferences.edit()
                            .putBoolean(Constants.PREF_DISABLE_NO_NOTIFICATION_ALERT, true)
                            .apply();
                })
                .setCancelable(true);

        builder.create().show();
    }

    private void initTopAppBar() {
        if (this.topAppBar == null) {
            return;
        }
        this.topAppBar.setOverflowIcon(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_more_vert_24));
        this.topAppBar.setTitle(getString(R.string.app_name));
        refreshTopAppBarSubtitle();
        this.topAppBar.setOnMenuItemClickListener(this::onTopAppBarMenuItemSelected);
    }

    private void refreshTopAppBarSubtitle() {
        if (this.topConnectionSummary == null) {
            return;
        }
        String intranet = this.intranetSummary == null || this.intranetSummary.isEmpty()
                ? getString(R.string.network_intranet_no)
                : this.intranetSummary;
        String networkInfo = "";
        NetworkStatus connectedKernelStatus = null;
        Long connectedNetworkId = this.viewModel == null ? null : this.viewModel.getConnectNetworkId().getValue();
        if (connectedNetworkId != null) {
            for (var network : this.mNetworks) {
                if (connectedNetworkId.equals(network.getNetworkId())) {
                    String networkName = network.getNetworkName();
                    if (networkName != null && !networkName.isEmpty()) {
                        networkInfo = networkName;
                    } else {
                        networkInfo = network.getNetworkIdStr();
                    }
                    var networkConfig = network.getNetworkConfig();
                    if (networkConfig != null) {
                        connectedKernelStatus = networkConfig.getStatus();
                    }
                    break;
                }
            }
        }
        boolean hasConnectedNetwork = connectedNetworkId != null;
        String statusText;
        NetworkStatus displayStatusForColor = null;
        if (!hasConnectedNetwork) {
            statusText = getString(R.string.network_disconnected);
        } else if (connectedKernelStatus == null || connectedKernelStatus == NetworkStatus.REQUESTING_CONFIGURATION) {
            statusText = getString(R.string.network_status_requesting_configuration);
            displayStatusForColor = NetworkStatus.REQUESTING_CONFIGURATION;
        } else {
            statusText = getString(connectedKernelStatus.toStringId());
            displayStatusForColor = connectedKernelStatus;
        }

        StringBuilder summary = new StringBuilder(statusText);
        if (!networkInfo.isEmpty()) {
            summary.append(" ").append(networkInfo);
        }
        this.topConnectionSummary.setText(summary.toString());
        this.topConnectionSummary.setTextColor(hasConnectedNetwork
                ? resolveStatusColor(displayStatusForColor)
                : ContextCompat.getColor(requireContext(), R.color.status_neutral));
        if (this.topIntranetSummary != null) {
            boolean showIntranetSummary = shouldShowIntranetSummary();
            if (showIntranetSummary) {
                this.topIntranetSummary.setVisibility(View.VISIBLE);
                this.topIntranetSummary.setText(intranet);
                this.topIntranetSummary.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_text_primary));
            } else {
                this.topIntranetSummary.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 判断顶部是否应显示“内网：是/否”。
     * <p>
     * 规则：
     * 1) 自动内网策略未开启：不显示；
     * 2) 探测 IP 未配置：不显示；
     * 3) 仅在“已开启且已配置”时显示。
     *
     * @return true 显示；false 隐藏
     */
    private boolean shouldShowIntranetSummary() {
        if (!isAdded()) {
            return false;
        }
        Context appContext = requireContext().getApplicationContext();
        return AutoConnectPolicy.isAutoRouteCheckEnabled(appContext)
                && AutoConnectPolicy.isConfigReady(appContext);
    }

    /**
     * 当前是否处于开关交互锁定窗口（防抖/防连点）。
     *
     * @return true 表示暂时禁止点击开关
     */
    private boolean isSwitchInteractionLocked() {
        return SystemClock.elapsedRealtime() < this.switchInteractionLockedUntilMs;
    }

    /**
     * 锁定开关交互，避免连接流程被连续点击打断。
     */
    private void lockSwitchInteraction() {
        this.switchInteractionLockedUntilMs = SystemClock.elapsedRealtime() + SWITCH_INTERACTION_LOCK_MS;
    }

    /**
     * 解除开关交互锁。
     */
    private void unlockSwitchInteraction() {
        this.switchInteractionLockedUntilMs = 0L;
    }

    /**
     * 网络信息列表适配器
     */
    public class RecyclerViewAdapter extends RecyclerView.Adapter<RecyclerViewAdapter.ViewHolder> {

        private final List<Network> mValues;

        public RecyclerViewAdapter(List<Network> items) {
            this.mValues = items;
            Log.d(NetworkListFragment.TAG, "Created network list item adapter");
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.list_item_network, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(final RecyclerViewAdapter.ViewHolder holder, int position) {
            Network network = mValues.get(position);
            holder.mItem = network;
            // 设置文本信息
            holder.mNetworkId.setText(getString(
                    R.string.status_line_format,
                    normalizeFieldLabel(getString(R.string.network_id)),
                    network.getNetworkIdStr()
            ));
            String networkName = network.getNetworkName();
            if (networkName != null && !networkName.isEmpty()) {
                holder.mNetworkName.setText(getString(
                        R.string.status_line_format,
                        normalizeFieldLabel(getString(R.string.network_name)),
                        networkName
                ));
            } else {
                holder.mNetworkName.setText(getString(
                        R.string.status_line_format,
                        normalizeFieldLabel(getString(R.string.network_name)),
                        getString(R.string.empty_network_name)
                ));
            }
            // 设置点击事件
            holder.mView.setOnClickListener(holder::onClick);
            // 设置长按事件
            holder.mView.setOnLongClickListener(holder::onLongClick);
            // 判断连接状态
            Long connectedNetworkId = NetworkListFragment.this.viewModel
                    .getConnectNetworkId().getValue();
            boolean connected = connectedNetworkId != null &&
                    connectedNetworkId.equals(network.getNetworkId());
            // 设置开关
            holder.mSwitch.setOnCheckedChangeListener(null);
            holder.mSwitch.setChecked(connected);
            // 卡片状态统一采用内核回调状态，不使用开关状态推断
            NetworkStatus kernelStatus = null;
            var networkConfig = network.getNetworkConfig();
            if (networkConfig != null) {
                kernelStatus = networkConfig.getStatus();
            }
            boolean pendingKernelState = connected && (kernelStatus == null
                    || kernelStatus == NetworkStatus.REQUESTING_CONFIGURATION);
            holder.mSwitch.setEnabled(!NetworkListFragment.this.isSwitchInteractionLocked()
                    && !pendingKernelState);
            holder.mSwitch.setOnCheckedChangeListener(holder::onSwitchCheckedChanged);
            NetworkStatus displayStatus = null;
            String displayStatusText;
            if (!connected) {
                displayStatusText = getString(R.string.network_disconnected);
            } else if (kernelStatus == null) {
                displayStatus = NetworkStatus.REQUESTING_CONFIGURATION;
                displayStatusText = getString(R.string.network_status_requesting_configuration);
            } else {
                displayStatus = kernelStatus;
                displayStatusText = getString(kernelStatus.toStringId());
            }
            holder.mNetworkState.setText(getString(
                    R.string.status_line_format,
                    getString(R.string.network_status),
                    displayStatusText
            ));
            holder.mNetworkState.setTextColor(NetworkListFragment.this.resolveStatusColor(displayStatus));
            boolean kernelConnected = connected && displayStatus == NetworkStatus.OK;
            String p2pText = kernelConnected
                    ? ((NetworkListFragment.this.p2pSummary == null || NetworkListFragment.this.p2pSummary.isEmpty())
                    ? getString(R.string.network_p2p_offline)
                    : NetworkListFragment.this.p2pSummary)
                    : getString(R.string.network_p2p_offline);
            holder.mNetworkP2P.setText(p2pText);
            boolean p2pEnabled = kernelConnected
                    && getString(R.string.network_p2p_waiting).equals(holder.mNetworkP2P.getText().toString());
            holder.mNetworkP2P.setTextColor(ContextCompat.getColor(
                    holder.mView.getContext(),
                    p2pEnabled ? R.color.status_ok : R.color.status_neutral
            ));
            boolean showIntranetHint = shouldShowIntranetSummary() && Boolean.TRUE.equals(NetworkListFragment.this.intranetDetected);
            if (showIntranetHint) {
                holder.mNetworkIntranetHint.setVisibility(View.VISIBLE);
                holder.mNetworkIntranetHint.setText(R.string.network_intranet_skip_hint);
                holder.mNetworkIntranetHint.setTextColor(ContextCompat.getColor(
                        holder.mView.getContext(), R.color.status_neutral));
            } else {
                holder.mNetworkIntranetHint.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return mValues.size();
        }

        @ToString
        public class ViewHolder extends RecyclerView.ViewHolder {
            public final View mView;
            public final TextView mNetworkId;
            public final TextView mNetworkName;
            public final TextView mNetworkState;
            public final TextView mNetworkP2P;
            public final TextView mNetworkIntranetHint;
            public final SwitchCompat mSwitch;
            public Network mItem;

            public ViewHolder(View view) {
                super(view);
                mView = view;
                mNetworkId = view.findViewById(R.id.network_list_network_id);
                mNetworkName = view.findViewById(R.id.network_list_network_name);
                mNetworkState = view.findViewById(R.id.network_list_network_state);
                mNetworkP2P = view.findViewById(R.id.network_list_network_p2p);
                mNetworkIntranetHint = view.findViewById(R.id.network_list_network_intranet_hint);
                mSwitch = view.findViewById(R.id.network_start_network_switch);
            }

            /**
             * 单击列表项打开网络详细页面
             */
            public void onClick(View view) {
                Log.d(NetworkListFragment.TAG, "ConvertView OnClickListener");
                Intent intent = new Intent(NetworkListFragment.this.getActivity(), NetworkDetailActivity.class);
                intent.putExtra(NetworkListFragment.NETWORK_ID_MESSAGE, this.mItem.getNetworkId());
                NetworkListFragment.this.startActivity(intent);
            }

            /**
             * 长按列表项创建弹出菜单
             */
            public boolean onLongClick(View view) {
                var that = NetworkListFragment.this;
                Log.d(NetworkListFragment.TAG, "ConvertView OnLongClickListener");
                PopupMenu popupMenu = new PopupMenu(that.getActivity(), view);
                popupMenu.getMenuInflater().inflate(R.menu.popup_menu_network_item, popupMenu.getMenu());
                popupMenu.show();
                popupMenu.setOnMenuItemClickListener(menuItem -> {
                    if (menuItem.getItemId() == R.id.menu_item_delete_network) {
                        // 删除对应网络
                        DaoSession daoSession = ((ZerotierFixApplication) that.requireActivity().getApplication()).getDaoSession();
                        AssignedAddressDao assignedAddressDao = daoSession.getAssignedAddressDao();
                        NetworkConfigDao networkConfigDao = daoSession.getNetworkConfigDao();
                        NetworkDao networkDao = daoSession.getNetworkDao();
                        if (this.mItem != null) {
                            // 如果删除的是当前连接的网络，则停止服务
                            var connectedNetworkId = that.viewModel.getConnectNetworkId().getValue();
                            if (this.mItem.getNetworkId().equals(connectedNetworkId)) {
                                that.stopService();
                            }
                            // 从 DB 中删除网络
                            NetworkConfig networkConfig = this.mItem.getNetworkConfig();
                            if (networkConfig != null) {
                                List<AssignedAddress> assignedAddresses = networkConfig.getAssignedAddresses();
                                if (!assignedAddresses.isEmpty()) {
                                    for (AssignedAddress assignedAddress : assignedAddresses) {
                                        assignedAddressDao.delete(assignedAddress);
                                    }
                                }
                                networkConfigDao.delete(networkConfig);
                            }
                            networkDao.delete(this.mItem);
                        }
                        daoSession.clear();
                        // 更新数据
                        that.updateNetworkListAndNotify();
                        return true;
                    } else if (menuItem.getItemId() == R.id.menu_item_copy_network_id) {
                        // 复制网络 ID
                        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText(getString(R.string.network_id), this.mItem.getNetworkIdStr());
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(getContext(), R.string.text_copied, Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    return false;
                });
                return true;
            }

            /**
             * 点击开启网络开关
             */
            public void onSwitchCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                NetworkListFragment.this.eventBus.post(new NetworkListCheckedChangeEvent(
                        this.mSwitch,
                        isChecked,
                        this.mItem
                ));
            }
        }
    }

}
