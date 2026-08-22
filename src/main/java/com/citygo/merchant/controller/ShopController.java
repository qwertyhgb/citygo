package com.citygo.merchant.controller;

import com.citygo.common.result.Result;
import com.citygo.merchant.dto.ShopCreateRequest;
import com.citygo.merchant.dto.ShopOpenStatusRequest;
import com.citygo.merchant.dto.ShopUpdateRequest;
import com.citygo.merchant.service.ShopService;
import com.citygo.merchant.vo.ShopVO;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 店铺管理接口（仅商家可用）。
 */
@Tag(name = "店铺", description = "商家店铺管理")
@RestController
@RequestMapping("/api/shops")
@PreAuthorize("hasRole('MERCHANT')")
public class ShopController {

    private final ShopService shopService;

    public ShopController(ShopService shopService) {
        this.shopService = shopService;
    }

    /**
     * 创建店铺。
     */
    @Operation(summary = "创建店铺")
    @PostMapping
    public Result<ShopVO> create(@Valid @RequestBody ShopCreateRequest request) {
        return Result.success(shopService.create(request, currentUserId()));
    }

    /**
     * 更新店铺（只能改自己的店铺）。
     */
    @Operation(summary = "更新店铺")
    @PutMapping("/{id}")
    public Result<ShopVO> update(@PathVariable Long id, @Valid @RequestBody ShopUpdateRequest request) {
        return Result.success(shopService.update(id, request, currentUserId()));
    }

    /**
     * 切换营业状态。
     */
    @Operation(summary = "切换营业状态")
    @PatchMapping("/{id}/open-status")
    public Result<Void> updateOpenStatus(@PathVariable Long id,
                                         @Valid @RequestBody ShopOpenStatusRequest request) {
        shopService.updateOpenStatus(id, request, currentUserId());
        return Result.success();
    }

    /**
     * 我的店铺列表。
     */
    @Operation(summary = "我的店铺")
    @GetMapping("/my")
    public Result<List<ShopVO>> myShops() {
        return Result.success(shopService.listMy(currentUserId()));
    }

    /**
     * 从 SecurityContext 取出当前登录用户的 userId。
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

}