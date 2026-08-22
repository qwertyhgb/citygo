package com.citygo.order.controller;

import com.citygo.common.page.PageVO;
import com.citygo.common.result.Result;
import com.citygo.order.dto.OrderCreateRequest;
import com.citygo.order.service.OrderService;
import com.citygo.order.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端订单接口（下单/详情/我的/取消/支付，需登录）。
 */
@Tag(name = "订单", description = "用户下单、支付与订单查询")
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 下单。
     */
    @Operation(summary = "下单")
    @PostMapping
    public Result<OrderVO> create(@Valid @RequestBody OrderCreateRequest request) {
        return Result.success(orderService.create(request, currentUserId()));
    }

    /**
     * 订单详情（含明细）。
     */
    @Operation(summary = "订单详情")
    @GetMapping("/{id}")
    public Result<OrderVO> detail(@PathVariable Long id) {
        return Result.success(orderService.getDetail(id, currentUserId()));
    }

    /**
     * 我的订单分页。
     */
    @Operation(summary = "我的订单")
    @GetMapping("/my")
    public Result<PageVO<OrderVO>> my(@RequestParam(required = false) Integer status,
                                      @RequestParam(defaultValue = "1") long pageNum,
                                      @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(orderService.pageMy(status, pageNum, pageSize, currentUserId()));
    }

    /**
     * 取消订单。
     */
    @Operation(summary = "取消订单")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        orderService.cancel(id, null, currentUserId());
        return Result.success();
    }

    /**
     * 模拟支付（幂等）。
     */
    @Operation(summary = "模拟支付")
    @PostMapping("/{id}/pay")
    public Result<Void> pay(@PathVariable Long id) {
        orderService.pay(id, currentUserId());
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