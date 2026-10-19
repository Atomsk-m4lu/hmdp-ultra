package com.hmdp.shared.domain.compensation.amount;

import java.util.Map;

/**
 * 赔付金额计算器 — 按 planCode 从规则表取金额，受 maxAmount 限制。
 *
 * <p>无规则或结果 <= 0 返回 0，上层节点据此判断 NO_PAYOUT_RULE。</p>
 */
public class PayoutAmountCalculator {

    private final Map<String, Long> rules;
    private final long maxAmount;

    public PayoutAmountCalculator(Map<String, Long> rules, long maxAmount) {
        this.rules = rules == null ? java.util.Collections.emptyMap() : rules;
        this.maxAmount = maxAmount;
    }

    /**
     * 计算指定 planCode 的赔付金额。
     *
     * @return 规则值与 maxAmount 的较小者；无规则或 <= 0 时返回 0
     */
    public long calculate(String planCode) {
        if (planCode == null || !rules.containsKey(planCode)) {
            return 0;
        }
        long rule = rules.get(planCode);
        if (rule <= 0) {
            return 0;
        }
        return Math.min(rule, maxAmount);
    }
}