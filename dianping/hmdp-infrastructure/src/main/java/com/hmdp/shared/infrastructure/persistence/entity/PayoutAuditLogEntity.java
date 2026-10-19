package com.hmdp.shared.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 赔付审计日志实体 — 映射 tb_payout_audit_log。
 */
@Data
@TableName("tb_payout_audit_log")
public class PayoutAuditLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String claimNo;
    private Long shopId;
    private Long userId;
    private String planCode;
    private Long payoutAmount;
    private String paymentTxId;
    private String status;
    private String message;
    private LocalDateTime createTime;
}