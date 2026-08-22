package com.citygo.user.vo;

import lombok.Data;

import java.util.List;

/**
 * 用户信息视图对象。
 *
 * <p>面向调用方返回的用户基础信息，不含 password 等敏感字段；
 * {@code roles} 为用户绑定的角色编码列表（如 USER / MERCHANT / ADMIN）。</p>
 */
@Data
public class UserVO {

    /** 用户ID（雪花ID） */
    private Long id;

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatar;

    /** 手机号 */
    private String phone;

    /** 状态：1 正常 0 禁用 */
    private Integer status;

    /** 角色编码列表 */
    private List<String> roles;

}