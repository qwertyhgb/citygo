package com.citygo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper，对应 {@code user} 表。
 *
 * <p>继承 {@link BaseMapper} 获得通用 CRUD 能力；无需编写 XML 即可完成
 * 按用户名查询、插入等基础操作。由 {@code @MapperScan} 统一扫描注册。</p>
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

}