package com.citygo.review.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单评价实体，对应数据库 {@code review} 表。
 *
 * <p>一单一评（唯一索引 uk_order_id）；{@code merchantReply}/{@code replyTime} 为商家回复字段。
 * 评价后联动更新店铺评分（shop.score = 平均分），由服务层重算。</p>
 */
@Data
@TableName("review")
public class Review {

    /** 主键（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 订单ID */
    private Long orderId;

    /** 评价用户ID */
    private Long userId;

    /** 店铺ID（冗余自订单，便于按店查评价） */
    private Long shopId;

    /** 评分 1-5 */
    private Integer rating;

    /** 评价内容 */
    private String content;

    /** 评价图片（逗号分隔 URL） */
    private String images;

    /** 商家回复 */
    private String merchantReply;

    /** 回复时间 */
    private LocalDateTime replyTime;

    /** 状态：1 展示 0 隐藏 */
    private Integer status;

    /** 创建时间（由数据库默认值填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动更新） */
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    private Integer deleted;

}