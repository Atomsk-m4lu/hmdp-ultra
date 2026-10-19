package com.hmdp.shared.domain.compensation.spi;

/**
 * 支付网关 — 闪赔赔付的对外支付接口，SPI 扩展点。
 *
 * <p>实现需保证幂等：同一 claimNo 多次调用 pay() 只产生一笔实际支付。</p>
 */
public interface PayoutGateway {

    /**
     * 发起支付。
     *
     * @param claimNo 申诉单号（幂等键）
     * @param amount  支付金额，单位分
     * @return 支付结果
     */
    PaymentResult pay(String claimNo, long amount);

    /**
     * 查询支付回执。
     *
     * @param claimNo 申诉单号
     * @return 支付记录，不存在则返回 null
     */
    PaymentRecord query(String claimNo);
}