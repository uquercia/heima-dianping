package com.hmdp.service.impl;

import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.redis.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

/**
 * 服务实现类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopService iShopService;
    @Resource
    private ShopMapper shopMapper;
    @Resource
    private CacheClient cacheClient;

    /**
     * 按id查询数据库
     *
     * @param id 身份标识
     * @return Shop对象
     */
    @Override
    public Shop getQueryById(Long id) throws InterruptedException {
        String key = RedisConstants.CACHE_SHOP_KEY;
        String lockKey=RedisConstants.LOCK_SHOP_KEY;
        //存空值仿缓存穿透 非逻辑删除redis
//        Shop shop = cacheClient.queryWithPassThrough(key, id, Shop.class,
//                shopMapper::selectById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);

        //防缓存穿透，用的逻辑删除redis
//        Shop shop = cacheClient.queryWithLogicalExpire(key, lockKey, id, Shop.class,
//                shopMapper::selectById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);

        //互斥锁
        Shop shop = cacheClient.queryByIdWithMutex(key, lockKey, id,Shop.class,
                shopMapper::selectById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        return shop;
    }

    //暂时不用这个接口
    public Shop queryShopByIdWithMutex(Long id){
        return null;
    }

    @Override
    public Boolean updateBusiness(Shop shop) {
        //这边拆update和缓存的意思是 ： 万一事务提交失败回滚了，缓存却已经被你删了。这会导致下一次请求被迫去查数据库
        Boolean update = update(shop);
        //更新成功之后删缓存 并且创建新缓存
        if (update) {
            log.debug("开始删除缓存");
            String key = RedisConstants.CACHE_SHOP_KEY + shop.getId();
            //这边删除缓存就可以了 创建新缓存等查询的时候再创建 不然线程A删除缓存再创建缓存 中间的时间窗口就会给别的线程闯入的机会
//            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.LOGIN_USER_TTL,TimeUnit.SECONDS);
            log.debug("删除缓存成功", shop);
        } else {
            log.debug("删除缓存失败");
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public Boolean update(Shop shop) {
        return this.updateById(shop);
    }
}
