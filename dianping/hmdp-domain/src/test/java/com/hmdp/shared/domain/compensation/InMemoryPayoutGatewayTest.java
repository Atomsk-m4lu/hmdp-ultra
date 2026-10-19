package com.hmdp.shared.domain.compensation.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryPayoutGatewayTest {

    @Test
    void should_pay_success_in_success_mode() {
        InMemoryPayoutGateway gw = new InMemoryPayoutGateway(MockMode.SUCCESS);
        PaymentResult r = gw.pay("claim-1", 50);
        assertTrue(r.isSuccess());
        assertNotNull(r.getTxId());
    }

    @Test
    void should_return_same_txid_on_repeated_pay() {
        InMemoryPayoutGateway gw = new InMemoryPayoutGateway(MockMode.SUCCESS);
        PaymentResult first = gw.pay("claim-1", 50);
        PaymentResult second = gw.pay("claim-1", 50);
        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertEquals(first.getTxId(), second.getTxId());
    }

    @Test
    void should_pay_fail_in_fail_mode() {
        InMemoryPayoutGateway gw = new InMemoryPayoutGateway(MockMode.FAIL);
        PaymentResult r = gw.pay("claim-1", 50);
        assertFalse(r.isSuccess());
        assertEquals("GATEWAY_TIMEOUT", r.getErrorCode());
    }

    @Test
    void should_pay_fail_in_reject_mode() {
        InMemoryPayoutGateway gw = new InMemoryPayoutGateway(MockMode.REJECT);
        PaymentResult r = gw.pay("claim-1", 50);
        assertFalse(r.isSuccess());
        assertEquals("RISK_REJECTED", r.getErrorCode());
    }

    @Test
    void should_query_return_null_when_not_found() {
        InMemoryPayoutGateway gw = new InMemoryPayoutGateway(MockMode.SUCCESS);
        assertNull(gw.query("no-such-claim"));
    }

    @Test
    void should_query_return_record_after_pay() {
        InMemoryPayoutGateway gw = new InMemoryPayoutGateway(MockMode.SUCCESS);
        gw.pay("claim-1", 50);
        PaymentRecord rec = gw.query("claim-1");
        assertNotNull(rec);
        assertEquals(50, rec.getAmount());
        assertTrue(rec.isSuccess());
    }
}