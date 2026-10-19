package com.hmdp.shared.domain.compensation.spi;

import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 赔付审计记录 — 不可变值对象。
 */
@Getter
public class PayoutAuditRecord {

    private final String claimNo;
    private final Long shopId;
    private final Long userId;
    private final String planCode;
    private final long payoutAmount;
    private final String paymentTxId;
    private final String status;
    private final String message;
    private final LocalDateTime createTime;

    public PayoutAuditRecord(String claimNo, Long shopId, Long userId, String planCode,
                             long payoutAmount, String paymentTxId, String status, String message) {
        this.claimNo = claimNo;
        this.shopId = shopId;
        this.userId = userId;
        this.planCode = planCode;
        this.payoutAmount = payoutAmount;
        this.paymentTxId = paymentTxId;
        this.status = status;
        this.message = message;
        this.createTime = LocalDateTime.now();
    }
}