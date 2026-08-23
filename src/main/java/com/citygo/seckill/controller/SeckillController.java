package com.citygo.seckill.controller;

import com.citygo.common.annotation.RateLimit;
import com.citygo.common.result.Result;
import com.citygo.seckill.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀抢购接口。
 */
@Tag(name = "秒杀", description = "秒杀抢购相关接口")
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    /**
     * 抢购接口。
     *
     * <p>注释：秒杀接口必须限流防刷，固定窗口 1 秒最多允许 10 次请求。</p>
     *
     * @param productId 秒杀商品ID
     * @return 抢购结果
     */
    @Operation(summary = "秒杀抢购")
    @PostMapping("/{productId}")
    @RateLimit(limit = 10, windowSeconds = 1)
    public Result<String> seckill(@PathVariable Long productId) {
        Long currentUserId = currentUserId();
        seckillService.seckill(productId, currentUserId);
        return Result.success("抢购成功，订单创建中");
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

}
