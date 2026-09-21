package com.citygo.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀可靠消息记录，对应数据库 {@code seckill_message} 表（本地消息表）。
 *
 * <p>用于填平"Redis 预扣成功 → MQ 发送"之间的可靠性缝隙：
 * 抢购资格以本表落库为准（status=0 待发送），投递成功后置 status=1；
 * 发送失败的消息由 {@code SeckillMessageRelayTask} 定时补偿重发。</p>
 */
@Data
@TableName("seckill_message")
public class SeckillMessageRecord {

    /** 状态：待发送（已落库，尚未投递成功，等待补偿重发） */
    public static final int STATUS_PENDING = 0;

    /** 状态：已发送（convertAndSend 投递成功） */
    public static final int STATUS_SENT = 1;

    /** 状态：重试超限（连续补偿失败，需人工介入排查 MQ 故障） */
    public static final int STATUS_FAILED = 2;

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 抢购用户ID */
    private Long userId;

    /** 秒杀商品ID */
    private Long productId;

    /** 消息状态：0 待发送 1 已发送 2 重试超限 */
    private Integer status;

    /** 已补偿重试次数 */
    private Integer retryCount;

    /** 下次补偿重试时间 */
    private LocalDateTime nextRetryTime;

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

}
