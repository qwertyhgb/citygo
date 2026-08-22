package com.citygo.search.controller;

import com.citygo.common.page.PageVO;
import com.citygo.common.result.Result;
import com.citygo.product.vo.ProductVO;
import com.citygo.search.service.ProductSearchService;
import com.citygo.search.service.ShopSearchService;
import com.citygo.shop.vo.ShopBrowseVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 全文搜索接口（公开，ES 检索）。
 */
@Tag(name = "搜索", description = "店铺/商品的 Elasticsearch 全文搜索")
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final ShopSearchService shopSearchService;
    private final ProductSearchService productSearchService;

    public SearchController(ShopSearchService shopSearchService, ProductSearchService productSearchService) {
        this.shopSearchService = shopSearchService;
        this.productSearchService = productSearchService;
    }

    /**
     * 店铺搜索：关键词 + 城市 + 距离过滤，评分/销量/距离排序。
     */
    @Operation(summary = "店铺搜索")
    @GetMapping("/shops")
    public Result<PageVO<ShopBrowseVO>> searchShops(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Double nearLat,
            @RequestParam(required = false) Double nearLng,
            @RequestParam(required = false, defaultValue = "5") Double distanceKm,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(shopSearchService.searchShops(
                keyword, city, nearLat, nearLng, distanceKm, sort, pageNum, pageSize));
    }

    /**
     * 商品搜索：关键词多字段 + 分类/价格过滤，销量/价格排序。
     */
    @Operation(summary = "商品搜索")
    @GetMapping("/products")
    public Result<PageVO<ProductVO>> searchProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(productSearchService.searchProducts(
                keyword, categoryId, minPrice, maxPrice, sort, pageNum, pageSize));
    }

}