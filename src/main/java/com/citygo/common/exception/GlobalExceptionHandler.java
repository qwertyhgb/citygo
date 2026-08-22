package com.citygo.common.exception;

import com.citygo.common.result.Result;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器。
 *
 * <p>通过 {@code @RestControllerAdvice} 将控制器抛出的各类异常统一转换为
 * {@link Result} 响应，保证所有接口的返回结构一致（HTTP 状态码恒为 200，
 * 成败由 {@code Result.code} 表达）。</p>
 *
 * <p>日志分级约定：业务预期内异常（参数错误、资源不存在等）记 WARN，
 * 系统级未预期异常记 ERROR 并打印完整堆栈，便于监控与排查。</p>
 *
 * @author citygo
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常 {@link BizException}。
     * 业务异常属于预期内错误，返回对应业务码，日志记 WARN。
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.error(e.getErrorCode());
    }

    /**
     * 处理请求体字段校验失败 {@link MethodArgumentNotValidException}
     * （常见于 {@code @Valid} + {@code @NotBlank} 等注解）。
     * 取第一条字段错误，返回形如 "name: 不能为空" 的提示，日志记 WARN。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null
                ? ErrorCode.BAD_REQUEST.getMessage()
                : fieldError.getField() + ": " + fieldError.getDefaultMessage();
        log.warn("参数校验失败: {}", message);
        return Result.error(ErrorCode.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理参数约束校验失败 {@link ConstraintViolationException}
     * （常见于类路径下 {@code @Validated} 或方法校验）。
     * 归一为参数错误，返回通用文案，日志记 WARN。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        log.warn("参数约束校验失败: {}", e.getMessage());
        return Result.error(ErrorCode.BAD_REQUEST);
    }

    /**
     * 处理请求体无法读取（JSON 格式错误、空请求体等）{@link HttpMessageNotReadableException}。
     * 返回明确的"请求体格式错误"提示，日志记 WARN。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体格式错误: {}", e.getMessage());
        return Result.error(ErrorCode.BAD_REQUEST.getCode(), "请求体格式错误");
    }

    /**
     * 处理路径变量 / 请求参数类型不匹配 {@link MethodArgumentTypeMismatchException}。
     * 返回"参数类型错误"提示，日志记 WARN。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误: name={}, value={}", e.getName(), e.getValue());
        return Result.error(ErrorCode.BAD_REQUEST.getCode(), "参数类型错误");
    }

    /**
     * 处理静态资源 / 未知资源未找到 {@link NoResourceFoundException}。
     * 返回 404，日志记 WARN。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("资源不存在: method={}, path={}", e.getHttpMethod(), e.getResourcePath());
        return Result.error(ErrorCode.NOT_FOUND);
    }

    /**
     * 兜底异常：处理所有未在上方规则覆盖的异常。
     *
     * <p>为什么不直接返回 {@code e.getMessage()}？</p>
     * <ul>
     *   <li>异常详情（堆栈、SQL、内部字段）可能泄露内部实现与安全隐患，暴露给调用方风险高；</li>
     *   <li>调用方需要的是稳定的、可读的统一文案，而非易变的内部错误串。</li>
     * </ul>
     * 因此这里只返回统一兜底文案，同时以 ERROR 级别打印完整堆栈，便于开发与运维定位问题。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常: ", e);
        return Result.error(ErrorCode.INTERNAL_ERROR);
    }

}