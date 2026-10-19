package com.hmdp.shared.domain.compensation.machine;

import com.hmdp.shared.domain.compensation.amount.PayoutAmountCalculator;
import com.hmdp.shared.domain.compensation.model.FlashPayStateCode;
import com.hmdp.shared.domain.compensation.node.FlashPayApprovedNode;
import com.hmdp.shared.domain.compensation.node.FlashPayCompletedNode;
import com.hmdp.shared.domain.compensation.node.FlashPayManualReviewNode;
import com.hmdp.shared.domain.compensation.node.FlashPaySubmittedNode;
import com.hmdp.shared.domain.compensation.policy.FlashPayFailurePolicy;
import com.hmdp.shared.domain.compensation.spi.PayoutAuditRepository;
import com.hmdp.shared.domain.compensation.spi.PayoutGateway;
import com.hmdp.shared.domain.compensation.spi.PayoutNotificationService;
import com.hmdp.shared.stateengine.service.StateMachineDefinition;

import java.util.Collections;

/**
 * 无忧闪赔状态机定义 — 声明闪赔状态机的拓扑结构。
 *
 * <p>状态流转图：
 * <pre>{@code
 *                     ┌──────────────────┐
 *                     │    SUBMITTED     │ ← 初始状态
 *                     │  (校验申诉原因)   │
 *                     └───┬──────────┬───┘
 *                         │          │
 *                  原因有效 │          │ 原因为空 (failure)
 *                         │          │
 *                         ▼          ▼
 *                  ┌───────────┐ ┌──────────────┐
 *                  │  APPROVED  │ │ MANUAL_REVIEW │ ← 终态
 *                  │(计算+支付) │ │  (人工审核)    │
 *                  └─────┬─────┘ └──────────────┘
 *                        │
 *                        ▼
 *                  ┌───────────┐
 *                  │ COMPLETED  │ ← 终态
 *                  │(回执+审计+通知)│
 *                  └───────────┘
 * }</pre></p>
 *
 * <p>终态：COMPLETED、MANUAL_REVIEW</p>
 * <p>失败策略：始终转人工，不自动重试。</p>
 */
public class FlashPayStateMachineDefinition extends StateMachineDefinition {

    /**
     * 构造器注入所有业务依赖，用于构造内部节点。
     *
     * @param calculator           赔付金额计算器
     * @param gateway              支付网关
     * @param auditRepository      赔付审计仓储
     * @param notificationService  赔付通知服务
     */
    public FlashPayStateMachineDefinition(PayoutAmountCalculator calculator,
                                          PayoutGateway gateway,
                                          PayoutAuditRepository auditRepository,
                                          PayoutNotificationService notificationService) {
        super(builder(FlashPayStateCode.MACHINE_CODE, FlashPayStateCode.SUBMITTED.name())
                .addNode(new FlashPaySubmittedNode())
                .addNode(new FlashPayApprovedNode(calculator, gateway))
                .addNode(new FlashPayCompletedNode(gateway, auditRepository, notificationService, calculator))
                .addNode(new FlashPayManualReviewNode())
                .terminalStates(Collections.singleton(FlashPayStateCode.COMPLETED.name()))
                .failurePolicy(new FlashPayFailurePolicy())
                .build());
    }
}