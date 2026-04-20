package com.hmdp.canal;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

@Component
@Slf4j
public class CacheInvalidateSubscriber implements MessageListener {

    @Resource(name = "shopCache")
    private Cache<Long , Shop> shopCache;

    @Resource
    private Cache<String , List<ShopType>> shopTypeCache;
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody());
        CacheInvalidateMessage msg = JSONUtil.toBean(body, CacheInvalidateMessage.class);
        log.info("收到缓存失效通知：{} " , msg);
        switch (msg.getTableName()){
            case "tb_shop":
                if(msg.getId() != null) {
                    shopCache.invalidate(Long.valueOf(msg.getId()));
                } else {
                    shopCache.invalidateAll();
                }
                break;
            case "tb_shop_type":
                // shop_type 以整个列表为单位缓存，任何一条变更都要清空整个列表
                shopTypeCache.invalidateAll();
                break;
            default:
                log.warn("未处理的表: {}", msg.getTableName());
        }
    }
}
