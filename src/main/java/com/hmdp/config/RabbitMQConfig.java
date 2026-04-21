package com.hmdp.config;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class RabbitMQConfig {
    @Resource
    IVoucherOrderService voucherOrderService;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    private static final String MSG_ID_PREFIX = "mq:msg:id:";

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "direct.seckill.queue"),
            key = "direct.seckill",
            exchange = @Exchange(name = "hmdianping.direct" , type = ExchangeTypes.DIRECT)
    ))
    public void receiveMessage(Message message, Channel channel, VoucherOrder voucherOrder) throws IOException {
        String messageId = message.getMessageProperties().getMessageId();
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        // 利用 MessageID 幂等性检查：SET NX 保证只有第一次消费成功写入
        Boolean isFirstConsume = stringRedisTemplate.opsForValue()
                .setIfAbsent(MSG_ID_PREFIX + messageId, "1", 24, TimeUnit.HOURS);

        if (Boolean.FALSE.equals(isFirstConsume)) {
            log.warn("重复消息，已忽略，messageId={}", messageId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            voucherOrderService.handleVoucherOrder(voucherOrder);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 处理失败，删除 Redis 标记，允许重试
            stringRedisTemplate.delete(MSG_ID_PREFIX + messageId);
            channel.basicNack(deliveryTag, false, true);
            log.error("消息处理失败，messageId={}", messageId, e);
        }
    }

    @Bean
    public MessageConverter messageConverter(){
//          1.定义消息转换器
        Jackson2JsonMessageConverter jjmc = new Jackson2JsonMessageConverter();
//        2.配置自动创建消息id，用于识别不同消息，也可以在业务中基于ID判断是否是重复消息
        jjmc.setCreateMessageIds(true);
        return jjmc;
    }
}
