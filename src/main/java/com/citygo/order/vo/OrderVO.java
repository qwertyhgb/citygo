package com.citygo.order.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单视图对象。
 *
 * <p>列表页场景 {@code items} 可为空（不查明细以减负），详情页则包含完整明细。</p>
 */
@Data
public class OrderVO {

    /** 订单ID */
    private Long id;

    /** 业务订单号 */
    private String orderNo;

    /** 店铺ID */
    private Long shopId;

    /** 店铺名称 */
    private String shopName;

    /** 订单状态码（10/20/...） */
    private Integer status;

    /** 订单状态描述 */
    private String statusDesc;

    /** 订单来源：1 普通 2 秒杀 */
    private Integer source;

    /** 商品总额 */
    private BigDecimal totalAmount;

    /** 优惠金额 */
    private BigDecimal discountAmount;

    /** 使用的优惠券名称（未用券时为空） */
    private String couponName;

    /** 实付金额 */
    private BigDecimal payAmount;

    /** 收货人姓名（快照） */
    private String receiverName;

    /** 收货人电话（快照） */
    private String receiverPhone;

    /** 收货地址（快照） */
    private String receiverAddress;

    /** 买家备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 支付时间 */
    private LocalDateTime paidTime;

    /** 完成时间 */
    private LocalDateTime completedTime;

    /** 取消时间 */
    private LocalDateTime cancelTime;

    /** 取消原因 */
    private String cancelReason;

    /** 订单明细（详情页填充，列表页可为空） */
    private List<OrderItemVO> items;

}