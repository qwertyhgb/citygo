package com.citygo.order.enums;

/**
 * 订单状态枚举。
 *
 * <p>编码按业务推进顺序离散取值（10-80），预留中间态与扩展空间。</p>
 *
 * <p>状态机流转表（本期实现亮色的流转，70/80 退款链路留待后续）：
 * <pre>
 *   10 待支付 --支付--> 20 已支付 --接单--> 30 商家已接单 --配送--> 40 配送中 --完成--> 50 已完成
 *   10 待支付 --取消--> 60 已取消
 *   20..40 --退款--> 70 退款中 --退款成功--> 80 已退款   （本期不实现流转，枚举先建好）
 * </pre>
 * </p>
 */
public enum OrderStatus {

    /** 待支付 */
    PENDING_PAYMENT(10, "待支付"),

    /** 已支付 */
    PAID(20, "已支付"),

    /** 商家已接单 */
    ACCEPTED(30, "商家已接单"),

    /** 配送中 */
    DELIVERING(40, "配送中"),

    /** 已完成 */
    COMPLETED(50, "已完成"),

    /** 已取消 */
    CANCELLED(60, "已取消"),

    /** 退款中（本期不实现流转） */
    REFUNDING(70, "退款中"),

    /** 已退款（本期不实现流转） */
    REFUNDED(80, "已退款");

    private final int code;
    private final String desc;

    OrderStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按状态码反查枚举；查不到返回 null，由调用方决定如何处理（例如非法状态码之外的场景）。
     */
    public static OrderStatus fromCode(int code) {
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

}