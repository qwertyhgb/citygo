package com.citygo.auth.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.auth.dto.LoginRequest;
import com.citygo.auth.dto.RegisterRequest;
import com.citygo.auth.entity.Role;
import com.citygo.auth.entity.UserRole;
import com.citygo.auth.mapper.RoleMapper;
import com.citygo.auth.mapper.UserRoleMapper;
import com.citygo.auth.security.JwtAuthenticationFilter;
import com.citygo.auth.security.JwtUtil;
import com.citygo.auth.service.AuthService;
import com.citygo.auth.vo.LoginResponse;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.user.entity.User;
import com.citygo.user.mapper.UserMapper;
import com.citygo.user.service.UserService;
import com.citygo.user.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * 认证域服务实现。
 *
 * <p>登录采用 BCrypt 校验密码（不把明文入库），成功后签发 JWT 并把
 * {@code citygo:login:token:{token} -> userId} 写入 Redis 作为服务端登录态。
 * 登出即删除该 key。</p>
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;

    /** JWT 有效期（秒），用于与 Redis 登录态 TTL 保持一致 */
    private final long expireSeconds;

    public AuthServiceImpl(UserMapper userMapper,
                           UserRoleMapper userRoleMapper,
                           RoleMapper roleMapper,
                           UserService userService,
                           PasswordEncoder passwordEncoder,
                           JwtUtil jwtUtil,
                           StringRedisTemplate stringRedisTemplate,
                           @Value("${citygo.jwt.expire-seconds}") long expireSeconds) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.stringRedisTemplate = stringRedisTemplate;
        this.expireSeconds = expireSeconds;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(RegisterRequest request) {
        // 用户名唯一性校验
        if (userService.getByUsername(request.getUsername()) != null) {
            throw new BizException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        // 创建用户：BCrypt 加密密码，status=1，昵称默认取用户名
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getUsername());
        user.setStatus(1);
        userMapper.insert(user);

        // 绑定 USER 角色
        Role role = roleMapper.selectOne(
                Wrappers.<Role>lambdaQuery().eq(Role::getCode, "USER"));
        if (role == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR);
        }
        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        userRoleMapper.insert(userRole);

        return userService.toUserVO(user);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userService.getByUsername(request.getUsername());
        // 用户不存在或已禁用，统一返回"用户名或密码错误"（防撞库探测）
        if (user == null || user.getStatus() == 0) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }

        // 签发 token 并写入 Redis 登录态，TTL 与 JWT 有效期一致
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        stringRedisTemplate.opsForValue().set(
                JwtAuthenticationFilter.LOGIN_TOKEN_PREFIX + token,
                String.valueOf(user.getId()),
                Duration.ofSeconds(expireSeconds));

        return new LoginResponse(token, userService.toUserVO(user));
    }

    @Override
    public void logout(HttpServletRequest request) {
        // 从请求属性拿到过滤器透传的原始 token（幂等：缺失/重复登出视为成功）
        Object attr = request.getAttribute(JwtAuthenticationFilter.REQUEST_ATTR_TOKEN);
        if (attr instanceof String token) {
            stringRedisTemplate.delete(JwtAuthenticationFilter.LOGIN_TOKEN_PREFIX + token);
        }
    }

}