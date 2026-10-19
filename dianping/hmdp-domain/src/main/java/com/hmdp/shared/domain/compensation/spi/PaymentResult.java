package com.hmdp.shared.domain.compensation.spi;

import lombok.Getter;

/**
 * 支付结果 — PayoutGateway.pay() 的返回值，不可变值对象。
 */
@Getter
public class PaymentResult {

    /** 是否支付成功 */
    private final boolean success;
    /** 支付网关交易流水号（成功时必有值） */
    private final String txId;
    /** 错误码（失败时必有值） */
    private final String errorCode;
    /** 错误描述 */
    private final String message;

    private PaymentResult(boolean success, String txId, String errorCode, String message) {
        this.success = success;
        this.txId = txId;
        this.errorCode = errorCode;
        this.message = message;
    }

    public static PaymentResult success(String txId) {
        return new PaymentResult(true, txId, null, "OK");
    }

    public static PaymentResult failure(String errorCode, String message) {
        return new PaymentResult(false, null, errorCode, message);
    }
}