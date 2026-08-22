package com.citygo.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求体。
 *
 * <p>通过 JSR-303 校验注解约束字段格式，校验失败由全局异常处理器统一返回 400。</p>
 */
@Data
public class RegisterRequest {

    /** 用户名（4-20 位） */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 20, message = "用户名长度需在 4-20 位之间")
    private String username;

    /** 密码（6-32 位） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6-32 位之间")
    private String password;

}