package com.citygo.address.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.address.entity.Address;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收货地址 Mapper，对应 {@code address} 表。
 *
 * <p>用于地址的增删改查、按用户查询地址列表等操作。</p>
 */
@Mapper
public interface AddressMapper extends BaseMapper<Address> {

}