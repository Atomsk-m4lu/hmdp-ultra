package com.hmdp.shared.domain.compensation.amount;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PayoutAmountCalculatorTest {

    @Test
    void should_return_rule_amount_when_within_max() {
        Map<String, Long> rules = new HashMap<>();
        rules.put("FLASH_PAY", 50L);
        PayoutAmountCalculator calc = new PayoutAmountCalculator(rules, 500L);
        assertEquals(50L, calc.calculate("FLASH_PAY"));
    }

    @Test
    void should_cap_at_max_when_rule_exceeds() {
        Map<String, Long> rules = new HashMap<>();
        rules.put("FLASH_PAY", 9999L);
        PayoutAmountCalculator calc = new PayoutAmountCalculator(rules, 500L);
        assertEquals(500L, calc.calculate("FLASH_PAY"));
    }

    @Test
    void should_return_zero_when_no_rule() {
        PayoutAmountCalculator calc = new PayoutAmountCalculator(new HashMap<>(), 500L);
        assertEquals(0L, calc.calculate("NO_SUCH_PLAN"));
    }

    @Test
    void should_return_zero_when_rule_is_negative() {
        Map<String, Long> rules = new HashMap<>();
        rules.put("FLASH_PAY", -10L);
        PayoutAmountCalculator calc = new PayoutAmountCalculator(rules, 500L);
        assertEquals(0L, calc.calculate("FLASH_PAY"));
    }

    @Test
    void should_return_zero_when_plan_code_null() {
        PayoutAmountCalculator calc = new PayoutAmountCalculator(new HashMap<>(), 500L);
        assertEquals(0L, calc.calculate(null));
    }
}