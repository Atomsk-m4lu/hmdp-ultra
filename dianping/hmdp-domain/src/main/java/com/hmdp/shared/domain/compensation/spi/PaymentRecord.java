package com.hmdp.shared.domain.compensation.spi;

import lombok.Getter;

/**
 * 支付回执 — PayoutGateway.query() 的返回值，不可变值对象。
 */
@Getter
public class PaymentRecord {

    /** 申诉单号（幂等键） */
    private final String claimNo;
    /** 实际支付金额，单位分 */
    private final long amount;
    /** 支付网关交易流水号 */
    private final String txId;
    /** 是否支付成功 */
    private final boolean success;
    /** 结果说明 */
    private final String message;

    public PaymentRecord(String claimNo, long amount, String txId, boolean success, String message) {
        this.claimNo = claimNo;
        this.amount = amount;
        this.txId = txId;
        this.success = success;
        this.message = message;
    }
}