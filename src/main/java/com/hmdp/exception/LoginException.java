package com.hmdp.exception;

public class LoginException extends BusinessException {

    public LoginException(int code, String message) {
        super(code, message);
    }

    public LoginException(String message) {
        super(message);
    }

    public LoginException(ExceptionDetail detail) {
        super(detail);
    }
}
