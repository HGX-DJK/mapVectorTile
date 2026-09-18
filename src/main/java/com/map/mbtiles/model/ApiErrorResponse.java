package com.map.mbtiles.model;

import java.io.Serializable;

/**
 * 全局统一 RESTful API 错误响应模型（兼容 Java 8）
 */
public final class ApiErrorResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** HTTP 状态码（如 400, 404, 500） */
    private final int status;
    /** HTTP 错误简短描述（如 "Bad Request", "Not Found"） */
    private final String error;
    /** 人类可读的详细错误提示信息 */
    private final String message;
    /** 产生异常的请求 URI 路径 */
    private final String path;
    /** 异常产生的系统时间戳（毫秒） */
    private final long timestamp;

    public ApiErrorResponse(int status, String error, String message, String path) {
        this(status, error, message, path, System.currentTimeMillis());
    }

    public ApiErrorResponse(int status, String error, String message, String path, long timestamp) {
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
        this.timestamp = timestamp;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getPath() {
        return path;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "ApiErrorResponse{" +
                "status=" + status +
                ", error='" + error + '\'' +
                ", message='" + message + '\'' +
                ", path='" + path + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
