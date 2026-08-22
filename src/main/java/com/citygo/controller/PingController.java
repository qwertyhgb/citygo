package com.citygo.controller;

import com.citygo.common.result.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统状态与健康检查接口。
 *
 * <p>该接口是 Phase 1 用于验证"网络链路 → 应用 → 数据库/容器环境"整体连通性的临时期探接口。
 * 进入业务阶段后会被真实的业务接口（如用户、商家、商品等）替代，届时可移除。</p>
 *
 * @author citygo
 */
@Tag(name = "系统", description = "系统状态与健康检查")
@RestController
@RequestMapping("/api")
public class PingController {

    /**
     * 连通性探测：无业务逻辑，固定返回 pong 表示应用已正常响应请求。
     *
     * @return 统一返回结构，code=200、data=pong
     */
    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.success("pong");
    }

}