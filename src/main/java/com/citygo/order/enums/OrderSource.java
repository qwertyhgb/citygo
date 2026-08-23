package com.citygo.order.enums;

/**
 * 订单来源枚举。
 *
 * <p>用于区分普通下单与高并发秒杀下单，决定库存扣减/回补路径以及一人一单复查策略。</p>
 */
public enum OrderSource {

    /** 普通订单 */
    NORMAL(1, "普通"),

    /** 秒杀订单 */
    SECKILL(2, "秒杀");

    private final int code;
    private final String desc;

    OrderSource(int code, String desc) {
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
     * 按编码反查枚举；查不到返回 null。
     */
    public static OrderSource fromCode(int code) {
        for (OrderSource source : values()) {
            if (source.code == code) {
                return source;
            }
        }
        return null;
    }

}
