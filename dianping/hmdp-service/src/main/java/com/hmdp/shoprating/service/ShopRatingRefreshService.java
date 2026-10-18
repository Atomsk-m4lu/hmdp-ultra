package com.hmdp.shoprating.service;

/**
 * 店铺热度评分刷新服务接口。
 * <p>
 * 两个触发入口，对应两种场景：
 * <ul>
 *   <li>{@link #triggerFromBlog(Long)} —— 探店博客发布，<b>写操作事件</b>，即时触发，无去重</li>
 *   <li>{@link #triggerIfExpired(Long)} —— 商详查询后检查，<b>读操作触发</b>，双重去重（Redis 时间戳 + Redisson 分布式锁）</li>
 * </ul>
 * 两个方法最终都发 RocketMQ，listener 消费后跑 {@code shop_rating_refresh} 任务链。
 * </p>
 */
public interface ShopRatingRefreshService {

    /**
     * 探店博客发布触发评分刷新（即时触发，无去重）。
     * <p>
     * 博客发布是明确的写操作事件，必然影响店铺热度，所以直接发 MQ，不做去重。
     * 由 BlogService 在用户发布探店博客后调用。
     * </p>
     *
     * @param shopId 被探店的店铺 ID
     */
    void triggerFromBlog(Long shopId);

    /**
     * 商详查询后检查是否过期，过期则触发评分刷新（双重去重）。
     * <p>
     * 商详查询是读操作，高 QPS 下同一店铺每秒可能被查多次，必须用 Redis 时间戳 + Redisson 分布式锁做双重去重。
     * 由 ShopDetailApplicationService.getShopDetail() 返回结果后异步调用，不阻塞主流程。
     * </p>
     *
     * @param shopId 刚被查询过的店铺 ID
     */
    void triggerIfExpired(Long shopId);
}