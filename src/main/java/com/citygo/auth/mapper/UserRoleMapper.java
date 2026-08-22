package com.citygo.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.auth.entity.UserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户角色关联 Mapper，对应 {@code user_role} 表。
 *
 * <p>用于查询某用户绑定的全部角色记录、插入新绑定关系等操作。</p>
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

}