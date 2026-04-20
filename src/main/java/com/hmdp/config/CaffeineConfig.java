package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class CaffeineConfig {
//    商铺缓存 最多1000条，5分钟过期
    @Bean("shopCache")
    public Cache<Long , Shop> shopCache() {
        return Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(5 , TimeUnit.MINUTES)
                .recordStats()          //开启命中率统计
                .build();
    }

    // 商铺类型缓存：最多 100 条，写入后 10 分钟过期
    @Bean("shopTypeCache")
    public Cache<String, List<ShopType>> shopTypeCache() {
        return Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build();
    }

}
