package com.citygo.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品 Mapper，对应 {@code product} 表。
 *
 * <p>用于商品的增删改查、按店铺/关键词分页查询等操作。</p>
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * CAS 式扣减库存并累加销量（防超卖核心）。
     *
     * <p>为什么用条件更新而不是"先查库存再减"：
     * 高并发下先查再改是"读改写"，两个请求同时读到同一库存，各自都认为充足，
     * 最终会把库存扣成负数（超卖）。而 {@code UPDATE ... WHERE stock >= ?} 是单条
     * 原子语句，数据库行锁保证同一时刻只有一个请求能扣减成功，返回 0 影响行数即库存不足。</p>
     *
     * @param id       商品ID
     * @param quantity 扣减数量
     * @return 受影响行数；0 表示库存不足或商品不存在
     */
    @Update("UPDATE product SET stock = stock - #{quantity}, sales = sales + #{quantity} " +
            "WHERE id = #{id} AND stock >= #{quantity}")
    int deductStock(@Param("id") Long id, @Param("quantity") int quantity);

    /**
     * 回补库存（取消订单时）：库存加回、销量扣回。
     *
     * <p>简化：不校验 sales 是否够减（销量通常远大于当前回补量），直接加回，
     * 取消回补是"只增不减"方向——库存只增不超卖，且订单已扣则必能回补。</p>
     *
     * @param id       商品ID
     * @param quantity 回补数量
     * @return 受影响行数
     */
    @Update("UPDATE product SET stock = stock + #{quantity}, sales = sales - #{quantity} WHERE id = #{id}")
    int restoreStock(@Param("id") Long id, @Param("quantity") int quantity);

}