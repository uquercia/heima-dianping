package com.hmdp;

import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

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
}
