package com.citygo.product.controller;

import com.citygo.common.page.PageVO;
import com.citygo.common.result.Result;
import com.citygo.product.dto.ProductCreateRequest;
import com.citygo.product.dto.ProductStatusRequest;
import com.citygo.product.dto.ProductStockRequest;
import com.citygo.product.dto.ProductUpdateRequest;
import com.citygo.product.service.ProductService;
import com.citygo.product.vo.ProductVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.citygo.seckill.dto.SeckillConfigRequest;
import com.citygo.seckill.service.SeckillService;

/**
 * 商品管理接口（仅商家可用）。
 */
@Tag(name = "商品", description = "商家商品管理")
@RestController
@RequestMapping("/api/products")
@PreAuthorize("hasRole('MERCHANT')")
public class ProductController {

    private final ProductService productService;
    private final SeckillService seckillService;

    public ProductController(ProductService productService, SeckillService seckillService) {
        this.productService = productService;
        this.seckillService = seckillService;
    }

    /**
     * 商家配置秒杀。
     */
    @Operation(summary = "商家配置秒杀")
    @PostMapping("/{id}/seckill")
    public Result<ProductVO> configSeckill(@PathVariable Long id, @Valid @RequestBody SeckillConfigRequest request) {
        return Result.success(seckillService.configSeckill(id, request, currentUserId()));
    }

    /**
     * 创建商品。
     */
    @Operation(summary = "创建商品")
    @PostMapping
    public Result<ProductVO> create(@Valid @RequestBody ProductCreateRequest request) {
        return Result.success(productService.create(request, currentUserId()));
    }

    /**
     * 更新商品。
     */
    @Operation(summary = "更新商品")
    @PutMapping("/{id}")
    public Result<ProductVO> update(@PathVariable Long id, @Valid @RequestBody ProductUpdateRequest request) {
        return Result.success(productService.update(id, request, currentUserId()));
    }

    /**
     * 上架/下架。
     */
    @Operation(summary = "商品上下架")
    @PatchMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody ProductStatusRequest request) {
        productService.updateStatus(id, request, currentUserId());
        return Result.success();
    }

    /**
     * 修改库存。
     */
    @Operation(summary = "修改库存")
    @PatchMapping("/{id}/stock")
    public Result<Void> updateStock(@PathVariable Long id, @Valid @RequestBody ProductStockRequest request) {
        productService.updateStock(id, request, currentUserId());
        return Result.success();
    }

    /**
     * 我的商品分页列表（可选店铺/模糊商品名过滤）。
     */
    @Operation(summary = "我的商品")
    @GetMapping("/my")
    public Result<PageVO<ProductVO>> pageMy(
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(productService.pageMy(shopId, keyword, pageNum, pageSize, currentUserId()));
    }

    /**
     * 从 SecurityContext 取出当前登录用户的 userId。
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

}