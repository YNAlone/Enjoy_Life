package com.hmdp.canal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
//注册订阅
public class RedisSubscriberConfig {
    private static final String CHANNEL = "hmdp:cache:invalidate";

    @Bean
    public RedisMessageListenerContainer cacheInvalidateListenerContainer(
            RedisConnectionFactory factory,
            CacheInvalidateSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(subscriber, new PatternTopic(CHANNEL));
        return container;
    }
}
