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

}