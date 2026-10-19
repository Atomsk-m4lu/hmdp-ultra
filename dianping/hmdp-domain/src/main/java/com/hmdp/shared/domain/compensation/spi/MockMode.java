package com.hmdp.shared.domain.compensation.spi;

/**
 * 支付模拟模式 — 供 InMemoryPayoutGateway 测试时切换行为。
 */
public enum MockMode {
    /** 模拟支付成功 */
    SUCCESS,
    /** 模拟网关故障（返回失败，retryable=false） */
    FAIL,
    /** 模拟业务拒绝（如风控拦截） */
    REJECT
}