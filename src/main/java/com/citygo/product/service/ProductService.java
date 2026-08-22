package com.citygo.product.service;

import com.citygo.common.page.PageVO;
import com.citygo.product.dto.ProductCreateRequest;
import com.citygo.product.dto.ProductStatusRequest;
import com.citygo.product.dto.ProductStockRequest;
import com.citygo.product.dto.ProductUpdateRequest;
import com.citygo.product.vo.ProductVO;

/**
 * 商品域服务接口。
 */
public interface ProductService {

    /**
     * 创建商品（校验分类存在 + 店铺归属）。
     */
    ProductVO create(ProductCreateRequest request, Long currentUserId);

    /**
     * 更新商品（归属校验）。
     */
    ProductVO update(Long id, ProductUpdateRequest request, Long currentUserId);

    /**
     * 上架/下架（归属校验）。
     */
    void updateStatus(Long id, ProductStatusRequest request, Long currentUserId);

    /**
     * 改库存（归属校验）。
     */
    void updateStock(Long id, ProductStockRequest request, Long currentUserId);

    /**
     * 当前商家的商品分页列表（可选店铺/模糊商品名过滤，按 id 倒序）。
     */
    PageVO<ProductVO> pageMy(Long shopId, String keyword, long pageNum, long pageSize, Long currentUserId);

    /**
     * 公开商品分页查询（仅上架商品，支持店铺/分类/关键词/价格区间过滤与白名单排序；
     * 返回结果 stock 置 null 脱敏）。shopId 非空时校验店铺 status=1，否则抛 SHOP_NOT_FOUND。
     */
    PageVO<ProductVO> pagePublic(Long shopId, Long categoryId, String keyword,
                                 java.math.BigDecimal minPrice, java.math.BigDecimal maxPrice,
                                 String sort, long pageNum, long pageSize);

    /**
     * 公开商品详情（仅上架商品；不存在或下架抛 PRODUCT_NOT_FOUND；stock 置 null 脱敏）。
     */
    ProductVO getPublicDetail(Long id);

}