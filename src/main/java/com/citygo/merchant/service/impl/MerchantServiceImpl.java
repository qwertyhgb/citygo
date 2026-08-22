package com.citygo.merchant.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.auth.entity.Role;
import com.citygo.auth.entity.UserRole;
import com.citygo.auth.mapper.RoleMapper;
import com.citygo.auth.mapper.UserRoleMapper;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import com.citygo.merchant.dto.MerchantRegisterRequest;
import com.citygo.merchant.entity.Merchant;
import com.citygo.merchant.mapper.MerchantMapper;
import com.citygo.merchant.service.MerchantService;
import com.citygo.user.entity.User;
import com.citygo.user.mapper.UserMapper;
import com.citygo.user.service.UserService;
import com.citygo.user.vo.UserVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家域服务实现。
 *
 * <p>商家账号与普通用户同源（都落在 user 表），靠角色区分：商家注册时
 * 创建 user + 绑定 MERCHANT 角色 + 创建 merchant 商家资料，三者在一个事务内。
 * merchant 表通过 userId 与 user 表逻辑关联。</p>
 */
@Service
public class MerchantServiceImpl implements MerchantService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final MerchantMapper merchantMapper;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public MerchantServiceImpl(UserMapper userMapper,
                               RoleMapper roleMapper,
                               UserRoleMapper userRoleMapper,
                               MerchantMapper merchantMapper,
                               UserService userService,
                               PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.merchantMapper = merchantMapper;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO registerMerchant(MerchantRegisterRequest request) {
        // 用户名唯一性校验
        if (userService.getByUsername(request.getUsername()) != null) {
            throw new BizException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        // 创建用户账号，昵称取商家名称
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getMerchantName());
        user.setStatus(1);
        userMapper.insert(user);

        // 绑定 MERCHANT 角色
        Role role = roleMapper.selectOne(
                Wrappers.<Role>lambdaQuery().eq(Role::getCode, "MERCHANT"));
        if (role == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR);
        }
        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        userRoleMapper.insert(userRole);

        // 创建商家资料
        Merchant merchant = new Merchant();
        merchant.setUserId(user.getId());
        merchant.setMerchantName(request.getMerchantName());
        merchant.setContactName(request.getContactName());
        merchant.setContactPhone(request.getContactPhone());
        merchant.setStatus(1);
        merchantMapper.insert(merchant);

        return userService.toUserVO(user);
    }

    @Override
    public Merchant getByUserId(Long userId) {
        return merchantMapper.selectOne(
                Wrappers.<Merchant>lambdaQuery().eq(Merchant::getUserId, userId));
    }

    @Override
    public Merchant getById(Long id) {
        return merchantMapper.selectById(id);
    }

}