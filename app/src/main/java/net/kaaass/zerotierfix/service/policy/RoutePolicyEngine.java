package net.kaaass.zerotierfix.service.policy;

import android.content.Context;
import android.util.Log;

import net.kaaass.zerotierfix.util.AutoConnectPolicy;

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
}
