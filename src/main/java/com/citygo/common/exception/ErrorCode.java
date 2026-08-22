package com.citygo.common.exception;

/**
 * 通用错误码枚举。
 *
 * <p>本阶段只定义跨业务、跨模块共用的基础错误码；后续进入具体业务阶段（如 auth、order、
 * payment 等）时，将在对应业务包里扩展各自的业务错误码，避免把所有码堆在公共枚举里导致膨胀。</p>
 *
 * <p>错误码取值与 HTTP 语义对齐，便于阅读，但因本项目 HTTP 状态码恒为 200，
 * 实际的业务状态由这里的 {@code code} 字段承载。</p>
 *
 * @author citygo
 */
public enum ErrorCode {

    /** 请求参数错误：参数缺失、格式非法、取值越界等 */
    BAD_REQUEST(400, "参数错误"),

    /** 未登录或登录已过期：需要先认证通过再访问受保护资源 */
    UNAUTHORIZED(401, "未登录或登录已过期"),

    /** 没有操作权限：已登录但无权访问该资源或执行该操作 */
    FORBIDDEN(403, "没有操作权限"),

    /** 资源不存在：目标资源在系统中不存在或已被删除 */
    NOT_FOUND(404, "资源不存在"),

    /** 资源冲突：目标资源已存在或状态冲突，无法完成提交 */
    CONFLICT(409, "资源冲突"),

    /** 服务器内部错误：未预期的系统级故障，通常由异常兜底逻辑返回 */
    INTERNAL_ERROR(500, "服务器内部错误"),

    // ---------------- Phase 3 认证/用户域业务错误码 ----------------
    // 在通用错误码之后追加，避免重号；后续业务阶段在各自业务包内继续扩展。

    /** 注册/绑定时用户名已存在：资源冲突语义 */
    USERNAME_ALREADY_EXISTS(409, "用户名已存在"),

    /** 登录失败：统一用"用户名或密码错误"，不区分具体原因，防止撞库探测用户是否存在 */
    BAD_CREDENTIALS(401, "用户名或密码错误"),

    /** 按用户ID/用户名查询不到用户 */
    USER_NOT_FOUND(404, "用户不存在"),

    /** 账号已被禁用：已登录但账号状态为禁用，无法继续使用 */
    USER_DISABLED(403, "账号已被禁用"),

    /** JWT 非法、过期或服务端登录态（Redis）已失效 */
    TOKEN_INVALID(401, "登录状态无效或已过期"),

    // ---------------- Phase 4 商家/店铺/商品/分类域错误码 ----------------

    /** 按用户ID未查到商家资料（当前用户不是商家） */
    MERCHANT_NOT_FOUND(404, "商家信息不存在"),

    /** 按店铺ID未查到店铺 */
    SHOP_NOT_FOUND(404, "店铺不存在"),

    /** 按商品ID未查到商品 */
    PRODUCT_NOT_FOUND(404, "商品不存在"),

    /** 按分类ID未查到分类 */
    CATEGORY_NOT_FOUND(404, "分类不存在"),

    /** 尝试操作不属于当前商家的资源（越权） */
    UNAUTHORIZED_OPERATION(403, "无权操作该资源"),

    // ---------------- Phase 6 收货地址/购物车错误码 ----------------

    /** 按地址ID未查到收货地址 */
    ADDRESS_NOT_FOUND(404, "地址不存在"),

    /** 对已下架商品操作（加购/下单） */
    PRODUCT_OFF_SHELF(409, "商品已下架");

    /** 业务错误码 */
    private final int code;

    /** 面向调用方的默认错误描述 */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

}