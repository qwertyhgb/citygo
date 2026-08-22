package com.citygo.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.order.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单明细 Mapper，对应 {@code order_item} 表。
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

}