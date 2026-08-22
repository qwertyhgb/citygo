package com.citygo.search.service;

import com.citygo.common.page.PageVO;
import com.citygo.product.entity.Product;
import com.citygo.product.vo.ProductVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品搜索服务接口（ES 搜索 + 索引同步）。
 */
public interface ProductSearchService {

    /**
     * 单商品索引同步（写成功后 by afterCommit 调用）。
     */
    void syncProduct(Product product);

    /**
     * 批量商品索引同步（下单后商品销量/库存变化时用）。
     */
    void syncProducts(List<Product> products);

    /**
     * 商品搜索：关键词多字段检索 + 分类/价格过滤 + 排序；返回 ProductVO（stock 脱敏）。
     */
    PageVO<ProductVO> searchProducts(String keyword, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice,
                                     String sort, long pageNum, long pageSize);

}