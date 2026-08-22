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
    INTERNAL_ERROR(500, "服务器内部错误");

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