package com.hmdp;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Slf4j
@SpringBootTest
public class testRedission {
    @Resource
    private RedissonClient redissonClient;

    @Test
    public void test() throws InterruptedException {
        RLock lock = redissonClient.getLock("anyLock");
        boolean b = lock.tryLock(1, 20, TimeUnit.SECONDS);
        if (!b) {
            System.out.println("获取锁失败");
            return;
        }
        try {
            System.out.println("开始执行任务");
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            System.out.println("释放锁");
            lock.unlock();
        }
    }

    @Test
    void testReentrantLock() {
        RLock lock = redissonClient.getLock("test:reentrant");
        try {
            // 第一次获取锁
            boolean isLock1 = lock.tryLock();
            if (isLock1) {
                log.info("第一次加锁成功");
                try {
                    // 在同一个线程中，第二次尝试获取同一把锁
                    boolean isLock2 = lock.tryLock();
                    if (isLock2) {
                        log.info("第二次加锁成功（触发重入）");
                        // 此时去 Redis 查看，该 Hash 的 value 应该是 2
                    }
                } finally {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

}
