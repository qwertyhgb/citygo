package com.citygo.user.service;

import com.citygo.user.entity.User;
import com.citygo.user.vo.UserVO;

import java.util.List;

/**
 * 用户领域服务接口。
 *
 * <p>提供用户查询基础能力，以及用户 → {@link UserVO} 的组装（内含角色编码列表）。</p>
 */
public interface UserService {

    /**
     * 按用户名查询用户；不存在返回 null。
     */
    User getByUsername(String username);

    /**
     * 按用户ID查询；不存在返回 null。
     */
    User getById(Long id);

    /**
     * 查询用户绑定的全部角色编码列表（如 USER / MERCHANT / ADMIN）。
     */
    List<String> getRoleCodes(Long userId);

    /**
     * 将用户实体组装为不含 password 的视图对象（含角色）。
     */
    UserVO toUserVO(User user);

}