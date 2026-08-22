package com.citygo.merchant.controller;

import com.citygo.common.result.Result;
import com.citygo.merchant.dto.MerchantRegisterRequest;
import com.citygo.merchant.service.MerchantService;
import com.citygo.user.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家注册接口。
 *
 * <p>公开接口（无需登录）：注册后同普通用户一样通过 /api/auth/login 登录，
 * 返回的 UserVO 中 roles 含 MERCHANT。</p>
 */
@Tag(name = "商家", description = "商家注册")
@RestController
@RequestMapping("/api/merchants")
public class MerchantController {

    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    /**
     * 商家注册。
     */
    @Operation(summary = "商家注册")
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody MerchantRegisterRequest request) {
        return Result.success(merchantService.registerMerchant(request));
    }

}