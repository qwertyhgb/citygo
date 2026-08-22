package com.citygo.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.auth.entity.Role;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色 Mapper，对应 {@code role} 表。
 *
 * <p>用于查询角色编码（按 code 定位角色、批量按 ID 查角色）等操作。</p>
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {

}