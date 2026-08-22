package com.citygo.user.controller;

import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.common.result.Result;
import com.citygo.user.entity.User;
import com.citygo.user.service.UserService;
import com.citygo.user.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户信息接口。
 */
@Tag(name = "用户", description = "当前用户信息")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 当前登录用户信息（需登录）。
     *
     * <p>从 SecurityContext 中取出认证时写入的 userId（principal），再查询用户信息。
     * Security 已保证访问到此处的请求都携带了有效登录态。</p>
     */
    @Operation(summary = "我的信息")
    @GetMapping("/me")
    public Result<UserVO> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long userId = (Long) authentication.getPrincipal();
        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return Result.success(userService.toUserVO(user));
    }

}