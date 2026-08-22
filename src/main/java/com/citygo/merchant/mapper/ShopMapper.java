package com.citygo.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.merchant.entity.Shop;
import org.apache.ibatis.annotations.Mapper;

/**
 * 店铺 Mapper，对应 {@code shop} 表。
 *
 * <p>用于店铺的增删改查、按商家ID查询其店铺列表等操作。</p>
 */
@Mapper
public interface ShopMapper extends BaseMapper<Shop> {

}