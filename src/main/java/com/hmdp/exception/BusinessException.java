package com.hmdp.exception;

public class BusinessException extends RuntimeException {

    private int code;
    private String message;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(ExceptionDetail detail) {
        super(detail.getMessage());
        this.code = (int) detail.getCode();
    }

    public int getCode() {
        return code;
    }
}
