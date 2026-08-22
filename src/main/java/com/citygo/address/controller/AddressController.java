package com.citygo.address.controller;

import com.citygo.address.dto.AddressCreateRequest;
import com.citygo.address.dto.AddressUpdateRequest;
import com.citygo.address.service.AddressService;
import com.citygo.address.vo.AddressVO;
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
 * 收货地址管理接口（需登录，普通用户即可）。
 */
@Tag(name = "收货地址", description = "收货地址增删改查")
@RestController
@RequestMapping("/api/addresses")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    /**
     * 新增地址。
     */
    @Operation(summary = "新增地址")
    @PostMapping
    public Result<AddressVO> create(@Valid @RequestBody AddressCreateRequest request) {
        return Result.success(addressService.create(request, currentUserId()));
    }

    /**
     * 我的地址列表。
     */
    @Operation(summary = "我的地址")
    @GetMapping
    public Result<List<AddressVO>> list() {
        return Result.success(addressService.listMine(currentUserId()));
    }

    /**
     * 更新地址。
     */
    @Operation(summary = "更新地址")
    @PutMapping("/{id}")
    public Result<AddressVO> update(@PathVariable Long id, @Valid @RequestBody AddressUpdateRequest request) {
        return Result.success(addressService.update(id, request, currentUserId()));
    }

    /**
     * 删除地址（逻辑删除）。
     */
    @Operation(summary = "删除地址")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.delete(id, currentUserId());
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