package com.citygo.common.result;

import com.citygo.common.exception.ErrorCode;

/**
 * 统一返回结构 {@code Result<T>}。
 *
 * <p>为什么需要统一返回结构：</p>
 * <ul>
 *   <li>前端只需处理一种固定的数据格式，无需为每个接口单独适配，降低联调成本；</li>
 *   <li>错误语义统一收敛到 code / message，前端能据此统一做弹窗、跳转等逻辑。</li>
 * </ul>
 *
 * <p>本项目设计取舍：<b>HTTP 状态码始终保持 200</b>，真正的业务成败信息放在 {@code Result.code}。
 * 这样网络传输层与业务层解耦，网关、日志、监控无需关心业务状态，前端在 HTTP 200 的基础上
 * 再依据 code 分流成功与各业务错误，配合全局异常处理器可避免把异常堆栈一类细节暴露给调用方。</p>
 *
 * <p>本类禁止直接 new，只能通过静态工厂方法创建实例。</p>
 *
 * @param <T> 业务返回值类型；无返回时可为 {@code Void}
 * @author citygo
 */
public class Result<T> {

    /** 业务状态码；200 表示成功，其余见 {@link ErrorCode} 及各业务阶段扩展码 */
    private int code;

    /** 状态描述信息；预期是面向调用方的、可直接展示的文案 */
    private String message;

    /** 业务数据；允许为 null，且序列化为 JSON 时仍会输出 {@code "data": null}，保证结构稳定 */
    private T data;

    /**
     * 私有构造器：禁止外部直接 new，强制走静态工厂，保证返回结构语义一致。
     */
    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功（无数据）：code=200、message="success"、data=null。
     */
    public static <T> Result<T> success() {
        return new Result<>(200, "success", null);
    }

    /**
     * 成功（带数据）：code=200、message="success"。
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }

    /**
     * 成功（自定义描述与数据）：code=200。
     */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data);
    }

    /**
     * 失败：根据 {@link ErrorCode} 生成对应业务码与文案。
     */
    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 失败：直接指定业务码与文案（用于不宜引用 {@link ErrorCode} 枚举的兜底/临时场景）。
     */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

}