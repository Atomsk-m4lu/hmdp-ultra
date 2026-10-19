package com.hmdp.shared.domain.compensation.spi;

/**
 * 日志通知实现 — 仅打印标准输出，绝不抛异常。
 *
 * <p>domain 层不依赖 slf4j/logback，改用 System.out。</p>
 */
public class LoggingPayoutNotificationService implements PayoutNotificationService {

    @Override
    public void notifyUser(String claimNo, Long userId, String message) {
        try {
            System.out.println("[PayoutNotify] claimNo=" + claimNo + ", userId=" + userId + ", message=" + message);
        } catch (Exception e) {
            System.out.println("[PayoutNotify] failed, claimNo=" + claimNo + ", userId=" + userId + ", err=" + e.getMessage());
        }
    }
}