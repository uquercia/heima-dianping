package com.hmdp.service;

import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 服务类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Shop getQueryById(Long id) throws InterruptedException;

    Boolean updateBusiness(Shop shop);

    Shop queryShopByIdWithMutex(Long id) throws InterruptedException;
}
