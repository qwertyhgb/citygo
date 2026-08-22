package com.citygo.shop.controller;

import com.citygo.common.page.PageVO;
import com.citygo.common.result.Result;
import com.citygo.product.service.ProductService;
import com.citygo.product.vo.ProductVO;
import com.citygo.shop.dto.ShopBrowseQuery;
import com.citygo.shop.service.ShopBrowseService;
import com.citygo.shop.vo.ShopBrowseVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端店铺浏览接口（公开，无需登录）。
 */
@Tag(name = "店铺浏览", description = "用户端店铺列表/详情/店内商品")
@RestController
@RequestMapping("/api/shops")
public class ShopBrowseController {

    private final ShopBrowseService shopBrowseService;
    private final ProductService productService;

    public ShopBrowseController(ShopBrowseService shopBrowseService,
                                ProductService productService) {
        this.shopBrowseService = shopBrowseService;
        this.productService = productService;
    }

    /**
     * 店铺分页列表（城市/关键词筛选 + 白名单排序）。
     * 查询参数通过 @ModelAttribute 自动绑定到查询对象。
     */
    @Operation(summary = "店铺列表")
    @GetMapping
    public Result<PageVO<ShopBrowseVO>> list(@ModelAttribute ShopBrowseQuery query) {
        return Result.success(shopBrowseService.page(query));
    }

    /**
     * 店铺详情。
     */
    @Operation(summary = "店铺详情")
    @GetMapping("/{id}")
    public Result<ShopBrowseVO> detail(@PathVariable Long id) {
        return Result.success(shopBrowseService.getDetail(id));
    }

    /**
     * 店铺内商品分页（仅上架商品，库存脱敏）。
     */
    @Operation(summary = "店铺内商品")
    @GetMapping("/{id}/products")
    public Result<PageVO<ProductVO>> shopProducts(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        // 先校验店铺存在且正常，再按 shopId 分页查上架商品
        shopBrowseService.requireEnabledShop(id);
        return Result.success(productService.pagePublic(id, null, null, null, null,
                "default", pageNum, pageSize));
    }

}