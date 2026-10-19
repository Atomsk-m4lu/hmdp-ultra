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

    @Override
    public NodeResult execute(StateTask task, StateContext context) {
        FlashPayPayload payload = FlashPayPayload.deserialize(task.getPayload());
        long amount = calculator.calculate(payload.getPlanCode());
        if (amount <= 0) {
            return NodeResult.failure("NO_PAYOUT_RULE", "未配置赔付金额规则", false);
        }
        PaymentResult payResult;
        try {
            payResult = gateway.pay(task.getTaskId(), amount);
        } catch (Exception e) {
            return NodeResult.failure("PAYMENT_ERROR", "支付调用异常: " + e.getMessage(), false);
        }
        if (!payResult.isSuccess()) {
            return NodeResult.failure("PAYMENT_FAILED",
                    "支付失败(" + payResult.getErrorCode() + "): " + payResult.getMessage(), false);
        }
        return NodeResult.next(FlashPayStateCode.COMPLETED.name(),
                "已支付 amount=" + amount + "分 txId=" + payResult.getTxId());
    }
}