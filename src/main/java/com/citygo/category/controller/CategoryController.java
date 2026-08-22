package com.citygo.category.controller;

import com.citygo.category.service.CategoryService;
import com.citygo.category.vo.CategoryVO;
import com.citygo.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分类查询接口（公开，无需登录）。
 */
@Tag(name = "分类", description = "商品分类查询")
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * 查询启用中的一级分类列表。
     */
    @Operation(summary = "分类列表")
    @GetMapping
    public Result<List<CategoryVO>> list() {
        return Result.success(categoryService.listEnabledCategories());
    }

}