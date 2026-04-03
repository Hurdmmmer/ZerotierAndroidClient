package net.kaaass.zerotierfix.service.policy;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import net.kaaass.zerotierfix.events.IntranetCheckStateEvent;
import net.kaaass.zerotierfix.util.AutoConnectPolicy;

import org.greenrobot.eventbus.EventBus;

/**
 * 路由策略决策引擎。
 * <p>
 * 该组件只负责“判定”，不负责执行 Service/Runtime 的启停动作：
 * 1) 读取用户策略开关与探测配置；
 * 2) 执行内网探测；
 * 3) 输出统一决策结果，供上层 Service 执行。
 */
public final class RoutePolicyEngine {
    private static final String TRACE = "CONNECT_TRACE";
    private static final String TAG = "ZT1_Service";

    private RoutePolicyEngine() {
    }

    /**
     * 策略执行动作。
     */
    public enum PolicyAction {
        /**
         * 保持/恢复 ZeroTier 运行。
         */
        KEEP_RUNNING,
        /**
         * 进入监听态，不启用 ZeroTier 转发。
         */
        ENTER_MONITOR_ONLY
    }

    /**
     * 路由策略决策结果。
     */
    public static final class Decision {
        /** 策略动作：继续运行或进入监听态。 */
        private final PolicyAction action;
        /** 内网探测结果：true/false/null(未知)。 */
        private final Boolean inIntranet;
        /** 诊断细节码，便于日志与 UI 展示。 */
        private final String detail;

        /**
         * 构造策略决策结果。
         *
         * @param action      策略执行动作
         * @param inIntranet  内网判定结果（true/false/null）
         * @param detail      诊断明细
         */
        public Decision(PolicyAction action, Boolean inIntranet, String detail) {
            this.action = action;
            this.inIntranet = inIntranet;
            this.detail = detail;
        }

        /**
         * @return 策略执行动作
         */
        public PolicyAction getAction() {
            return action;
        }

        /**
         * @return 内网判定结果（true/false/null）
         */
        public Boolean getInIntranet() {
            return inIntranet;
        }

        /**
         * @return 诊断明细
         */
        public String getDetail() {
            return detail;
        }

    }

    /**
     * 执行一次策略决策。
     *
     * @param context 上下文
     * @return 决策结果
     */
    public static Decision evaluate(Context context) {
        return evaluate(context, "unknown");
    }

    /**
     * 执行一次策略决策（带触发原因日志）。
     *
     * @param context 上下文
     * @param reason  触发原因
     * @return 决策结果
     */
    public static Decision evaluate(Context context, String reason) {
        boolean enabled = AutoConnectPolicy.isAutoRouteCheckEnabled(context);
        String probeIp = AutoConnectPolicy.getConfiguredProbeIp(context);
        Log.i(TRACE, "RoutePolicyEngine.evaluate reason=" + reason
                + ", enabled=" + enabled
                + ", probeIpEmpty=" + probeIp.isEmpty());
        if (!enabled) {
            Decision decision = new Decision(PolicyAction.KEEP_RUNNING, null, "auto_route_check_disabled");
            Log.i(TRACE, "RoutePolicyEngine.evaluate result action=" + decision.getAction()
                    + ", inIntranet=" + decision.getInIntranet()
                    + ", detail=" + decision.getDetail());
            return decision;
        }
        if (!AutoConnectPolicy.isConfigReady(context)) {
            Decision decision = new Decision(PolicyAction.KEEP_RUNNING, null, "probe_ip_not_ready");
            Log.i(TRACE, "RoutePolicyEngine.evaluate result action=" + decision.getAction()
                    + ", inIntranet=" + decision.getInIntranet()
                    + ", detail=" + decision.getDetail());
            return decision;
        }
        AutoConnectPolicy.ProbeResult result = AutoConnectPolicy.detectIntranetState(context);
        PolicyAction action = Boolean.TRUE.equals(result.getInIntranet())
                ? PolicyAction.ENTER_MONITOR_ONLY
                : PolicyAction.KEEP_RUNNING;
        Decision decision = new Decision(action, result.getInIntranet(), result.getDetail());
        Log.i(TRACE, "RoutePolicyEngine.evaluate result action=" + decision.getAction()
                + ", inIntranet=" + decision.getInIntranet()
                + ", detail=" + decision.getDetail());
        return decision;
    }

    /**
     * 策略执行回调接口。
     * <p>
     * 由 Service 提供具体实现，策略引擎仅负责决策与协调，不直接持有 Service 类型。
     */
    public interface PolicyRuntimeDelegate {
        /**
        * 设置监听态标记。
        *
        * @param monitorOnlyMode 是否监听态
        */
        void setMonitorOnlyMode(boolean monitorOnlyMode);

        /**
         * @return 当前是否监听态
         */
        boolean isMonitorOnlyMode();

        /**
         * 进入监听态。
         *
         * @param reason 触发原因
         * @param detail 诊断明细
         */
        void enterMonitorOnlyMode(String reason, String detail);

        /**
         * @return 当前激活网络 ID
         */
        long getActiveNetworkId();

        /**
         * 恢复或启动 ZeroTier 并加入网络。
         *
         * @param networkId 目标网络 ID
         * @param reason    触发原因
         * @return 是否成功发起
         */
        boolean startOrResumeZeroTierAndJoin(long networkId, String reason);
    }

