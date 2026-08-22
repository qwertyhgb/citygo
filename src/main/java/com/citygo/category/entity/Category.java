package com.citygo.category.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品分类实体，对应数据库 {@code category} 表。
 *
 * <p>支持层级分类（{@code parentId}=0 表示一级分类）。
 * Phase 4 通过迁移 V3 预置了 6 个一级分类。</p>
 */
@Data
@TableName("category")
public class Category {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 父分类ID，0 表示一级分类 */
    private Long parentId;

    /** 分类名称 */
    private String categoryName;

    /** 分类图标 */
    private String icon;

    /** 排序值，越小越靠前 */
    private Integer sort;

    /** 状态：1 启用 0 停用 */
    private Integer status;

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

}