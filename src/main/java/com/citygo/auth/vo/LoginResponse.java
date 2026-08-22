package com.citygo.auth.vo;

import com.citygo.user.vo.UserVO;
import lombok.Data;

/**
 * 登录成功响应体，包含访问令牌与该用户信息。
 */
@Data
public class LoginResponse {

    /** JWT 访问令牌 */
    private String token;

    /** 当前登录用户信息（含角色） */
    private UserVO user;

    public LoginResponse(String token, UserVO user) {
        this.token = token;
        this.user = user;
    }

}