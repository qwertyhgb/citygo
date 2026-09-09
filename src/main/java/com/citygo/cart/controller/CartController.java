package com.citygo.cart.controller;

import com.citygo.cart.dto.CartItemRequest;
import com.citygo.cart.dto.CartItemUpdateRequest;
import com.citygo.cart.service.CartService;
import com.citygo.cart.vo.CartItemVO;
import com.citygo.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 购物车接口 - 面向新手的详细说明版。
 *
 * <h3>概述</h3>
 * 购物车（非订单）相关的 HTTP 入口，共 5 个接口：
 * 加购 / 改数量 / 删条目 / 清空 / 查看。
 *
 * <h3>安全设计（重点）</h3>
 * <ul>
 *   <li><b>必须登录</b>：购物车是个人信息，接口不在公开白名单里，
 *       未登录会被 Spring Security 拦下（401/403）；</li>
 *   <li><b>userId 从登录态取</b>：每个接口都调用 {@link #currentUserId()}，
 *       从 SecurityContext（JWT 认证后填充）拿当前用户 ID，
 *       <b>绝不信任前端传来的 userId</b>——否则任何人都能操作别人的购物车
 *       （这是新手最常漏的越权漏洞）；</li>
 *   <li><b>无需特定角色</b>：普通用户（USER）即可，不需要商家身份。</li>
 * </ul>
 *
 * <h3>接口一览</h3>
 * <pre>
 * POST   /api/cart/items            加购（同商品自动累加数量，返回商品种数）
 * PUT    /api/cart/items/{productId} 改数量（quantity=0 即删除）
 * DELETE /api/cart/items/{productId} 删条目（幂等）
 * DELETE /api/cart                  清空购物车
 * GET    /api/cart                  查看购物车明细
 * </pre>
 *
 * <p>真正的业务逻辑都在 {@link CartService}，本类只做"接参 → 取 userId → 调服务 → 包装响应"。</p>
 *
 * @see CartService 购物车服务（Redis Hash 存储，见 CartServiceImpl）
 */
@Tag(name = "购物车", description = "Redis 购物车")
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    /**
     * 加入购物车（数量累加）。
     */
    @Operation(summary = "加入购物车")
    @PostMapping("/items")
    public Result<Integer> addItem(@Valid @RequestBody CartItemRequest request) {
        int count = cartService.addItem(currentUserId(), request.getProductId(), request.getQuantity());
        return Result.success(count);
    }

    /**
     * 更新购物车条目数量（0 表示删除）。
     */
    @Operation(summary = "更新购物车条目")
    @PutMapping("/items/{productId}")
    public Result<Void> updateItem(@PathVariable Long productId,
                                   @Valid @RequestBody CartItemUpdateRequest request) {
        cartService.updateItem(currentUserId(), productId, request.getQuantity());
        return Result.success();
    }

    /**
     * 删除购物车条目（幂等）。
     */
    @Operation(summary = "删除购物车条目")
    @DeleteMapping("/items/{productId}")
    public Result<Void> removeItem(@PathVariable Long productId) {
        cartService.removeItem(currentUserId(), productId);
        return Result.success();
    }

    /**
     * 清空购物车。
     */
    @Operation(summary = "清空购物车")
    @DeleteMapping
    public Result<Void> clear() {
        cartService.clear(currentUserId());
        return Result.success();
    }

    /**
     * 查看购物车明细。
     */
    @Operation(summary = "查看购物车")
    @GetMapping
    public Result<List<CartItemVO>> viewCart() {
        return Result.success(cartService.viewCart(currentUserId()));
    }

    /**
     * 从 SecurityContext 取出当前登录用户的 userId。
     *
     * <p><b>为什么不从请求参数取 userId？</b>SecurityContext 是 Spring Security
     * 在 JWT 过滤器校验通过后填充的"当前会话上下文"，里面的身份是服务器盖章的，
     * 前端无法伪造。这里让所有购物车操作强制绑定<b>登录者本人</b>——
     * 想操作别人的购物车只能先拿别人的账号登录，天然防越权（最典型的越权漏洞场景
     * 就是"接口用前端传的 userId，传谁的就能操作谁的"）。</p>
     *
     * <p>principal 在 AuthServiceImpl 登录成功时被设置为 userId（Long 类型），
     * 所以这里可以安全强转。</p>
     *
     * @return 当前登录用户 ID（保证非 null，未登录请求到不了这里）
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

}