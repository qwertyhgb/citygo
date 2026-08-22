package com.citygo.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.order.entity.Payment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 支付记录 Mapper，对应 {@code payment} 表。
 */
@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {

}