package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
//        设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData),time,unit);
    }

    public <R,ID> R queryWithPassThrough(String ketPrefix , ID id, Class<R> type, Function<ID,R> dbFallback
    , Long time, TimeUnit unit) {
//        1.从Redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String json = stringRedisTemplate.opsForValue().get(key);
//        2.判断是否存在
        if(StrUtil.isNotBlank(json)) {
//        3.存在则直接返回
            return JSONUtil.toBean(json,type);
        }
        if(json != null) {
//            返回错误信息
            return null;
        }
//        4.不存在则查询数据库
        R r = dbFallback.apply(id);
//        5.数据库中不存在则返回错误
        if(r == null) {
//            将数据存储为空置置于redis中
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);

            return null ;
        }
//        6.数据库中存在则写入Redis，并返回
        this.set(key,r,time,unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(String keyPrefix , ID id,Class<R> type,Function<ID,R> dbFallback, Long time, TimeUnit unit) {
//        1.从Redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
//        2.判断是否存在
        if(StrUtil.isBlank(json)) {
//        3.存在则直接返回
            return null;
        }
//        4.命中则将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //        5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
//        5.1 未过期，则返回商铺信息
            return r;
        }
//        5.2 已经过期，需要缓存重建

//        6.缓存重建

//        6.1 获取互斥锁
        String  lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
//        6.2 判断是否获取锁成功
        if(isLock) {
//        6.3 获取锁成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try{
//                    重建缓存,首先查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入Redis
                    this.setWithLogicalExpire(key,r1,time,unit);

                } catch(Exception e) {
                    throw new RuntimeException(e);
                }finally{
//                    释放锁
                    unlock(lockKey);
                }
            });
        }

//        6.4失败则直接返回店铺信息
        return r;
    }

    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //    释放锁
    public void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
