package com.hmdp.shoprating.service;

import com.hmdp.shared.domain.taskchain.enums.ExecutionMode;
import com.hmdp.shared.domain.taskchain.enums.ProcessPhase;
import com.hmdp.shared.domain.taskchain.model.PhaseConfig;
import com.hmdp.shared.domain.taskchain.service.TaskChainEngine;
import com.hmdp.shoprating.domain.model.ShopRatingRefreshMessage;
import com.hmdp.shoprating.domain.model.ShopRatingRefreshTaskContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_RATING_REFRESH_LOCK_KEY;

/**
 * 店铺热度评分刷新处理器 —— 真正调 {@link TaskChainEngine} 跑 shop_rating_refresh 任务链的地方。
 * <p>
 * 调用链（三段式异步解耦）：
 * <pre>
 * 商详查询 / 博客发布
 *   → ShopRatingRefreshServiceImpl 发 RocketMQ
 *     → ShopRatingRefreshListener.onMessage()
 *       → ShopRatingRefreshProcessor.process()  ← ★ 本类在这里
 *         → TaskChainEngine.executePhaseChain("shop_rating_refresh", ...)
 *           → 引擎内部跑 7 个节点
 * </pre>
 * </p>
 * <p>
 * 为什么这里还要加 Redisson 分布式锁？
 * <ul>
 *   <li>triggerIfExpired 里的双重去重（Redis 时间戳 + Redisson 锁）是<b>发 MQ 前的去重</b>，防止短时间内发多条重复消息</li>
 *   <li>这里的锁是<b>消费端的幂等保护</b>——RocketMQ 可能重复投递、多个消费者实例可能同时收到同一条消息，
 *       拿到锁的那个实例才真正跑任务链，其他直接 return</li>
 * </ul>
 * </p>
 */
@Service
public class ShopRatingRefreshProcessor {

    /** 任务链引擎：共享基础设施，由 SharedEngineAutoConfiguration 装配 */
    private final TaskChainEngine taskChainEngine;

    /** Redisson 分布式锁客户端：多实例部署时保证同一 shopId 同时只有一个实例在跑评分刷新任务链 */
    private final RedissonClient redissonClient;

    public ShopRatingRefreshProcessor(TaskChainEngine taskChainEngine,
                                      RedissonClient redissonClient) {
        this.taskChainEngine = taskChainEngine;
        this.redissonClient = redissonClient;
    }

    /**
     * 消费入口：加锁 → 调引擎 → 解锁。
     * <p>
     * 链路配置与商详链路（shop_detail）的对比：
     * <pre>
     * shop_detail           = [DATA_PROCESSING 并行, POST_PROCESSING 串行]
     * shop_rating_refresh   = [PRE_VALIDATION 串行, DATA_PROCESSING 串行, DATA_INSERTION 并行]
     * </pre>
     * 评分刷新为什么 DATA_INSERTION 反而并行？
     * UPDATE 1 条 MySQL（UpdateShopScoreNode，skippable=false）
     * + DELETE 1 条 Redis（DeleteShopCacheNode，skippable=true）
     * + UPDATE 1 条 Redis（UpdateShopHotScoreNode，skippable=true）
     * + UPDATE 1 条 MySQL（MarkRatingRefreshTimeNode，skippable=true）
     * → 4 个写入互不依赖，一起跑最快
     * → 其中 3 个失败了不影响评分落库这个核心结果
     * </p>
     *
     * @param message MQ 消息体：包含 shopId / triggerSource / timestamp
     */
    public void process(ShopRatingRefreshMessage message) {
        // ① 加分布式锁：key = "shop:rating:refresh:lock:{shopId}"
        // 多实例部署时同一 shopId 同时只有一个实例能拿到锁
        RLock lock = redissonClient.getLock(SHOP_RATING_REFRESH_LOCK_KEY + message.getShopId());
        if (!lock.tryLock()) {
            // 锁已被其他实例持有 → 它正在跑这条 shopId 的评分刷新，这里放弃
            return;
        }
        try {
            // ② 构造 context：贯穿全链的数据容器，input 塞 MQ 消息
            ShopRatingRefreshTaskContext context = new ShopRatingRefreshTaskContext();
            context.setInput(message);

            // ③ 调 TaskChainEngine 跑 shop_rating_refresh 任务链
            //    三个阶段配置：
            //    PRE_VALIDATION 串行  → 风控过滤（RiskFilterNode），有依赖必须串行
            //    DATA_PROCESSING 串行 → 计算加权评分（CalcWeightedScoreNode），必须等 PRE_VALIDATION 通过
            //    DATA_INSERTION 并行  → 4 个写入节点互不依赖，一起跑最快
            taskChainEngine.executePhaseChain("shop_rating_refresh", context, Arrays.asList(
                    new PhaseConfig(ProcessPhase.PRE_VALIDATION, ExecutionMode.SERIAL),
                    new PhaseConfig(ProcessPhase.DATA_PROCESSING, ExecutionMode.SERIAL),
                    new PhaseConfig(ProcessPhase.DATA_INSERTION, ExecutionMode.PARALLEL)
            ));
        } finally {
            // ④ 必须 finally 里解锁：保证引擎执行异常时锁也能释放，不会锁死
            lock.unlock();
        }
    }
}