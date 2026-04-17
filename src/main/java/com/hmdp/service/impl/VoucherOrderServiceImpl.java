package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

//    注入RabbitMQ对象

    @Resource
    private RabbitTemplate rabbitTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }



    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

/*    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }*/

/*    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
//                1.获取消息队列中的订单信息
                List<MapRecord<String,Object,Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1","c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(queueName, ReadOffset.lastConsumed())
                );
//                2.判断消息是否获取成功
                if(list ==null || list.isEmpty()) {
//                2.1 获取失败继续下一次
                    continue;
                }
//                3.解析消息中的订单
                MapRecord<String,Object,Object> record = list.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values,new VoucherOrder(),true);

//                4.获取成功则下单
                handleVoucherOrder(voucherOrder);
//                5.ACK确认
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    try {
                        handlePandingList();
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }

        private void handlePandingList() throws InterruptedException {
            while (true) {
                try {
//                1.获取pending-list队列中的订单信息
                    List<MapRecord<String,Object,Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
//                2.判断消息是否获取成功
                    if(list ==null || list.isEmpty()) {
//                2.1 获取失败说明pending-list无异常消息，结束循环
                        break;
                    }
//                3.解析消息中的订单
                    MapRecord<String,Object,Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values,new VoucherOrder(),true);

//                4.获取成功则下单
                    handleVoucherOrder(voucherOrder);
//                5.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    Thread.sleep(20);
                }
            }

        }
    }*/

//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
////                1.获取队列中的订单信息
//                try {
//                    VoucherOrder voucherOrder = orderTasks.take();
////                2.创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//    }

    private IVoucherOrderService proxy;

    public void handleVoucherOrder(VoucherOrder voucherOrder) {
//        1.获取用户
        Long userId = voucherOrder.getUserId();
//        2.创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        3.获取锁
        boolean isLock = lock.tryLock();
//        4.判断锁是否获取成功
        if (!isLock) {
//            获取锁失败
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
//           释放锁
            lock.unlock();
        }
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
////          获取用户
//        Long userId = UserHolder.getUser().getId();
//        Long orderId = redisIdWorker.nextId("order");
//        //        1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString(), String.valueOf(orderId)
//        );
////        2.判断结果是否为0
//        int r = result.intValue();
//        //        2.1 不为0  无购买资格
//        if (r != 0) {
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//
////        3.获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
////        4.返回订单id
//        return Result.ok(orderId);
//    }


/*
*   使用消息队列实现异步下单，首先调用Lua脚本判断是否有购买资格
*   有资格则返回0 ， 将订单信息存入消息队列
*   等待消息
* */
    @Override
    public Result seckillVoucher(Long voucherId){
        //1.执行lua脚本，判断当前用户的购买资格
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        if (result != 0) {
            //2.不为0说明没有购买资格
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }
//        3.有购买资格，将订单存入消息队列
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
//        存入消息队列等待异步消费
        rabbitTemplate.convertAndSend("hmdianping.direct" , "direct.sckill"  , voucherOrder);
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
////          获取用户
//        Long userId = UserHolder.getUser().getId();
//        //        1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
////        2.判断结果是否为0
//        int r = result.intValue();
//        //        2.1 不为0  无购买资格
//        if (r != 0) {
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
////        2.2 为0 有购买资格，将下单信息保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
////        2.3 订单id
//        Long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
////        2.4 用户id
//        voucherOrder.setUserId(userId);
////        2.5 代金券id
//        voucherOrder.setVoucherId(voucherId);
////        2.6 放入阻塞队列
//        orderTasks.add(voucherOrder);
////        3.获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
////        4.返回订单id
//        return Result.ok(orderId);
//    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
////        1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
////        2.判断秒杀是否开启
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
////            秒杀尚未开始
//            return Result.fail("秒杀尚未开始");
//        }
////        3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
////            秒杀已结束
//            return Result.fail("秒杀已结束");
//        }
////        4.判断库存是否充足
//        if (voucher.getStock() < 1) {
////            库存不足
//            return Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
////创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
////        获取锁
//        boolean isLock = lock.tryLock();
////        判断锁是否获取成功
//        if (!isLock) {
////            获取锁失败
//            return Result.fail("请勿重复下单");
//        }
//        try {
////            获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally{
////           释放锁
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //        5.一人一单
        Long userId = voucherOrder.getUserId();

        //        5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            //            用户已经购买过该代金券
            log.error("用户已经购买过一次");
            return;
        }
        //        6.扣除库存
        //        使用乐观锁逻辑，在扣除库存时检查库存数量是否被修改过 由于乐观锁的特性，这样设置会导致多次检测的异常情况，库存售卖不出去
        //        进行修改，只要库存大于0即可售卖
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            //            库存不足
            log.error("库存不足");
            return;
        }
        //        7.创建订单
        save(voucherOrder);
    }
}
