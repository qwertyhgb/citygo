package com.citygo.merchant.service;

import com.citygo.merchant.dto.MerchantRegisterRequest;
import com.citygo.merchant.entity.Merchant;
import com.citygo.user.vo.UserVO;

/**
 * 商家域服务接口。
 */
public interface MerchantService {

    /**
     * 商家注册：创建 user 账号（绑定 MERCHANT 角色）与 merchant 商家资料，返回用户视图。
     */
    UserVO registerMerchant(MerchantRegisterRequest request);

    /**
     * 按用户ID查询商家；不存在返回 null。
     */
    Merchant getByUserId(Long userId);

    /**
     * 按商家ID查询商家；不存在返回 null。
     */
    Merchant getById(Long id);

}