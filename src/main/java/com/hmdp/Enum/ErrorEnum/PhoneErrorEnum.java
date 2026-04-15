package com.hmdp.Enum.ErrorEnum;

import com.hmdp.exception.ExceptionDetail;
import lombok.Getter;

@Getter
public enum PhoneErrorEnum implements ExceptionDetail {
    PHONE_INVALID(40001, "手机号格式错误"),
    CODE_INVALID(40002, "验证码错误"),
    CODE_EMPTY(40003, "验证码为空"),
    CODE_EXPIRED(40004, "验证码已过期");

    private final int code;
    private final String message;

    PhoneErrorEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public long getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
