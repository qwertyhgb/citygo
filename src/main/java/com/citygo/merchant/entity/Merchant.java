package com.citygo.merchant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家实体，对应数据库 {@code merchant} 表。
 *
 * <p>商家账号与普通用户同源（user 表），靠角色（MERCHANT）区分；
 * merchant 表仅存商家平台身份资料，通过 {@code userId} 与 user 表逻辑关联。</p>
 */
@Data
@TableName("merchant")
public class Merchant {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联用户ID */
    private Long userId;

    /** 商家名称 */
    private String merchantName;

    /** 联系人姓名 */
    private String contactName;

    /** 联系电话 */
    private String contactPhone;

    /** 商家 logo */
    private String logo;

    /** 商家简介 */
    private String description;

    /** 状态：1 正常 0 禁用 */
    private Integer status;

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

}