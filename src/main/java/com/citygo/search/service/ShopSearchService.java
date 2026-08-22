package com.citygo.search.service;

import com.citygo.common.page.PageVO;
import com.citygo.merchant.entity.Shop;
import com.citygo.shop.vo.ShopBrowseVO;

/**
 * 店铺搜索服务接口（ES 搜索 + 索引同步）。
 */
public interface ShopSearchService {

    /**
     * 店铺索引同步（写成功后由业务层 afterCommit 调用）。
     */
    void syncShop(Shop shop);

    /**
     * 店铺搜索：关键词全文检索 + 城市过滤 + 评分/销量/距离排序 + 附近距离过滤。
     * ES 异常时降级返回空列表。
     */
    PageVO<ShopBrowseVO> searchShops(String keyword, String city, Double nearLat, Double nearLng,
                                     Double distanceKm, String sort, long pageNum, long pageSize);

}