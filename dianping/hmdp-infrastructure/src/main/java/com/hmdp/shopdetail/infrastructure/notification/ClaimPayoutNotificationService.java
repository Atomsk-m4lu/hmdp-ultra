package com.hmdp.shopdetail.infrastructure.notification;

import com.hmdp.shared.domain.compensation.spi.PayoutNotificationService;
import com.hmdp.shopdetail.domain.model.GuaranteeClaim;
import com.hmdp.shopdetail.domain.repository.GuaranteeClaimRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 闪赔通知适配器 — 按 claimNo 反查申诉单补齐 userId。
 *
 * <p>payload 只有 planCode/reason，状态机节点传入的 userId 为 null；
 * 申诉单是用户归属的权威来源，这里按 claimNo 反查，避免为了通知去改 payload 格式。</p>
 *
 * <p>当前仍用结构化日志模拟发送；接真实短信/推送时替换 notifyUser 里的 log.info 即可。</p>
 */
@Slf4j
@Service
public class ClaimPayoutNotificationService implements PayoutNotificationService {

    private final GuaranteeClaimRepository claimRepository;

    public ClaimPayoutNotificationService(GuaranteeClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    @Override
    public void notifyUser(String claimNo, Long userId, String message) {
        Long resolvedUserId;
        try {
            resolvedUserId = resolveUserId(claimNo, userId);
        } catch (Exception e) {
            // 通知是旁路：查申诉单失败只告警，不影响状态机主流程
            log.warn("[PayoutNotify] resolve recipient failed, claimNo={}", claimNo, e);
            return;
        }
        if (resolvedUserId == null) {
            log.warn("[PayoutNotify] recipient not found, claimNo={}", claimNo);
            return;
        }
        // 真实短信/推送在这里调用；当前落结构化日志，userId 已是申诉单上的真实用户
        log.info("[PayoutNotify] claimNo={}, userId={}, message={}", claimNo, resolvedUserId, message);
    }

    Long resolveUserId(String claimNo, Long fallbackUserId) {
        if (fallbackUserId != null) {
            return fallbackUserId;
        }
        return claimRepository.findByClaimNo(claimNo)
                .map(GuaranteeClaim::getUserId)
                .orElse(null);
    }
}
