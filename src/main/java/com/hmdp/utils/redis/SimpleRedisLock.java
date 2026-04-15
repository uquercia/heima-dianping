package com.hmdp.utils.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class SimpleRedisLock {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";
    private String name;

    public boolean tryLock(Long timeout) {
        // 获取线程标示（作为 value，用于防止误删）
        String threadId = String.valueOf(Thread.currentThread().getId());
        String lockKey = KEY_PREFIX + threadId;
        log.debug("锁名:{}", lockKey);
        //获取锁 里面存了threadId
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, threadId, timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(b);
    }

    //java中使用Lua 脚本
    private static final DefaultRedisScript<Long> defaultRedisScript;
    static String UNLOCK_SCRIPT = "unlock.lua";

    static {
        defaultRedisScript = new DefaultRedisScript<>();
        //ClassPathResource 去/src/main/resource下面找资源
        defaultRedisScript.setLocation(new ClassPathResource(UNLOCK_SCRIPT));
        defaultRedisScript.setResultType(Long.class);
    }

    public boolean unlock() {
        String threadId = String.valueOf(Thread.currentThread().getId());
        String lockKey = KEY_PREFIX + threadId;
        //执行lua脚本
        // 调用lua脚本
        Long b = stringRedisTemplate.execute(
                defaultRedisScript,
                Collections.singletonList(KEY_PREFIX + threadId),
                threadId + Thread.currentThread().getId());

//        String threadId = String.valueOf(Thread.currentThread().getId());
//        String lockKey = KEY_PREFIX + threadId;
//        //  应该添加检查，只删除自己的锁（比对 value 是否等于当前线程ID，防止误删）
//        String lockInfo = stringRedisTemplate.opsForValue().get(lockKey);
//        if (!StringUtils.hasText(lockInfo)) {
//            log.debug("没有该锁");
//            return false;
//        }
//        if (!lockInfo.equals(threadId)) {
//            log.debug("锁线程不同");
//            return false;
//        }
//        Boolean b = stringRedisTemplate.delete(lockKey);
//        return Boolean.TRUE.equals(b);
        if (b == null) {return Boolean.FALSE;}else return Boolean.TRUE.equals(b);
    }
}
