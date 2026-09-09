package com.citygo.product.controller;

import com.citygo.common.annotation.RateLimit;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
        return Result.success(productService.pagePublic(query));
    }

    /**
     * 热门商品（销量 top N，手写缓存三防；库存脱敏）。
     * 加 @RateLimit 作为限流示例：固定窗口 1 秒内最多 5 次，超限返回 429，便于测试触发。
     */
    @Operation(summary = "热门商品")
    @GetMapping("/hot")
    @RateLimit(limit = 5, windowSeconds = 1)
    public Result<List<ProductVO>> hot(@RequestParam(defaultValue = "10") int limit) {
        return Result.success(productService.getHotProducts(limit));
    }

    /**
     * 秒杀商品列表（已配置秒杀价且上架的商品，按开始时间升序）。
     * 与 GET /{id} 不冲突：Spring 路由匹配时字面量路径优先于路径变量。
     */
    @Operation(summary = "秒杀商品列表")
    @GetMapping("/seckill")
    public Result<List<ProductVO>> seckillList() {
        return Result.success(productService.getSeckillProducts());
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