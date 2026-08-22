package com.citygo.cart.controller;

import com.citygo.cart.dto.CartItemRequest;
import com.citygo.cart.dto.CartItemUpdateRequest;
import com.citygo.cart.service.CartService;
import com.citygo.cart.vo.CartItemVO;
import com.citygo.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 购物车接口（需登录，普通用户即可，无需特定角色）。
 */
@Tag(name = "购物车", description = "Redis 购物车")
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    /**
     * 加入购物车（数量累加）。
     */
    @Operation(summary = "加入购物车")
    @PostMapping("/items")
    public Result<Integer> addItem(@Valid @RequestBody CartItemRequest request) {
        int count = cartService.addItem(currentUserId(), request.getProductId(), request.getQuantity());
        return Result.success(count);
    }

    /**
     * 更新购物车条目数量（0 表示删除）。
     */
    @Operation(summary = "更新购物车条目")
    @PutMapping("/items/{productId}")
    public Result<Void> updateItem(@PathVariable Long productId,
                                   @Valid @RequestBody CartItemUpdateRequest request) {
        cartService.updateItem(currentUserId(), productId, request.getQuantity());
        return Result.success();
    }

    /**
     * 删除购物车条目（幂等）。
     */
    @Operation(summary = "删除购物车条目")
    @DeleteMapping("/items/{productId}")
    public Result<Void> removeItem(@PathVariable Long productId) {
        cartService.removeItem(currentUserId(), productId);
        return Result.success();
    }

    /**
     * 清空购物车。
     */
    @Operation(summary = "清空购物车")
    @DeleteMapping
    public Result<Void> clear() {
        cartService.clear(currentUserId());
        return Result.success();
    }

    /**
     * 查看购物车明细。
     */
    @Operation(summary = "查看购物车")
    @GetMapping
    public Result<List<CartItemVO>> viewCart() {
        return Result.success(cartService.viewCart(currentUserId()));
    }

    /**
     * 从 SecurityContext 取出当前登录用户的 userId。
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

}