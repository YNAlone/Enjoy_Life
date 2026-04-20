package com.hmdp.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.List;

@Component
@Slf4j
public class CanalClient implements InitializingBean , DisposableBean {
    @Value("${canal.host}")
    private String CANAL_HOST;
    @Value("${canal.port}")
    private int CANAL_PORT = 11111;
    @Value("${canal.destination}")
    private String DESTINATION = "hmdp";
    private static final int BATCH_SIZE = 100;

    private final CacheInvalidatePublisher publisher;
    private final StringRedisTemplate stringRedisTemplate;

    private CanalConnector connector;
    private volatile boolean running = true;
    private Thread canalThread;

    public CanalClient(CacheInvalidatePublisher publisher,
                       StringRedisTemplate stringRedisTemplate) {
        this.publisher = publisher;
        this.stringRedisTemplate = stringRedisTemplate;
    }


    @Override
    public void destroy() throws Exception {
        running = false;
        if(canalThread != null) {
            canalThread.interrupt();
        }
        if(connector != null) {
            connector.disconnect();
        }
        log.info("Canal 客户端已关闭");
    }

    @Override
    public void afterPropertiesSet() {
        try {
            connector = CanalConnectors.newSingleConnector(
                    new InetSocketAddress(CANAL_HOST, CANAL_PORT),
                    DESTINATION, "", "");
            connector.connect();
            connector.subscribe("hmdp\\.tb_shop,hmdp\\.tb_shop_type");
            connector.rollback();

            canalThread = new Thread(this::process, "canal-client-thread");
            canalThread.setDaemon(true);
            canalThread.start();
            log.info("Canal 客户端启动成功");
        } catch (Exception e) {
            log.warn("Canal Server 连接失败，缓存同步不可用，请确认 Canal Server 已启动: {}", e.getMessage());
        }
    }

    private void process() {
        while(running) {
            try {
                Message message = connector.getWithoutAck(BATCH_SIZE);
                long batchId = message.getId();
                if(batchId == -1 || message.getEntries().isEmpty()) {
                    Thread.sleep(500);
                    continue;
                }
                handleEntries(message.getEntries());
                connector.ack(batchId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch(Exception e) {
                log.error("Canal 消费异常" , e);
                connector.rollback();
            }
        }
    }

    private void handleEntries(List<CanalEntry.Entry> entries) {
        for(CanalEntry.Entry entry : entries) {
//            只处理行数据变更，忽略事务开始/提交
            if(entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }
            CanalEntry.Header header = entry.getHeader();
            String tableName = header.getTableName();
            String eventType = header.getEventType().toString();  //INSERT/UPDATE/DELETE

            try{
                CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(
                        entry.getStoreValue());
                handleRowChange(tableName, eventType, rowChange);
            } catch (InvalidProtocolBufferException e) {
                log.error("解析 RowChange 失败 ， table = {}" , tableName ,e);
            }

        }
    }

    private void handleRowChange(String tableName, String eventType, CanalEntry.RowChange rowChange) {
        for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
            // UPDATE/DELETE 从 beforeColumns 取 id；INSERT 从 afterColumns 取 id
            List<CanalEntry.Column> columns = eventType.equals("DELETE")
                    ? rowData.getBeforeColumnsList()
                    : rowData.getAfterColumnsList();

            Long id = extractId(columns);
            if (id == null) {
                log.warn("无法提取 id, table={}, eventType={}", tableName, eventType);
                continue;
            }

            log.info("Canal 监听到变更: table={}, eventType={}, id={}", tableName, eventType, id);

            // 1. 删除 Redis 缓存
            invalidateRedis(tableName, id);

            // 2. 通过 Pub/Sub 广播，让所有节点清除 Caffeine
            publisher.publish(new CacheInvalidateMessage(tableName, id, eventType));
        }

    }

    private void invalidateRedis(String tableName, Long id) {
        switch (tableName) {
            case "tb_shop":
                stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
                break;
            case "tb_shop_type":
//                由于shop_type缓存的是整个表所以直接删除
                stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_LIST_KEY);
                break;
        }
    }

    private Long extractId(List<CanalEntry.Column> columns) {
        return columns.stream()
                .filter(col -> "id".equals(col.getName()))
                .map(col -> Long.parseLong(col.getValue()))
                .findFirst()
                .orElse(null);
    }
}
