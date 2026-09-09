package com.citygo.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.merchant.entity.Shop;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 店铺 Mapper，对应 {@code shop} 表。
 *
 * <p>用于店铺的增删改查、按商家ID查询其店铺列表等操作。</p>
 */
@Mapper
public interface ShopMapper extends BaseMapper<Shop> {

    /**
     * 累加店铺月销量（下单为正、取消为负）。
     *
     * @param id    店铺ID
     * @param delta 增量（正负均可）
     * @return 受影响行数
     */
    @Update("UPDATE shop SET monthly_sales = monthly_sales + #{delta} WHERE id = #{id}")
    int addMonthlySales(@Param("id") Long id, @Param("delta") int delta);

    /**
     * 更新店铺评分并原子累加评价数。
     *
     * @param id    店铺ID
     * @param score 新均分
     * @return 受影响行数
     */
    @Update("UPDATE shop SET score = #{score}, review_count = review_count + 1 WHERE id = #{id}")
    int updateScoreAndIncrReviewCount(@Param("id") Long id, @Param("score") BigDecimal score);

}