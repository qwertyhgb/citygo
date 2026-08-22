package com.citygo.shop.service;

import com.citygo.common.page.PageVO;
import com.citygo.shop.dto.ShopBrowseQuery;
import com.citygo.shop.vo.ShopBrowseVO;

/**
 * 用户端店铺浏览服务接口。
 */
public interface ShopBrowseService {

    /**
     * 公开分页查询店铺（仅 status=1，支持城市/关键词过滤与白名单排序）。
     */
    PageVO<ShopBrowseVO> page(ShopBrowseQuery query);

    /**
 * 店铺详情（仅 status=1，不存在抛 SHOP_NOT_FOUND）。
     */
    ShopBrowseVO getDetail(Long id);

    /**
     * 校验店铺存在且 status=1（供"店铺内商品"等组合查询使用），不满足抛 SHOP_NOT_FOUND。
     */
    void requireEnabledShop(Long shopId);

}