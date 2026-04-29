package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.exception.BusinessException;
import com.hmdp.exception.LoginException;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.redis.RedisConstants;
import com.hmdp.utils.redis.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * 秒杀优惠券表，与优惠券是一对一关系 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {
    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Autowired
    private ApplicationContext applicationContext;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //创建一个线程池用来做异步秒杀的订单
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //创建全局代理对象 （因为异步处理解决transfaction要用。因为是异步因此异步的线程和主简称不一样 ，因此创建在全局）
    private ISeckillVoucherService proxy;

    //第一时间运行线程池
    @PostConstruct
    public void init() {
        // 初始化代理对象，确保异步线程能正确调用事务方法
        proxy = applicationContext.getBean(ISeckillVoucherService.class);
        log.info("代理对象初始化成功: {}", proxy.getClass().getName());
        //执行线程池中的run方法
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
        log.info("异步订单处理线程已启动");
    }

    //创建线程池的运行规则
    public class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            //异步下单
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("线程池异步处理订单异常", e);
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        log.info("异步线程开始处理订单: voucherId={}, userId={}, orderId={}", 
                voucherOrder.getVoucherId(), voucherOrder.getUserId(), voucherOrder.getId());
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock redisLock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + voucherOrder.getVoucherId());
        // 3.尝试获取锁
        boolean isLock = redisLock.tryLock(10, TimeUnit.SECONDS);
        log.info("异步线程获取锁结果: {}, lockKey={}", isLock, RedisConstants.LOCK_ORDER_KEY + voucherOrder.getVoucherId());
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("异步线程获取锁失败，订单处理中止: {}", voucherOrder);
            return;
        }
        try {
            log.info("准备调用 proxy.createVoucherOrder, proxy类型={}", proxy.getClass().getName());
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
            log.info("异步订单处理成功: orderId={}", voucherOrder.getId());
        } catch (Exception e) {
            log.error("异步订单处理失败: orderId={}, 异常信息: ", voucherOrder.getId(), e);
        } finally {
            // 释放锁
            redisLock.unlock();
            log.info("异步线程释放锁: lockKey={}", RedisConstants.LOCK_ORDER_KEY + voucherOrder.getVoucherId());
        }
    }

    @Transactional
    @Override
    public VoucherOrder seckillVoucher(Long voucherId) throws InterruptedException {

        //检查是否一人一单 然后扣减库存
        //拼接Keys seckill:stock:, voucherId:和参数 voucherId 和set<userId>
        //keys
        Long userId = UserHolder.getUser().getId();
        //orderId
        String seckillStockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        String seckillVoucherUserIdKey = RedisConstants.SECKILL_VOUCHER_USERID_KEY + voucherId;
        List<String> keyList = Arrays.asList(seckillStockKey, seckillVoucherUserIdKey);
        //执行编写完的lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                keyList,
                userId.toString()
        );
        if (result == 2) {
            throw new BusinessException("库存不足");
        }
        if (result == 1) {
            throw new BusinessException("同一用户下了两单");
        }
        if (result != 0) {
            throw new BusinessException("其他异常");
        }
        //result为0 有购买资格
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4.用户id
        voucherOrder.setUserId(userId);
        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        //剩下的就是result为0 代表着有秒杀资格//把抢到的订单都放入阻塞队列等待获取
        orderTasks.add(voucherOrder);
        return voucherOrder;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        // 5.一人一单逻辑
        // 5.1.用户id
        Long userId = voucherOrder.getUserId();
        if (userId == null) {
            throw new LoginException("用户没登陆");
        }
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        boolean isLock = lock.tryLock(RedisConstants.LOCK_ORDER_WAIT_TIME, TimeUnit.SECONDS);
        if (!isLock) {
            throw new BusinessException("获取锁失败");
        }
        log.debug("redisson获取锁");
        try {
            getVoucherOrder(voucherOrder.getVoucherId(), userId);
        } catch (Exception e) {
            log.error("下单失败：", e);
            throw new BusinessException("下单异常，请重试");
        } finally {
            log.debug("redisson释放锁");
            lock.unlock();
        }
    }

    @Override
    public VoucherOrder getVoucherOrder(Long voucherId, Long userId) {
        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(VoucherOrder::getVoucherId, voucherId).eq(VoucherOrder::getUserId, userId);
        // 5.2.判断是否存在
        if (Objects.nonNull(voucherOrderMapper.selectOne(queryWrapper))) {
            // 用户已经购买过了
            throw new BusinessException("用户已经购买过一次！");
        }
        //库存大于0时扣减 乐观锁应用
        LambdaUpdateWrapper<SeckillVoucher> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(SeckillVoucher::getVoucherId, voucherId)
                .setSql("stock = stock - 1")
                // 只有 isCheckStock 为 true 时，SQL 才会加上 "AND stock > 0"
                .gt(SeckillVoucher::getStock, 0);
        int update = seckillVoucherMapper.update(null, updateWrapper);
        if (update <= 0) {
            throw new BusinessException("seckillVoucher is not update");
        }
        //7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        voucherOrder.setUserId(userId);
        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        log.debug("创建新订单，扣减库存成功，订单为{}",voucherOrder);
        voucherOrderMapper.insert(voucherOrder);

        return voucherOrder;
    }
}
