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

    /**
     * COMPLETED 节点收尾三件事：查回执 → 写审计 → 发通知，然后 terminalSuccess 结束整个状态机。
     *
     * <p>为什么不在 APPROVED 节点写审计？因为 APPROVED 只有 gateway.pay() 的同步返回，
     * 真正扣钱成功以 gateway.query() 查到的回执为准 —— 网关可能异步扣款或幂等合并，
     * 所以必须等 query 拿到最终状态再落审计。</p>
     *
     * <p>容错优先级：支付回执 &gt; 审计 &gt; 通知。
     * 回执缺失或失败意味着钱没出去，必须转人工；
     * 审计落库异常意味着钱出去了但没记账，也必须转人工对账；
     * 只有通知是"尽力而为"，吞异常不影响主流程 —— 用户没收到短信可以补发，但不能因为短信挂了把已支付的任务卡住。</p>
     */
    @Override
    public NodeResult execute(StateTask task, StateContext context) {
        // 不依赖 APPROVED 节点传下来的金额/txId，用 claimNo 主动回查网关 ——
        // 这是硬性约束：节点之间不能用静态变量/ThreadLocal 共享数据
        PaymentRecord record;
        try {
            record = gateway.query(task.getTaskId());
        } catch (Exception e) {
            // 网关查回执都挂了，说明基础设施有问题，人工排查
            return NodeResult.failure("PAYMENT_QUERY_ERROR", "查询支付回执异常: " + e.getMessage(), false);
        }
        if (record == null || record.getTxId() == null) {
            // 回执缺失：可能网关侧数据不一致，必须人工介入查钱到底扣没扣
            return NodeResult.failure("PAYMENT_RECORD_MISSING", "未查到支付回执", false);
        }
        if (!record.isSuccess()) {
            // 回执明确为失败：网关异步回传了拒绝（比如风控超时后才拒绝）
            return NodeResult.failure("PAYMENT_NOT_SUCCESSFUL", "支付回执状态为失败", false);
        }

        // --- 组装审计记录所需字段 ---
        FlashPayPayload payload = FlashPayPayload.deserialize(task.getPayload());

        // shopId 存在 bizKey 里（引擎不解析 bizKey，节点自己按约定取值）；userId 不在 payload 里，
        // 通知实现需按 claimNo 去申诉单反查，审计侧也留 null 让人工对账时关联
        Long shopId = null;
        Long userId = null;
        try {
            shopId = Long.valueOf(task.getBizKey());
        } catch (NumberFormatException ignored) {
        }
        String claimNo = task.getTaskId();
        long amount = record.getAmount();

        try {
            // repository 内部会捕获 DuplicateKeyException 静默忽略，保证任务重跑不会插入重复审计
            auditRepository.save(new PayoutAuditRecord(
                    claimNo, shopId, userId, payload.getPlanCode(),
                    amount, record.getTxId(), FlashPayStateCode.COMPLETED.name(), "OK"));
        } catch (Exception e) {
            // 支付已成功但审计未落库：钱出去了但没记账，转人工对账，绝对不能静默丢掉这条审计
            return NodeResult.failure("AUDIT_SAVE_ERROR", "保存赔付审计异常: " + e.getMessage(), false);
        }

        // 通知是"尽力而为"：try/catch 吞掉任何异常，绝不影响主流程收尾
        try {
            notificationService.notifyUser(claimNo, userId, "自动赔付已完成，金额=" + amount + "分");
        } catch (Exception ignored) {
        }

        // terminalSuccess：终态，引擎不会再推进，状态机生命周期结束
        return NodeResult.terminalSuccess("自动赔付已完成，金额=" + amount + "分");
    }
}