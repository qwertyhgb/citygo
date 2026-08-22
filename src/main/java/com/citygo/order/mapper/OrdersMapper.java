package com.citygo.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.order.entity.Orders;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单 Mapper，对应 {@code orders} 表。
 */
@Mapper
public interface OrdersMapper extends BaseMapper<Orders> {

}