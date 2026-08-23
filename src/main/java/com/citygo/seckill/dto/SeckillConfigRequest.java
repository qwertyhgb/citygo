package com.citygo.seckill.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商家配置秒杀请求体。
 */
@Data
public class SeckillConfigRequest {

    /** 秒杀价 */
    @NotNull(message = "秒杀价不能为空")
    @DecimalMin(value = "0.01", message = "秒杀价必须大于等于 0.01")
    private BigDecimal seckillPrice;

    /** 秒杀库存 */
    @NotNull(message = "秒杀库存不能为空")
    @Min(value = 1, message = "秒杀库存至少为 1")
    private Integer seckillStock;

    /** 秒杀开始时间 */
    @NotNull(message = "秒杀开始时间不能为空")
    private LocalDateTime startTime;

    /** 秒杀结束时间 */
    @NotNull(message = "秒杀结束时间不能为空")
    private LocalDateTime endTime;

}
