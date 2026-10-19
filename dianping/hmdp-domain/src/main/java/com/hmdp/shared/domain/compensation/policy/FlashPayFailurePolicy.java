package com.hmdp.shared.domain.compensation.policy;

import com.hmdp.shared.stateengine.model.NodeResult;
import com.hmdp.shared.stateengine.model.RetryDecision;
import com.hmdp.shared.stateengine.model.StateTask;
import com.hmdp.shared.stateengine.policy.FailurePolicy;

/**
 * 无忧闪赔失败策略 — 失败后始终转人工审核。
 *
 * <p>定位：无忧闪赔状态机层。闪赔场景下失败原因是业务数据问题
 * （如申诉原因为空、未配置金额规则、支付失败），重试无法自动修复，因此直接转人工。</p>
 *
 * <p>与秒杀对账的差异：秒杀对账的补偿失败通常是临时性故障（依赖服务暂不可用），
 * 适合重试；闪赔是数据校验问题，重试无意义。</p>
 */
public class FlashPayFailurePolicy implements FailurePolicy {

    @Override
    public RetryDecision onFailure(StateTask task, NodeResult result) {
        return RetryDecision.manualReview(result.getErrorMessage());
    }

    // 未来演进（当前严格保持"失败一律转人工"）：
    // 引入支付网关等基础设施后，可在此按 result.isRetryable() 分流：
    //   retryable=true  → RetryDecision.retry(...)（由 RetryPolicy 控制次数预算，FAILED 等外部 resume）
    //   retryable=false → RetryDecision.manualReview(...)（数据/业务问题，重试无意义）
    // 本次不改，是为了保持既有语义与测试基线不变。
}