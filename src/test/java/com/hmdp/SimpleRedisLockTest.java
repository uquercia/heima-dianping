package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class SimpleRedisLockTest {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void test() {
    }
}
