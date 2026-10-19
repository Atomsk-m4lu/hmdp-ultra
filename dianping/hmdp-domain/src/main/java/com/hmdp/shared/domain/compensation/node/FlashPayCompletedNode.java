package com.hmdp.shared.domain.compensation.node;

import com.hmdp.shared.domain.compensation.amount.PayoutAmountCalculator;
import com.hmdp.shared.domain.compensation.model.FlashPayPayload;
import com.hmdp.shared.domain.compensation.model.FlashPayStateCode;
import com.hmdp.shared.domain.compensation.spi.PayoutAuditRecord;
import com.hmdp.shared.domain.compensation.spi.PayoutAuditRepository;
import com.hmdp.shared.domain.compensation.spi.PayoutGateway;
import com.hmdp.shared.domain.compensation.spi.PayoutNotificationService;
import com.hmdp.shared.domain.compensation.spi.PaymentRecord;
import com.hmdp.shared.stateengine.model.NodeResult;
import com.hmdp.shared.stateengine.model.StateTask;
import com.hmdp.shared.stateengine.service.StateContext;
import com.hmdp.shared.stateengine.service.StateNode;

/**
 * 闪赔完成节点 — 查支付回执、写审计、发通知、收尾。
 *
 * <p>所有外部调用都有容错：查询/审计异常转为 failure(retryable=false) 转人工；
 * 通知吞异常只记日志，审计重复插入被 repository 静默忽略。</p>
 */
public class FlashPayCompletedNode implements StateNode {

    private final PayoutGateway gateway;
    private final PayoutAuditRepository auditRepository;
    private final PayoutNotificationService notificationService;
    private final PayoutAmountCalculator calculator;

    public FlashPayCompletedNode(PayoutGateway gateway,
                                 PayoutAuditRepository auditRepository,
                                 PayoutNotificationService notificationService,
                                 PayoutAmountCalculator calculator) {
        this.gateway = gateway;
        this.auditRepository = auditRepository;
        this.notificationService = notificationService;
        this.calculator = calculator;
    }

    @Override
    public String getStateCode() {
        return FlashPayStateCode.COMPLETED.name();
    }

    @Override
    public NodeResult execute(StateTask task, StateContext context) {
        PaymentRecord record;
        try {
            record = gateway.query(task.getTaskId());
        } catch (Exception e) {
            return NodeResult.failure("PAYMENT_QUERY_ERROR", "查询支付回执异常: " + e.getMessage(), false);
        }
        if (record == null || record.getTxId() == null) {
            return NodeResult.failure("PAYMENT_RECORD_MISSING", "未查到支付回执", false);
        }
        if (!record.isSuccess()) {
            return NodeResult.failure("PAYMENT_NOT_SUCCESSFUL", "支付回执状态为失败", false);
        }

        FlashPayPayload payload = FlashPayPayload.deserialize(task.getPayload());
        Long shopId = null;
        // payload 不含 userId；通知实现会按 claimNo 反查，审计侧可通过 claim_no 关联申诉单
        Long userId = null;
        try {
            shopId = Long.valueOf(task.getBizKey());
        } catch (NumberFormatException ignored) {
        }
        String claimNo = task.getTaskId();
        long amount = record.getAmount();

        try {
            auditRepository.save(new PayoutAuditRecord(
                    claimNo, shopId, userId, payload.getPlanCode(),
                    amount, record.getTxId(), FlashPayStateCode.COMPLETED.name(), "OK"));
        } catch (Exception e) {
            // 支付已成功但审计未落库：转人工对账，不能静默丢掉审计
            return NodeResult.failure("AUDIT_SAVE_ERROR", "保存赔付审计异常: " + e.getMessage(), false);
        }

        try {
            notificationService.notifyUser(claimNo, userId, "自动赔付已完成，金额=" + amount + "分");
        } catch (Exception ignored) {
            // 通知失败不影响主流程
        }

        return NodeResult.terminalSuccess("自动赔付已完成，金额=" + amount + "分");
    }
}