package com.citygo.merchant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.merchant.entity.Merchant;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商家 Mapper，对应 {@code merchant} 表。
 *
 * <p>用于按用户ID查询商家资料（判断/获取当前登录用户的商家身份）、插入商家资料等操作。</p>
 */
@Mapper
public interface MerchantMapper extends BaseMapper<Merchant> {

}