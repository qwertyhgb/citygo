package com.citygo.category.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.category.entity.Category;
import com.citygo.category.mapper.CategoryMapper;
import com.citygo.category.service.CategoryService;
import com.citygo.category.vo.CategoryVO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分类域服务实现。
 */
@Service
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;

    public CategoryServiceImpl(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    @Override
    public List<CategoryVO> listEnabledCategories() {
        return categoryMapper.selectList(
                        Wrappers.<Category>lambdaQuery()
                                .eq(Category::getStatus, 1)
                                .orderByAsc(Category::getSort)
                                .orderByAsc(Category::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 分类实体 → 视图对象。
     */
    private CategoryVO toVO(Category category) {
        CategoryVO vo = new CategoryVO();
        vo.setId(category.getId());
        vo.setCategoryName(category.getCategoryName());
        vo.setIcon(category.getIcon());
        vo.setSort(category.getSort());
        return vo;
    }

}