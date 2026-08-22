package com.citygo.category.vo;

import lombok.Data;

/**
 * 分类视图对象。
 */
@Data
public class CategoryVO {

    /** 分类ID */
    private Long id;

    /** 分类名称 */
    private String categoryName;

    /** 分类图标 */
    private String icon;

    /** 排序值，越小越靠前 */
    private Integer sort;

}