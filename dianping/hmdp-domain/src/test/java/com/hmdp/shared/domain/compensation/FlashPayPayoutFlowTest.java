package com.hmdp.shared.domain.compensation;

import com.hmdp.shared.domain.compensation.amount.PayoutAmountCalculator;
import com.hmdp.shared.domain.compensation.machine.FlashPayStateMachineDefinition;
import com.hmdp.shared.domain.compensation.model.FlashPayPayload;
import com.hmdp.shared.domain.compensation.service.FlashPayStateMachineFacade;
import com.hmdp.shared.domain.compensation.spi.InMemoryPayoutAuditRepository;
import com.hmdp.shared.domain.compensation.spi.InMemoryPayoutGateway;
import com.hmdp.shared.domain.compensation.spi.LoggingPayoutNotificationService;
import com.hmdp.shared.domain.compensation.spi.MockMode;
import com.hmdp.shared.domain.compensation.spi.PaymentRecord;
import com.hmdp.shared.domain.compensation.spi.PaymentResult;
import com.hmdp.shared.domain.compensation.spi.PayoutAuditRecord;
import com.hmdp.shared.domain.compensation.spi.PayoutAuditRepository;
import com.hmdp.shared.domain.compensation.spi.PayoutGateway;
import com.hmdp.shared.stateengine.model.StateTask;
import com.hmdp.shared.stateengine.model.StateTaskStatus;
import com.hmdp.shared.stateengine.policy.RetryPolicy;
import com.hmdp.shared.stateengine.service.StateEngine;
import com.hmdp.shared.stateengine.service.StateMachineRegistry;
import com.hmdp.shared.stateengine.spi.InMemoryStateTaskRepository;
import com.hmdp.shared.stateengine.spi.InMemoryStateTransitionLogRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlashPayPayoutFlowTest {

    /** 成功路径：SUBMITTED→APPROVED→COMPLETED，3 个节点，最终 COMPLETED */
    @Test
    void should_complete_with_payout_when_rule_exists_and_pay_success() {
        Map<String, Long> rules = new HashMap<>();
        rules.put("FLASH_PAY", 50L);
        PayoutAmountCalculator calc = new PayoutAmountCalculator(rules, 500L);
        InMemoryPayoutGateway gw = new InMemoryPayoutGateway(MockMode.SUCCESS);
        InMemoryPayoutAuditRepository audit = new InMemoryPayoutAuditRepository();

        FlashPayStateMachineDefinition def = new FlashPayStateMachineDefinition(calc, gw, audit, new LoggingPayoutNotificationService());
        StateMachineRegistry reg = new StateMachineRegistry();
        reg.register(def);
        InMemoryStateTaskRepository taskRepo = new InMemoryStateTaskRepository();
        StateEngine engine = new StateEngine(reg, taskRepo, new InMemoryStateTransitionLogRepository(), new RetryPolicy(3));
        FlashPayStateMachineFacade facade = new FlashPayStateMachineFacade(engine, taskRepo);

        StateTask result = facade.start("claim-100", "shop-1", new FlashPayPayload("FLASH_PAY", "商家提前打烊"));

        assertEquals(StateTaskStatus.COMPLETED, result.getRuntimeStatus());
        assertEquals("COMPLETED", result.getCurrentState());
        assertEquals(3, result.getTransitionLogs().size());
        assertTrue(result.getLastMessage().contains("自动赔付"));
        assertNotNull(gw.query("claim-100"));
        assertEquals(1, audit.snapshot().size());
        PayoutAuditRecord auditRecord = audit.snapshot().get("claim-100");
        assertNotNull(auditRecord);
        assertEquals(50L, auditRecord.getPayoutAmount());
        assertEquals(gw.query("claim-100").getTxId(), auditRecord.getPaymentTxId());
        assertEquals("COMPLETED", auditRecord.getStatus());
    }

    /** 无金额规则 → SUBMITTED(成功)→APPROVED(failure NO_PAYOUT_RULE) → MANUAL_REVIEW，2 条日志 */
    @Test
    void should_route_to_manual_review_when_no_payout_rule() {
        PayoutAmountCalculator calc = new PayoutAmountCalculator(new HashMap<>(), 500L);
        InMemoryPayoutGateway gw = new InMemoryPayoutGateway(MockMode.SUCCESS);
        InMemoryPayoutAuditRepository audit = new InMemoryPayoutAuditRepository();

        FlashPayStateMachineDefinition def = new FlashPayStateMachineDefinition(calc, gw, audit, new LoggingPayoutNotificationService());
        StateMachineRegistry reg = new StateMachineRegistry();
        reg.register(def);
        InMemoryStateTaskRepository taskRepo = new InMemoryStateTaskRepository();
        StateEngine engine = new StateEngine(reg, taskRepo, new InMemoryStateTransitionLogRepository(), new RetryPolicy(3));
        FlashPayStateMachineFacade facade = new FlashPayStateMachineFacade(engine, taskRepo);

        StateTask result = facade.start("claim-200", "shop-2", new FlashPayPayload("UNKNOWN_PLAN", "商家提前打烊"));

        assertEquals(StateTaskStatus.MANUAL_REVIEW, result.getRuntimeStatus());
        assertEquals(2, result.getTransitionLogs().size());
        assertNull(gw.query("claim-200"));
    }

    /** 支付网关失败 → SUBMITTED(成功)→APPROVED(failure PAYMENT_FAILED) → MANUAL_REVIEW，2 条日志 */
    @Test
    void should_route_to_manual_review_when_payment_fails() {
        Map<String, Long> rules = new HashMap<>();
        rules.put("FLASH_PAY", 50L);
        PayoutAmountCalculator calc = new PayoutAmountCalculator(rules, 500L);
        InMemoryPayoutGateway gw = new InMemoryPayoutGateway(MockMode.FAIL);
        InMemoryPayoutAuditRepository audit = new InMemoryPayoutAuditRepository();

        FlashPayStateMachineDefinition def = new FlashPayStateMachineDefinition(calc, gw, audit, new LoggingPayoutNotificationService());
        StateMachineRegistry reg = new StateMachineRegistry();
        reg.register(def);
        InMemoryStateTaskRepository taskRepo = new InMemoryStateTaskRepository();
        StateEngine engine = new StateEngine(reg, taskRepo, new InMemoryStateTransitionLogRepository(), new RetryPolicy(3));
        FlashPayStateMachineFacade facade = new FlashPayStateMachineFacade(engine, taskRepo);

        StateTask result = facade.start("claim-300", "shop-3", new FlashPayPayload("FLASH_PAY", "商家提前打烊"));

        assertEquals(StateTaskStatus.MANUAL_REVIEW, result.getRuntimeStatus());
        assertEquals(2, result.getTransitionLogs().size());
        assertNotNull(gw.query("claim-300"));
        assertFalse(gw.query("claim-300").isSuccess());
    }

    /** 支付网关抛异常 → APPROVED(failure PAYMENT_ERROR) → MANUAL_REVIEW，不能被异常打断主循环 */
    @Test
    void should_route_to_manual_review_when_payment_gateway_throws() {
        Map<String, Long> rules = new HashMap<>();
        rules.put("FLASH_PAY", 50L);
        PayoutAmountCalculator calc = new PayoutAmountCalculator(rules, 500L);
        PayoutGateway throwingGateway = new PayoutGateway() {
            @Override
            public PaymentResult pay(String claimNo, long amount) {
                throw new IllegalStateException("gateway down");
            }

            @Override
            public PaymentRecord query(String claimNo) {
                return null;
            }
        };
        InMemoryPayoutAuditRepository audit = new InMemoryPayoutAuditRepository();

        FlashPayStateMachineDefinition def = new FlashPayStateMachineDefinition(
                calc, throwingGateway, audit, new LoggingPayoutNotificationService());
        StateMachineRegistry reg = new StateMachineRegistry();
        reg.register(def);
        InMemoryStateTaskRepository taskRepo = new InMemoryStateTaskRepository();
        StateEngine engine = new StateEngine(reg, taskRepo, new InMemoryStateTransitionLogRepository(), new RetryPolicy(3));
        FlashPayStateMachineFacade facade = new FlashPayStateMachineFacade(engine, taskRepo);

        StateTask result = facade.start("claim-400", "shop-4", new FlashPayPayload("FLASH_PAY", "商家提前打烊"));

        assertEquals(StateTaskStatus.MANUAL_REVIEW, result.getRuntimeStatus());
        assertTrue(result.getLastMessage().contains("支付调用异常"));
    }

    /** 审计写库抛异常 → COMPLETED(failure AUDIT_SAVE_ERROR) → MANUAL_REVIEW，支付成功也不能静默丢审计 */
    @Test
    void should_route_to_manual_review_when_audit_save_throws() {
        Map<String, Long> rules = new HashMap<>();
        rules.put("FLASH_PAY", 50L);
        PayoutAmountCalculator calc = new PayoutAmountCalculator(rules, 500L);
        InMemoryPayoutGateway gw = new InMemoryPayoutGateway(MockMode.SUCCESS);
        PayoutAuditRepository throwingAudit = record -> {
            throw new IllegalStateException("db down");
        };

        FlashPayStateMachineDefinition def = new FlashPayStateMachineDefinition(
                calc, gw, throwingAudit, new LoggingPayoutNotificationService());
        StateMachineRegistry reg = new StateMachineRegistry();
        reg.register(def);
        InMemoryStateTaskRepository taskRepo = new InMemoryStateTaskRepository();
        StateEngine engine = new StateEngine(reg, taskRepo, new InMemoryStateTransitionLogRepository(), new RetryPolicy(3));
        FlashPayStateMachineFacade facade = new FlashPayStateMachineFacade(engine, taskRepo);

        StateTask result = facade.start("claim-500", "shop-5", new FlashPayPayload("FLASH_PAY", "商家提前打烊"));

        assertEquals(StateTaskStatus.MANUAL_REVIEW, result.getRuntimeStatus());
        assertTrue(result.getLastMessage().contains("保存赔付审计异常"));
    }
}