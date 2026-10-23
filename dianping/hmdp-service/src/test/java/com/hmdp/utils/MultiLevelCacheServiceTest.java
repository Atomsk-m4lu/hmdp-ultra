package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiLevelCacheServiceTest {

    private MultiLevelCacheService service;
    private ValueOperations<String, String> valueOps;
    private CacheClient cacheClient;
    private Cache<String, Object> localCache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        cacheClient = mock(CacheClient.class);
        localCache = Caffeine.newBuilder().maximumSize(100).build();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new MultiLevelCacheService();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "cacheClient", cacheClient);
        ReflectionTestUtils.setField(service, "shopLocalCache", localCache);
    }

    @Test
    void should_load_cold_key_and_fill_l1() {
        Shop shop = new Shop().setId(1L);
        when(valueOps.get("cache:shop:1")).thenReturn(null);
        when(cacheClient.<Shop, Long>loadOnMiss(
                eq("cache:shop:"), eq(1L), eq(Shop.class), any(), eq(30L), eq(TimeUnit.MINUTES)))
                .thenReturn(shop);

        Shop result = service.queryWithLocalCache("cache:shop:", 1L, Shop.class, id -> shop, 30L, TimeUnit.MINUTES);

        assertSame(shop, result);
        Object l1 = localCache.getIfPresent("cache:shop:1");
        assertNotNull(l1);
        assertSame(shop, ((RedisData) l1).getData());

        reset(valueOps);
        assertSame(shop, service.queryWithLocalCache("cache:shop:", 1L, Shop.class, id -> shop, 30L, TimeUnit.MINUTES));
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void should_return_null_for_negative_cache_without_db() {
        when(valueOps.get("cache:shop:2")).thenReturn("");
        AtomicBoolean dbCalled = new AtomicBoolean();

        Shop result = service.queryWithLocalCache("cache:shop:", 2L, Shop.class,
                id -> {
                    dbCalled.set(true);
                    return new Shop().setId(2L);
                }, 30L, TimeUnit.MINUTES);

        assertNull(result);
        assertFalse(dbCalled.get());
        verify(cacheClient, never()).loadOnMiss(anyString(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void should_return_l2_hit_and_fill_l1() {
        Shop shop = new Shop().setId(3L);
        RedisData cached = new RedisData();
        cached.setData(shop);
        cached.setExpireTime(LocalDateTime.now().plusMinutes(10));
        when(valueOps.get("cache:shop:3")).thenReturn(JSONUtil.toJsonStr(cached));

        Shop result = service.queryWithLocalCache("cache:shop:", 3L, Shop.class, id -> null, 30L, TimeUnit.MINUTES);

        assertNotNull(result);
        assertEquals(3L, result.getId());
        assertNotNull(localCache.getIfPresent("cache:shop:3"));
        verify(cacheClient, never()).loadOnMiss(anyString(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void should_delegate_to_logical_expire_when_l2_is_stale() {
        Shop oldShop = new Shop().setId(4L);
        RedisData stale = new RedisData();
        stale.setData(oldShop);
        stale.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(valueOps.get("cache:shop:4")).thenReturn(JSONUtil.toJsonStr(stale));
        Shop rebuilt = new Shop().setId(4L);
        when(cacheClient.<Shop, Long>queryWithLogicalExpire(
                eq("cache:shop:"), eq(4L), eq(Shop.class), any(), eq(30L), eq(TimeUnit.MINUTES)))
                .thenReturn(rebuilt);

        Shop result = service.queryWithLocalCache("cache:shop:", 4L, Shop.class, id -> rebuilt, 30L, TimeUnit.MINUTES);

        assertSame(rebuilt, result);
        verify(cacheClient).queryWithLogicalExpire(
                eq("cache:shop:"), eq(4L), eq(Shop.class), any(), eq(30L), eq(TimeUnit.MINUTES));
    }

    @Test
    void should_return_null_when_cold_load_finds_nothing() {
        when(valueOps.get("cache:shop:5")).thenReturn(null);
        when(cacheClient.<Shop, Long>loadOnMiss(
                eq("cache:shop:"), eq(5L), eq(Shop.class), any(), eq(30L), eq(TimeUnit.MINUTES)))
                .thenReturn(null);

        assertNull(service.queryWithLocalCache("cache:shop:", 5L, Shop.class, id -> null, 30L, TimeUnit.MINUTES));
        assertNull(localCache.getIfPresent("cache:shop:5"));
    }
}