    /**
     * 自动路由策略协调器。
     * <p>
     * 由 RoutePolicyEngine 管理策略并发控制、保护窗口与异步探测流程。
     */
    public static final class Coordinator {
        /** 并发互斥锁。 */
        private final Object autoRoutePolicyLock = new Object();
        /** 当前是否有检测任务执行中。 */
        private boolean autoRouteCheckRunning = false;
        /** 手动连接保护窗口截止时间（elapsedRealtime）。 */
        private volatile long manualConnectProtectionDeadlineMs = 0L;

        /**
         * 计算并设置手动连接保护窗口。
         *
         * @param hasExplicitNetworkId 是否显式连接
         * @param protectionMs         保护时长
         * @return 当前设置后的截止时间
         */
        public long updateManualProtectionDeadline(boolean hasExplicitNetworkId, long protectionMs) {
            if (!hasExplicitNetworkId) {
                this.manualConnectProtectionDeadlineMs = 0L;
                return 0L;
            }
            long deadline = SystemClock.elapsedRealtime() + protectionMs;
            this.manualConnectProtectionDeadlineMs = deadline;
            return deadline;
        }

        /**
         * 清理手动连接保护窗口。
         */
        public void clearManualProtectionDeadline() {
            this.manualConnectProtectionDeadlineMs = 0L;
        }

        /**
         * 复位并发状态。
         */
        public void resetRunningState() {
            synchronized (this.autoRoutePolicyLock) {
                this.autoRouteCheckRunning = false;
            }
        }

        /**
         * 启动阶段执行一次内网策略决策。
         *
         * @param context  上下文
         * @param eventBus 事件总线
         * @param delegate 策略执行回调
         * @param reason   触发原因
         * @return true 表示应跳过 ZeroTier 转发
         */
        public boolean handleStartPolicy(
                Context context,
                EventBus eventBus,
                PolicyRuntimeDelegate delegate,
                String reason
        ) {
            Decision decision = RoutePolicyEngine.evaluate(context, reason);
            boolean policyEnabled = !("auto_route_check_disabled".equals(decision.getDetail()));
            eventBus.post(new IntranetCheckStateEvent(
                    policyEnabled,
                    decision.getInIntranet(),
                    reason,
                    decision.getDetail()
            ));

            if (!policyEnabled || decision.getInIntranet() == null) {
                delegate.setMonitorOnlyMode(false);
                return false;
            }
            if (decision.getAction() == PolicyAction.ENTER_MONITOR_ONLY) {
                delegate.enterMonitorOnlyMode(reason, decision.getDetail());
                return true;
            }
            if (delegate.isMonitorOnlyMode()) {
                Log.i(TRACE, "Service.handleIntranetPolicy resumeZeroTier reason=" + reason
                        + ", detail=" + decision.getDetail());
                delegate.setMonitorOnlyMode(false);
            }
            return false;
        }

        /**
         * 异步触发一次自动路由策略检测。
         *
         * @param context  上下文
         * @param eventBus 事件总线
         * @param delegate 策略执行回调
         * @param reason   触发原因
         */
        public void triggerAutoRoutePolicyCheckAsync(
                Context context,
                EventBus eventBus,
                PolicyRuntimeDelegate delegate,
                String reason
        ) {
            Log.i(TRACE, "Service.triggerAutoRoutePolicyCheck reason=" + reason);
            synchronized (this.autoRoutePolicyLock) {
                if (this.autoRouteCheckRunning) {
                    Log.i(TRACE, "Service.triggerAutoRoutePolicyCheck skip: running=true");
                    return;
                }
                this.autoRouteCheckRunning = true;
            }
            Thread checker = new Thread(() -> {
                try {
                    Decision decision = RoutePolicyEngine.evaluate(context, reason);
                    boolean policyEnabled = !("auto_route_check_disabled".equals(decision.getDetail()));
                    Log.i(TRACE, "Service.triggerAutoRoutePolicyCheck result enabled=" + policyEnabled
                            + ", action=" + decision.getAction()
                            + ", inIntranet=" + decision.getInIntranet() + ", detail=" + decision.getDetail());
                    eventBus.post(new IntranetCheckStateEvent(
                            policyEnabled,
                            decision.getInIntranet(),
                            reason,
                            decision.getDetail()
                    ));
                    if (!policyEnabled || decision.getInIntranet() == null) {
                        delegate.setMonitorOnlyMode(false);
                        return;
                    }
                    if (decision.getAction() == PolicyAction.ENTER_MONITOR_ONLY) {
                        long now = SystemClock.elapsedRealtime();
                        if (now < this.manualConnectProtectionDeadlineMs) {
                            Log.i(TRACE, "Service.triggerAutoRoutePolicyCheck skip ENTER_MONITOR_ONLY by manual protection"
                                    + " now=" + now + ", deadline=" + this.manualConnectProtectionDeadlineMs);
                            return;
                        }
                        delegate.enterMonitorOnlyMode(reason, decision.getDetail());
                    } else if (delegate.isMonitorOnlyMode()) {
                        long targetNetworkId = delegate.getActiveNetworkId();
                        if (targetNetworkId != 0L) {
                            Log.i(TRACE, "Service.triggerAutoRoutePolicyCheck resume from monitor-only reason=" + reason);
                            delegate.startOrResumeZeroTierAndJoin(targetNetworkId, reason);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Auto route policy check failed. reason=" + reason, e);
                    Log.e(TRACE, "Service.triggerAutoRoutePolicyCheck exception: " + e.getClass().getSimpleName());
                    eventBus.post(new IntranetCheckStateEvent(true, null, reason, "check_exception"));
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
}
