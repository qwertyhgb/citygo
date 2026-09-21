package com.citygo.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citygo.seckill.entity.SeckillMessageRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 秒杀可靠消息 Mapper，对应 {@code seckill_message} 表（本地消息表）。
 *
 * <p>由 {@code @MapperScan("com.citygo.**.mapper")} 统一扫描注册。</p>
 */
@Mapper
public interface SeckillMessageMapper extends BaseMapper<SeckillMessageRecord> {

}
