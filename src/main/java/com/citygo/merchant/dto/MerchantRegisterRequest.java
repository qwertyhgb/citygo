package com.citygo.merchant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 商家注册请求体。
 *
 * <p>商家注册会同时创建 user（登录账号）与 merchant（商家资料）两条记录。</p>
 */
@Data
public class MerchantRegisterRequest {

    /** 登录用户名（4-20 位） */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 20, message = "用户名长度需在 4-20 位之间")
    private String username;

    /** 登录密码（6-32 位） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6-32 位之间")
    private String password;

    /** 商家名称 */
    @NotBlank(message = "商家名称不能为空")
    @Size(max = 50, message = "商家名称长度不能超过 50")
    private String merchantName;

    /** 联系人姓名 */
    @NotBlank(message = "联系人不能为空")
    @Size(max = 50, message = "联系人姓名长度不能超过 50")
    private String contactName;

    /** 联系电话 */
    @NotBlank(message = "联系电话不能为空")
    private String contactPhone;

}