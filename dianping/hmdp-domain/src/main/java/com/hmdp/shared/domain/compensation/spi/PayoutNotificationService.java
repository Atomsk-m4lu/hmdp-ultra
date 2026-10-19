package com.hmdp.shared.domain.compensation.spi;

/**
 * 赔付通知服务 — SPI 扩展点，向用户推送赔付结果。
 *
 * <p>实现应保证通知失败不影响主流程。</p>
 */
public interface PayoutNotificationService {

    /**
     * 通知用户。
     *
     * @param claimNo 申诉单号
     * @param userId  用户ID，允许为 null；为 null 时实现应按 claimNo 反查用户
     * @param message 通知内容
     */
    void notifyUser(String claimNo, Long userId, String message);
}