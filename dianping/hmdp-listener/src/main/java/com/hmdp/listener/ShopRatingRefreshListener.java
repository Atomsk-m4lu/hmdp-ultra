package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.shoprating.domain.model.ShopRatingRefreshMessage;
import com.hmdp.shoprating.service.ShopRatingRefreshProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 店铺热度评分刷新 MQ 消费端入口。
 * <p>
 * 上游触发方是 {@code ShopRatingRefreshServiceImpl}，它同步发消息到 topic=shop-rating-refresh-topic，
 * 本 Listener 异步消费后委托 {@link ShopRatingRefreshProcessor} 去跑 shop_rating_refresh 任务链。
 * 整个链路：商详查询 → 发 MQ → Listener 消费 → Processor 调引擎。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "shop-rating-refresh-topic",
        consumerGroup = "shop-rating-refresh-consumer-group",
        selectorExpression = "refresh"
)
public class ShopRatingRefreshListener implements RocketMQListener<String> {

    /** 消息处理器：真正调 TaskChainEngine 跑 shop_rating_refresh 任务链 */
    private final ShopRatingRefreshProcessor processor;

    /**
     * RocketMQ 回调：收到消息后反序列化 → 记日志 → 交给 Processor。
     * <p>
     * Processor.process() 内部会加 Redisson 分布式锁（防止同一 shopId 并发消费），
     * 然后调 TaskChainEngine.executePhaseChain("shop_rating_refresh", ...)。
     * </p>
     *
     * @param message RocketMQ 投递的原始 JSON 字符串
     */
    @Override
    public void onMessage(String message) {
        // ① JSON → 领域对象：ShopRatingRefreshMessage 包含 shopId / triggerSource / timestamp
        ShopRatingRefreshMessage refreshMessage = JSONUtil.toBean(message, ShopRatingRefreshMessage.class);
        // ② 日志：方便排查消息从哪来（SHOP_DETAIL_EXPIRED 还是 BLOG_PUBLISHED）
        log.info("shop rating refresh message received, shopId={}, source={}",
                refreshMessage.getShopId(), refreshMessage.getTriggerSource());
        // ③ 委托 Processor：加分布式锁 → 调 TaskChainEngine 跑 shop_rating_refresh 任务链
        processor.process(refreshMessage);
    }
}