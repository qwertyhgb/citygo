package com.citygo.category.service;

import com.citygo.category.vo.CategoryVO;

import java.util.List;

/**
 * 分类域服务接口。
 */
public interface CategoryService {

    /**
     * 查询启用中的一级分类列表，按 sort 升序、id 升序排列。
     */
    List<CategoryVO> listEnabledCategories();

}