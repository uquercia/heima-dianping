package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.utils.redis.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    //加final为了不被后续其他类修改
    private final StringRedisTemplate stringRedisTemplate;

    //不写resource注入 是为了让其不依赖String
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogic(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //永久过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
//    这边用R,ID的原因是因为不知道传过来的是什么参数 因此只能用泛型接收 <R,ID>的意思是泛型声明 意思是这个方法里会用到这两个泛型
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallBack, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //查缓存有就直接返回 没有就查数据库，再没有就添加空缓存
        String objectJson = stringRedisTemplate.opsForValue().get(key);
        if ("".equals(objectJson)) {
            log.debug("空缓存");
            return null;
        }
        if (StringUtils.hasText(objectJson)) {
            log.debug("数据库有缓存{}", objectJson);
            R r = JSONUtil.toBean(objectJson, type);
            return r;
        }
        //去数据库里面查 查到一个对象r
        R r = dbFallBack.apply(id);
        if (Objects.isNull(r)) {
            log.debug("数据库无该对象");
            this.set(key, "", time, unit);
            return null;
        }
        log.debug("数据库有该对象{}", JSONUtil.toJsonStr(r));
        this.set(key, r, time, unit);
        return r;
    }

    //创建线程池 用于提交任务 (Tomcat默认200线程池 如果创建线程池可以异步解耦 把风险关在小盒子里）
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题 - 这边加锁是因为要重建缓存
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockKeyPredix, ID id,
                                            Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 从Redis查询缓存
        String objectJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 处理缓存穿透的占位符
        if ("".equals(objectJson)) {
            log.debug("命中空值占位符，直接返回null");
            return null; // 这里直接截断，不再往下走逻辑过期的反序列化
        }
        // 3. 未命中缓存，直接返回防止击穿 (别的逻辑再添加)
        if (!StringUtils.hasText(objectJson)) {
            log.debug("<UNK>null");
            return null;
        }
        RedisData redisData = JSONUtil.toBean(objectJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        // 4.逻辑过期方案:没命中缓存 去查数据库
        if (LocalDateTime.now().isBefore(expireTime)) {
            // 4.1 未过期，直接返回
            log.debug("缓存没过期，返回数据");
            return r;
        }
        // 5. 已过期，开始重建缓存
        // 5.1 获取互斥锁
        String lockKey = lockKeyPredix + id;
        boolean isLock = tryLock(lockKey);
        // 5.2 判断锁是否成功
        if (isLock) {
            // 5.3 成功，开启独立线程重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 1. Double Check
                    String json = stringRedisTemplate.opsForValue().get(key);
                    RedisData latestData = JSONUtil.toBean(json, RedisData.class);
                    if (latestData.getExpireTime().isAfter(LocalDateTime.now())) {
                        return;
                    }

                    // 2. 查询数据库
                    R newR = dbFallback.apply(id);

                    // 3. 写入缓存（包含逻辑过期封装）
                    if (Objects.isNull(newR)) {
                        // 数据库也没有，存入空对象防穿透（此处建议给TTL）
                        stringRedisTemplate.opsForValue().set(key, "", 2L, TimeUnit.MINUTES);
                    } else {
                        this.setWithLogic(key, newR, time, unit);
                    }
                } catch (Exception e) {
                    log.error("缓存重建异常", e);
                } finally {
                    // 4. 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6. 无论抢锁成功与否，都先返回旧数据（逻辑过期的核心）
        return r;
    }

    //创建释放获取shop的互斥锁方法
    public <R, ID> R queryByIdWithMutex(String keyPrefix, String lockKeyPredix, ID id,
                                        Class<R> type, Function<ID, R> dbFallback,
                                        Long time, TimeUnit unit) throws InterruptedException {
        //先用key查缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //1缓存命中直接返回shop
        if (StringUtils.hasText(json)) {
            log.debug("缓存命中");
            return JSONUtil.toBean(json, type);
        }
        //2缓存穿透创建的空对象
        if ("".equals(json)) {
            return null;
        }
        //3此时创建互斥锁
        String lockKey = lockKeyPredix + id;
        Boolean isLock = tryLock(lockKey);
        //3.1 如果 lock创建失败 就等待几秒 然后重新调用方法
        if (!isLock) {
            Thread.sleep(100);
            return queryByIdWithMutex(keyPrefix, lockKeyPredix, id,type, dbFallback, time, unit);
        }
        // 3.2 lock创建成功 拿到缓存 返回之前校验 UUID(保证删的是自己的锁) 然后删除分布式锁
        try {
            // 【关键：双重检查】
            // 必须重新查一次 Redis，可能上个线程刚写完缓存并放锁，如果缓存有数据直接查数据库旧浪费了
            json = stringRedisTemplate.opsForValue().get(key);
            if (StringUtils.hasText(json)) {
                return JSONUtil.toBean(json, type);
            }
            if ("".equals(json)) {
                return null;
            }
            //数据库中拿shop 并且写入缓存
            R r = dbFallback.apply(id);
            if (Objects.isNull(r)) {
                log.debug("数据库无id：{}的shop", id);
                //存空值防删缓存
                stringRedisTemplate.opsForValue().set(key, "", time, unit);
                return r;
            }
            //shop存在则设缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), time, unit);
            return r;
        } catch (Exception e) {
            log.error("构建缓存异常", e);
            throw new RuntimeException("系统繁忙");
        } finally {
            //finally中必须释放锁
            Boolean unlock = unlock(lockKey);
            log.debug("锁释放情况为{}", BooleanUtil.isTrue(unlock));
        }
    }

    private Boolean tryLock(String lockKey) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "", 30, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private Boolean unlock(String lockKey) {
        Boolean flag = stringRedisTemplate.delete(lockKey);
        return BooleanUtil.isTrue(flag);
    }
}
