package com.citygo.product.controller;

import com.citygo.common.page.PageVO;
import com.citygo.common.result.Result;
import com.citygo.product.dto.ProductBrowseQuery;
import com.citygo.product.service.ProductService;
import com.citygo.product.vo.ProductVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端商品浏览接口（公开，无需登录）。
 */
@Tag(name = "商品浏览", description = "用户端商品列表/详情")
@RestController
@RequestMapping("/api/products")
public class ProductBrowseController {

    private final ProductService productService;

    public ProductBrowseController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * 商品分页列表（分类/关键词/价格区间筛选 + 白名单排序；库存脱敏）。
     */
    @Operation(summary = "商品列表")
    @GetMapping
    public Result<PageVO<ProductVO>> list(@Valid @ModelAttribute ProductBrowseQuery query) {
        return Result.success(productService.pagePublic(
                query.getShopId(), query.getCategoryId(), query.getKeyword(),
                query.getMinPrice(), query.getMaxPrice(),
                query.getSort(), query.getPageNum(), query.getPageSize()));
    }

    /**
     * 商品详情（仅上架商品；库存脱敏）。
     */
    @Operation(summary = "商品详情")
    @GetMapping("/{id}")
    public Result<ProductVO> detail(@PathVariable Long id) {
        return Result.success(productService.getPublicDetail(id));
    }

}