package com.hmdp.shared.domain.compensation.spi;

/**
 * 赔付审计仓储 — SPI 扩展点，负责持久化赔付审计记录。
 *
 * <p>实现需保证按 claimNo 幂等：重复 save 应静默忽略。</p>
 */
public interface PayoutAuditRepository {

    void save(PayoutAuditRecord record);
}