package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.google.common.base.Function;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class TwoLevelCacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public TwoLevelCacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /*
    * 二级缓存查询： L1 Caffeine ->  L2 Redis ->  DB
    * @param l1Cache   Caffeine 缓存实例
    * @param cacheKey  Redis key
    * @param id      业务ID（作为Caffeine key)
    * @param type    返回类型
    * @param dbFallback  查DB的函数
    * @param ttl   Redis TTL
    * @param unit   TTL单位
    * */

    public <R , ID> R query(Cache<ID , R> l1Cache,
                            String cachaeKey,
                            ID id,
                            Class<R> type,
                            Function<ID , R> dbFallback ,
                            Long ttl ,
                            TimeUnit unit) {
//           1. 查 L1 Caffeine
        R result = l1Cache.getIfPresent(id);
        if(result != null) {
            log.debug("L1 Caffeine 命中 , key = {}" , cachaeKey);
            return result;
        }
//          2.  查 L2 Redis
        String json = stringRedisTemplate.opsForValue().get(cachaeKey);
        if(StrUtil.isNotBlank(json)) {
            log.debug("L2 Redis 命中 ， key = {}" , cachaeKey);
            result = JSONUtil.toBean(json, type);
//            L1回写
            l1Cache.put(id, result);
            return result;
        }

//        3.防止缓存穿透 ， Reids缓存空值
        if(json != null) {
            json = null;
        }
//        4.查数据库
        result = dbFallback.apply(id);
        if(result == null) {
//            防穿透 写空值到 Redis（不写Caffeine  防止污染）
            stringRedisTemplate.opsForValue().set(cachaeKey, "", 2, TimeUnit.MINUTES);
            return null;
        }

//        5.写入 L2 Redis + L1 Caffeine
        stringRedisTemplate.opsForValue().set(cachaeKey, JSONUtil.toJsonStr(result), ttl, unit);
        l1Cache.put(id, result);
        log.debug("DB查询命中 并写入 L1 + L2 ， key = {}" , cachaeKey);
        return result;
    }

    /*
    * 删除二级缓存（Canal 触发失效时调用）
    * */
    public <ID> void invalidate(Cache<ID , ?> l1Cache , String redisKey , ID id ){
        l1Cache.invalidate(id);
        stringRedisTemplate.delete(redisKey);
        log.info("缓存失效： L1 invalidate id = {} , L2 delte key = {}" , id , redisKey);
    }
}
