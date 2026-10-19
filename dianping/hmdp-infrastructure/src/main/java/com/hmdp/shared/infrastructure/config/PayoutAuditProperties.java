package com.hmdp.shared.infrastructure.config;

import com.hmdp.shared.domain.compensation.spi.MockMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 无忧闪赔赔付配置项
 *
 * <p>对应前缀 flash-pay。</p>
 */
@Data
@ConfigurationProperties(prefix = "flash-pay")
public class PayoutAuditProperties {

    /** 赔付金额规则：planCode → 金额（单位分） */
    private Map<String, Long> payoutRules = new HashMap<>();

    /** 单笔赔付上限，单位分 */
    private long maxPayout = 500;

    /** 支付模拟模式 */
    private PaymentMock payment = new PaymentMock();

    @Data
    public static class PaymentMock {
        /** 模拟模式：SUCCESS / FAIL / REJECT */
        private MockMode mockMode = MockMode.SUCCESS;
    }
}