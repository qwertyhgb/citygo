package com.citygo.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色实体，对应数据库 {@code role} 表。
 *
 * <p>种子数据已包含 USER / MERCHANT / ADMIN 三个角色（Phase 2 迁移 V2 写入）。</p>
 */
@Data
@TableName("role")
public class Role {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 角色编码（如 USER / MERCHANT / ADMIN） */
    private String code;

    /** 角色名称 */
    private String name;

    /** 角色描述 */
    private String description;

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

}