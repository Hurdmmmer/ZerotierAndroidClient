package net.kaaass.zerotierfix.events;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 内网探测状态事件。
 * <p>
 * inIntranet 语义：
 * 1) true: 判定已在内网（探测目标可通过物理网络直达）
 * 2) false: 判定不在内网
 * 3) null: 本次无法得出结论（配置缺失/系统信息不足/探测异常）
 */
@Data
@AllArgsConstructor
public class IntranetCheckStateEvent {

    /**
     * 是否启用了自动内网探测策略。
     */
    private final boolean enabled;

    /**
     * 内网判定结果，允许为空（未知）。
     */
    private final Boolean inIntranet;

    /**
     * 触发原因（如 service_start / network_available）。
     */
    private final String reason;

    /**
     * 诊断描述，便于日志与界面展示。
     */
    private final String detail;
}
