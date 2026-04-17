package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

@SpringBootTest
class SeckillPerformanceBenchmarkTests {

    private static final Long VOUCHER_ID = 1L;
    private static final int STOCK = 500;
    private static final int REQUEST_COUNT = 5000;
    private static final int THREAD_COUNT = 300;
    private static final long USER_ID_BASE = 2_000_000L;
    private static final int HIGH_REQUEST_COUNT = 10000;
    private static final int HIGH_THREAD_COUNT = 500;
    private static final int HIGH_ROUNDS = 5;

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void benchmarkSeckillDbVsRedis() throws InterruptedException {
        long dbCost = runDbBaseline(REQUEST_COUNT, THREAD_COUNT, STOCK, USER_ID_BASE);
        int dbSuccess = queryOrderCount();

        long redisCost = runRedisLuaStream(REQUEST_COUNT, THREAD_COUNT, STOCK, USER_ID_BASE);
        waitAsyncOrderDone(30);
        int redisSuccess = queryOrderCount();

        double improvement = dbCost == 0 ? 0 : (dbCost - redisCost) * 100.0 / dbCost;
        String report = "================ 秒杀性能对比结果 ================\n"
                + "请求数: " + REQUEST_COUNT + ", 库存: " + STOCK + ", 并发线程: " + THREAD_COUNT + "\n"
                + "传统数据库方案耗时(ms): " + dbCost + ", 成功单数: " + dbSuccess + "\n"
                + "Redis+Lua+Stream方案耗时(ms): " + redisCost + ", 成功单数: " + redisSuccess + "\n"
                + String.format("性能提升: %.2f%%\n", improvement)
                + "===============================================\n";
        System.out.print(report);
        writeReport(report);
    }

    @Test
    void benchmarkSeckillDbVsRedisHighConcurrencyMultiRounds() throws InterruptedException {
        long totalDbCost = 0;
        long totalRedisCost = 0;
        double totalImprovement = 0;
        StringBuilder details = new StringBuilder();

        for (int i = 1; i <= HIGH_ROUNDS; i++) {
            long roundUserBase = USER_ID_BASE + i * 1_000_000L;
            long dbCost = runDbBaseline(HIGH_REQUEST_COUNT, HIGH_THREAD_COUNT, STOCK, roundUserBase);
            int dbSuccess = queryOrderCount();

            long redisCost = runRedisLuaStream(HIGH_REQUEST_COUNT, HIGH_THREAD_COUNT, STOCK, roundUserBase);
            waitAsyncOrderDone(30);
            int redisSuccess = queryOrderCount();

            double improvement = dbCost == 0 ? 0 : (dbCost - redisCost) * 100.0 / dbCost;
            totalDbCost += dbCost;
            totalRedisCost += redisCost;
            totalImprovement += improvement;

            details.append(String.format("第%d轮: DB=%dms(%d单), Redis=%dms(%d单), 提升=%.2f%%\n",
                    i, dbCost, dbSuccess, redisCost, redisSuccess, improvement));
        }

        double avgDbCost = totalDbCost * 1.0 / HIGH_ROUNDS;
        double avgRedisCost = totalRedisCost * 1.0 / HIGH_ROUNDS;
        double avgImprovement = totalImprovement / HIGH_ROUNDS;

        String report = "=========== 高并发多轮秒杀性能对比结果 ===========\n"
                + "请求数/轮: " + HIGH_REQUEST_COUNT + ", 库存: " + STOCK + ", 并发线程: " + HIGH_THREAD_COUNT + ", 轮次: "
                + HIGH_ROUNDS + "\n"
                + details
                + String.format("平均DB耗时(ms): %.2f\n", avgDbCost)
                + String.format("平均Redis耗时(ms): %.2f\n", avgRedisCost)
                + String.format("平均性能提升: %.2f%%\n", avgImprovement)
                + "===============================================\n";
        System.out.print(report);
        writeReport("seckill-benchmark-high.txt", report);
    }

    private long runDbBaseline(int requestCount, int threadCount, int stock, long userIdBase)
            throws InterruptedException {
        prepareBenchmarkData(stock);
        return runConcurrentRequests(requestCount, threadCount, offset -> {
            VoucherOrder order = new VoucherOrder();
            order.setId(redisIdWorker.nextId("order"));
            order.setVoucherId(VOUCHER_ID);
            order.setUserId(userIdBase + offset);
            voucherOrderService.createVoucherOrder(order);
        });
    }

    private long runRedisLuaStream(int requestCount, int threadCount, int stock, long userIdBase)
            throws InterruptedException {
        prepareBenchmarkData(stock);
        createStreamGroupIfAbsent();
        return runConcurrentRequests(requestCount, threadCount, offset -> {
            UserDTO userDTO = new UserDTO();
            userDTO.setId(userIdBase + offset);
            UserHolder.saveUser(userDTO);
            try {
                Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
                if (!result.getSuccess()) {
                    return;
                }
            } finally {
                UserHolder.removeUser();
            }
        });
    }

    private long runConcurrentRequests(int requestCount, int threadCount, LongConsumer task)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(requestCount);
        long begin = System.currentTimeMillis();
        for (int i = 0; i < requestCount; i++) {
            long offset = i;
            pool.submit(() -> {
                try {
                    task.accept(offset);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        long cost = System.currentTimeMillis() - begin;
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
        return cost;
    }

    private void prepareBenchmarkData(int stock) {
        jdbcTemplate.update(
                "INSERT INTO tb_seckill_voucher(voucher_id, stock, begin_time, end_time) VALUES (?, ?, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY)) ON DUPLICATE KEY UPDATE stock = VALUES(stock), begin_time = VALUES(begin_time), end_time = VALUES(end_time)",
                VOUCHER_ID, stock);
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", VOUCHER_ID);

        String stockKey = "seckill:stock:" + VOUCHER_ID;
        String orderKey = "seckill:order:" + VOUCHER_ID;
        stringRedisTemplate.delete(stockKey);
        stringRedisTemplate.delete(orderKey);
        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(stock));
    }

    private void createStreamGroupIfAbsent() {
        String streamKey = "stream.orders";
        try {
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(streamKey))) {
                stringRedisTemplate.opsForStream().add(streamKey, java.util.Collections.singletonMap("init", "0"));
            }
            stringRedisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), "g1");
        } catch (Exception ignored) {
        }
    }

    private void waitAsyncOrderDone(int timeoutSeconds) throws InterruptedException {
        long endAt = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < endAt) {
            int count = queryOrderCount();
            if (count >= STOCK) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private int queryOrderCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM tb_voucher_order WHERE voucher_id = ?",
                Integer.class,
                VOUCHER_ID);
        return count == null ? 0 : count;
    }

    private void writeReport(String report) {
        writeReport("seckill-benchmark.txt", report);
    }

    private void writeReport(String fileName, String report) {
        try {
            Path output = Paths.get("target", fileName);
            Files.write(output, report.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("写入压测报告失败", e);
        }
    }
}
