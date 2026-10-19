package com.hmdp.shared.domain.compensation.spi;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存赔付审计仓储 — 测试/默认装配用。
 *
 * <p>线程安全，按 claimNo 去重，重复 save 忽略。</p>
 */
public class InMemoryPayoutAuditRepository implements PayoutAuditRepository {

    private final Map<String, PayoutAuditRecord> store = new ConcurrentHashMap<>();

    @Override
    public void save(PayoutAuditRecord record) {
        store.putIfAbsent(record.getClaimNo(), record);
    }

    public Map<String, PayoutAuditRecord> snapshot() {
        return Collections.unmodifiableMap(store);
    }
}