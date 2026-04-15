package com.hmdp.utils.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;
@Slf4j
@Component
public class SimpleRedisLock {
@Resource
private StringRedisTemplate stringRedisTemplate;

private static final String KEY_PREFIX="lock:";
private String name;

    public SimpleRedisLock() {}
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    public boolean tryLock(Long timeout){
        // 获取线程标示（作为 value，用于防止误删）
        String threadId = String.valueOf(Thread.currentThread().getId());
        String lockKey = KEY_PREFIX + name;
        log.debug("锁名:{}",lockKey);
        //获取锁
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, threadId, timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(b);
    }
    public boolean unlock(){
        String lockKey = KEY_PREFIX + name;
        //  应该添加检查，只删除自己的锁（比对 value 是否等于当前线程ID，防止误删）
        Boolean b = stringRedisTemplate.delete(lockKey);
        return Boolean.TRUE.equals(b);
    }
}
