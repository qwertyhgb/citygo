package com.citygo.auth.service;

import com.citygo.auth.dto.LoginRequest;
import com.citygo.auth.dto.RegisterRequest;
import com.citygo.auth.vo.LoginResponse;
import com.citygo.user.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 认证域服务接口：注册、登录、登出。
 */
public interface AuthService {

    /**
     * 注册普通用户：创建 user 记录并绑定 USER 角色，返回用户视图（不含 password）。
     */
    UserVO register(RegisterRequest request);

    /**
     * 登录校验并签发 JWT，把登录态写入 Redis，返回 token 与用户信息。
     */
    LoginResponse login(LoginRequest request);

    /**
     * 登出：删除 Redis 中的登录态。幂等设计，无 token 或重复登出也正常结束。
     */
    void logout(HttpServletRequest request);

}