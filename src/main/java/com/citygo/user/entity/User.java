package com.citygo.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应数据库 {@code user} 表。
 *
 * <p>主键使用雪花算法（{@code ASSIGN_ID}，分布式友好、无需依赖数据库自增）。
 * 逻辑删除字段 {@code deleted} 由 MyBatis-Plus 全局配置（logic-delete-field）自动生效，
 * 本实体仅需保留该属性，无需额外 {@code @TableLogic} 注解。</p>
 */
@Data
@TableName("user")
public class User {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户名 */
    private String username;

    /** 密码（BCrypt 加密存储） */
    private String password;

    /** 手机号（可空） */
    private String phone;

    /** 昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatar;

    /** 状态：1 正常 0 禁用 */
    private Integer status;

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

}