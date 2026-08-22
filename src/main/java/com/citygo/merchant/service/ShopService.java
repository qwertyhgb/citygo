package com.citygo.merchant.service;

import com.citygo.merchant.dto.ShopCreateRequest;
import com.citygo.merchant.dto.ShopOpenStatusRequest;
import com.citygo.merchant.dto.ShopUpdateRequest;
import com.citygo.merchant.vo.ShopVO;

import java.util.List;

/**
 * 店铺域服务接口。
 */
public interface ShopService {

    /**
     * 创建店铺（归属当前商家）。
     */
    ShopVO create(ShopCreateRequest request, Long currentUserId);

    /**
     * 更新店铺（归属校验：只能改自己的店铺）。
     */
    ShopVO update(Long id, ShopUpdateRequest request, Long currentUserId);

    /**
     * 切换营业状态（归属校验）。
     */
    void updateOpenStatus(Long id, ShopOpenStatusRequest request, Long currentUserId);

    /**
     * 查询当前商家的全部店铺列表（按 id 倒序）。
     */
    List<ShopVO> listMy(Long currentUserId);

    /**
     * 按店铺ID查询其归属商家ID；店铺不存在返回 null。
     */
    Long getMerchantIdOfShop(Long shopId);

}