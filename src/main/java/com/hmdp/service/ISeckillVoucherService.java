package com.hmdp.service;

import com.hmdp.entity.SeckillVoucher;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.VoucherOrder;

/**
 * 秒杀优惠券表，与优惠券是一对一关系 服务类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ISeckillVoucherService extends IService<SeckillVoucher> {
    VoucherOrder seckillVoucher(Long voucherId);

    VoucherOrder getVoucherOrder(Long voucherId, SeckillVoucher seckillVoucher,Long userId);
}
