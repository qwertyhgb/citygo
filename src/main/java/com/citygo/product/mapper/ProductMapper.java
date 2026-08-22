package com.citygo.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品 Mapper，对应 {@code product} 表。
 *
 * <p>用于商品的增删改查、按店铺/关键词分页查询等操作。</p>
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

}