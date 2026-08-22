package com.citygo.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.auth.entity.Role;
import com.citygo.auth.entity.UserRole;
import com.citygo.auth.mapper.RoleMapper;
import com.citygo.auth.mapper.UserRoleMapper;
import com.citygo.user.entity.User;
import com.citygo.user.mapper.UserMapper;
import com.citygo.user.service.UserService;
import com.citygo.user.vo.UserVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户领域服务实现。
 *
 * <p>负责 user 表的基础查询，并通过 user_role + role 组装用户的角色编码。
 * 虽然 Role/UserRole Mapper 归属于 auth 域，但"用户携带哪些角色"是用户信息的一部分，
 * 在此统一组装，供注册/登录/我的信息等场景复用。</p>
 */
@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;

    public UserServiceImpl(UserMapper userMapper,
                           UserRoleMapper userRoleMapper,
                           RoleMapper roleMapper) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    public User getByUsername(String username) {
        return userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    @Override
    public User getById(Long id) {
        return userMapper.selectById(id);
    }

    @Override
    public List<String> getRoleCodes(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(
                        Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId))
                .stream()
                .map(UserRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectByIds(roleIds).stream()
                .map(Role::getCode)
                .toList();
    }

    @Override
    public UserVO toUserVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setPhone(user.getPhone());
        vo.setStatus(user.getStatus());
        vo.setRoles(getRoleCodes(user.getId()));
        return vo;
    }

}