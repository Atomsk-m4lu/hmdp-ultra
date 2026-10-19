package com.hmdp.shared.infrastructure.config;

import com.hmdp.shared.domain.compensation.amount.PayoutAmountCalculator;
import com.hmdp.shared.domain.compensation.model.FlashPayStateCode;
import com.hmdp.shared.domain.compensation.spi.InMemoryPayoutAuditRepository;
import com.hmdp.shared.domain.compensation.spi.InMemoryPayoutGateway;
import com.hmdp.shared.domain.compensation.spi.LoggingPayoutNotificationService;
import com.hmdp.shared.domain.compensation.spi.MockMode;
import com.hmdp.shared.stateengine.service.StateMachineRegistry;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SharedEngineAutoConfigurationTest {

    @Test
    void stateMachineRegistry_shouldOnlyRegisterFlashPayMachine() {
        SharedEngineAutoConfiguration configuration = new SharedEngineAutoConfiguration();

        StateMachineRegistry registry = configuration.stateMachineRegistry(
                new PayoutAmountCalculator(Collections.singletonMap("FLASH_PAY", 50L), 500L),
                new InMemoryPayoutGateway(MockMode.SUCCESS),
                new InMemoryPayoutAuditRepository(),
                new LoggingPayoutNotificationService());

        assertDoesNotThrow(() -> registry.getRequired(FlashPayStateCode.MACHINE_CODE));
        assertThrows(IllegalArgumentException.class,
                () -> registry.getRequired("SECKILL_RECONCILIATION"));
    }
}
