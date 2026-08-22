package com.citygo.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.category.entity.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分类 Mapper，对应 {@code category} 表。
 *
 * <p>用于查询启用的分类列表、按ID校验分类是否存在等操作。</p>
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {

}