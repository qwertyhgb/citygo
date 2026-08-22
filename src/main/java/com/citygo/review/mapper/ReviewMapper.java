package com.citygo.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.review.entity.Review;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 评价 Mapper，对应 {@code review} 表。
 *
 * <p>自带的通用 CRUD 满足查/插/改；这里额外提供按店铺统计平均分的自定义查询，
 * 供"店铺评分重算"使用（AVG 由数据库聚合，避免把全部评价拉到内存算）。</p>
 */
@Mapper
public interface ReviewMapper extends BaseMapper<Review> {

    /**
     * 统计某店铺"可见评价"的平均分。
     *
     * <p>注意：自定义 @Select 不走 MyBatis-Plus 逻辑删除自动注入，故 SQL 里手动加
     * {@code deleted = 0}，与全局配置保持一致；status=1 只统计展示中的评价。</p>
     *
     * @param shopId 店铺ID
     * @return 平均分；无评价时返回 null（调用方应兼容）
     */
    @Select("SELECT AVG(rating) FROM review WHERE shop_id = #{shopId} AND status = 1 AND deleted = 0")
    BigDecimal avgRatingByShop(@Param("shopId") Long shopId);

}