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
import com.hmdp.utils.redis.SimpleRedisLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀优惠券表，与优惠券是一对一关系 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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
    private SimpleRedisLock simpleRedisLock;
    @Resource
    private RedissonClient redissonClient;

    @Transactional
    @Override
    public VoucherOrder seckillVoucher(Long voucherId) throws InterruptedException {
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(voucherId);
        if (Objects.isNull(seckillVoucher)) {
            log.debug("seckillVoucher is null");
            return null;
        }
        //time
        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime()) || LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) {
            throw new BusinessException("优惠券购买时间过期");
        }
        // 4.判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            // 库存不足
            throw new BusinessException("库存不足！");
        }
        // 5.一人一单逻辑
        // 5.1.用户id
        Long userId = UserHolder.getUser().getId();
        if (userId == null) {
            throw new LoginException("用户没登陆");
        }
//旧写法 -无法分布式上锁 只能保证单个jvm上锁
//        // 【核心 1】先加锁，保证锁的范围比事务大
//        synchronized (userId.toString().intern()) {
//            //把自带的aop代理对象 注入spring 这样就可以在代理对象上加锁 让事务生效
//            ISeckillVoucherService proxy = (ISeckillVoucherService) AopContext.currentProxy();
//            return proxy.getVoucherOrder(voucherId, seckillVoucher, userId);
//        }

        //上分布式锁 - 为每个用户创建独立的锁
//        boolean isLock = simpleRedisLock.tryLock(120L);
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        boolean isLock = lock.tryLock(RedisConstants.LOCK_ORDER_WAIT_TIME, RedisConstants.LOCK_ORDER_LEASE_TIME, TimeUnit.SECONDS);
        if (!isLock) {throw new BusinessException("获取锁失败");}
        log.debug("redisson获取锁");
        try{
            //把自带的aop代理对象 注入spring 这样就可以在代理对象上加锁 让事务生效
            ISeckillVoucherService proxy = (ISeckillVoucherService) AopContext.currentProxy();
            return proxy.getVoucherOrder(voucherId, seckillVoucher, userId);
        }catch (Exception e){
            log.error("下单失败：", e);
            throw new BusinessException("下单异常，请重试");
        }finally {
            log.debug("redisson释放锁");
            lock.unlock();
        }
    }
    public VoucherOrder getVoucherOrder(Long voucherId, SeckillVoucher seckillVoucher, Long userId) {
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
        int update = seckillVoucherMapper.update(seckillVoucher, updateWrapper);
        if (update < 0) {
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
        voucherOrderMapper.insert(voucherOrder);

        return voucherOrder;
    }
}
