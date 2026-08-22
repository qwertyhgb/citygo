package com.citygo.order.controller;

import com.citygo.common.page.PageVO;
import com.citygo.common.result.Result;
import com.citygo.order.service.OrderService;
import com.citygo.order.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家订单接口（需 MERCHANT 角色）。
 */
@Tag(name = "商家订单", description = "商家订单列表与状态流转")
@RestController
@RequestMapping("/api/orders")
@PreAuthorize("hasRole('MERCHANT')")
public class MerchantOrderController {

    private final OrderService orderService;

    public MerchantOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 商家订单列表（可选店铺/状态筛选）。
     */
    @Operation(summary = "商家订单列表")
    @GetMapping("/merchant")
    public Result<PageVO<OrderVO>> merchantOrders(
            @RequestParam(required = false) Long shopId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(orderService.pageMerchant(shopId, status, pageNum, pageSize, currentUserId()));
    }

    /**
     * 商家接单（20 → 30）。
     */
    @Operation(summary = "接单")
    @PostMapping("/{id}/accept")
    public Result<Void> accept(@PathVariable Long id) {
        orderService.accept(id, currentUserId());
        return Result.success();
    }

    /**
     * 商家配送（30 → 40）。
     */
    @Operation(summary = "配送")
    @PostMapping("/{id}/deliver")
    public Result<Void> deliver(@PathVariable Long id) {
        orderService.deliver(id, currentUserId());
        return Result.success();
    }

    /**
     * 商家完成（40 → 50）。
     */
    @Operation(summary = "完成")
    @PostMapping("/{id}/complete")
    public Result<Void> complete(@PathVariable Long id) {
        orderService.complete(id, currentUserId());
        return Result.success();
    }

    /**
     * 从 SecurityContext 取出当前登录用户的 userId。
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

}