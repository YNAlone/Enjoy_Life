package com.hmdp.canal;

import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class CacheInvalidatePublisher {
    private static final String CHANNEL = "hmdp:cache:invalidate";
    private final StringRedisTemplate stringRedisTemplate;
    public CacheInvalidatePublisher(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void publish(CacheInvalidateMessage message) {
        stringRedisTemplate.convertAndSend(CHANNEL, JSONUtil.toJsonStr(message));
    }
}
