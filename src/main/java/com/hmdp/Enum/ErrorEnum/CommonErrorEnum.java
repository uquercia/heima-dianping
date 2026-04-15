package com.hmdp.Enum.ErrorEnum;

import com.hmdp.exception.ExceptionDetail;
import lombok.Getter;

@Getter
public enum CommonErrorEnum implements ExceptionDetail {
    SYSTEM_ERROR(500, "系统异常"),
    ILLEGAL_ARGUMENT(400, "请求参数错误"),
    ILLEGAL_STATE(409, "状态冲突"),
    UNAUTHORIZED(401, "未认证"),
    FORBIDDEN(403, "无权限");

    private final int code;
    private final String message;

    CommonErrorEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public long getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
