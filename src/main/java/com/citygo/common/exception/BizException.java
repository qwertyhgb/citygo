package com.citygo.common.exception;

/**
 * 业务异常。
 *
 * <p>业务异常表示<b>预期内的、可被业务规则解释的异常</b>（如参数不合法、资源不存在、
 * 无操作权限等），通常由业务代码主动抛出。与之相对的是系统异常（如空指针、数据库连接失败等），
 * 这类异常属于程序缺陷或环境故障，不应被业务代码捕获并向调用方包装成业务错误。</p>
 *
 * <p>业务异常与系统异常分离的意义：</p>
 * <ul>
 *   <li>全局异常处理器可分别处理：业务异常用 WARN 日志记录并返回对应业务码，提示信息可安全暴露；</li>
 *   <li>系统异常用 ERROR 日志记录完整堆栈便于排查，返回统一兜底文案，避免向调用方泄露内部细节；</li>
 *   <li>运维与前端都能据此区分"需修复的业务逻辑"和"需定位的系统故障"。</li>
 * </ul>
 *
 * @author citygo
 */
public class BizException extends RuntimeException {

    /** 对应的业务错误码 */
    private final ErrorCode errorCode;

    /**
     * 使用错误码默认文案构造业务异常。
     */
    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 使用错误码 + 自定义描述构造业务异常，用于补充比默认文案更具体的提示。
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

}