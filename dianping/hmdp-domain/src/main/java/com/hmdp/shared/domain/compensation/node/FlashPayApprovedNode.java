package com.hmdp.shared.domain.compensation.node;

import com.hmdp.shared.domain.compensation.amount.PayoutAmountCalculator;
import com.hmdp.shared.domain.compensation.model.FlashPayPayload;
import com.hmdp.shared.domain.compensation.model.FlashPayStateCode;
import com.hmdp.shared.domain.compensation.spi.PaymentResult;
import com.hmdp.shared.domain.compensation.spi.PayoutGateway;
import com.hmdp.shared.stateengine.model.NodeResult;
import com.hmdp.shared.stateengine.model.StateTask;
import com.hmdp.shared.stateengine.service.StateContext;
import com.hmdp.shared.stateengine.service.StateNode;

/**
 * 闪赔审批通过节点 — 计算赔付金额并调用支付网关。
 *
 * <p>流程：反序列化 payload 取 planCode → 算金额 → 调 gateway.pay(taskId, amount)。
 * 无规则、支付失败或支付调用抛异常，一律返回 failure(retryable=false)，由 FailurePolicy 转人工审核。</p>
 *
 * <p>所有依赖均通过构造器注入，保证节点本身无静态可变状态。</p>
 */
public class FlashPayApprovedNode implements StateNode {

    private final PayoutAmountCalculator calculator;
    private final PayoutGateway gateway;

    public FlashPayApprovedNode(PayoutAmountCalculator calculator, PayoutGateway gateway) {
        this.calculator = calculator;
        this.gateway = gateway;
    }

    @Override
    public String getStateCode() {
        return FlashPayStateCode.APPROVED.name();
    }

    /**
     * APPROVED 节点核心业务：算金额 → 调支付 → 决定是否流转到 COMPLETED。
     *
     * <p>所有 failure 路径的 retryable 一律传 false —— 金额规则缺失是配置问题，
     * 支付网关异常或拒绝是外部问题，重试没有意义，统一走人工审核兜底。</p>
     *
     * <p>幂等键使用 task.getTaskId()（即 claimNo），由支付网关内部保证同一索赔单不会重复扣款。</p>
     */
    @Override
    public NodeResult execute(StateTask task, StateContext context) {
        // payload 仍是引擎不感知的纯字符串，节点自己反序列化取 planCode
        FlashPayPayload payload = FlashPayPayload.deserialize(task.getPayload());

        // 金额计算器内部会按 planCode 查规则、再受 maxAmount 上限约束
        long amount = calculator.calculate(payload.getPlanCode());
        if (amount <= 0) {
            // 无规则或结果为零意味着这个 planCode 不在赔付范围内，不是可重试故障
            return NodeResult.failure("NO_PAYOUT_RULE", "未配置赔付金额规则", false);
        }

        PaymentResult payResult;
        try {
            // 幂等：同一 claimNo 多次调用只会产生一条真实支付记录
            payResult = gateway.pay(task.getTaskId(), amount);
        } catch (Exception e) {
            // 网关抛异常（超时/网络抖动/序列化失败）—— 归为不可重试，由人工介入排查
            return NodeResult.failure("PAYMENT_ERROR", "支付调用异常: " + e.getMessage(), false);
        }

        if (!payResult.isSuccess()) {
            // 网关返回业务失败（余额不足、风控拒绝等）—— 同样不可重试，需人工判断是否改走其他赔付渠道
            return NodeResult.failure("PAYMENT_FAILED",
                    "支付失败(" + payResult.getErrorCode() + "): " + payResult.getMessage(), false);
        }

        // 支付成功，携带 txId 跳转到 COMPLETED 节点，由后续节点写审计和发通知
        return NodeResult.next(FlashPayStateCode.COMPLETED.name(),
                "已支付 amount=" + amount + "分 txId=" + payResult.getTxId());
    }
}