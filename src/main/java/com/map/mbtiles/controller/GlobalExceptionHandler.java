package com.map.mbtiles.controller;

import org.apache.catalina.connector.ClientAbortException;
import org.apache.coyote.CloseNowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.sql.SQLException;

/**
 * 全局统一异常处理器
 * 确保所有错误均返回标准 HTTP 响应，杜绝浏览器报 Failed to fetch；
 * 针对地图快速缩放/平移时产生的客户端正常中断进行静默降级处理，避免日志刷屏。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 路径参数类型不匹配（例如 z/x/y 传入非整数字符串）
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("非法路径参数: {} = '{}' — 预期类型 {}",
                ex.getName(), ex.getValue(), ex.getRequiredType());
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body("非法请求参数: " + ex.getName());
    }

    /**
     * 非法参数异常（如检测到恶意路径穿越符号或格式错误）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("非法请求: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body(ex.getMessage());
    }

    /**
     * 数据库连接与查询异常 — 记录堆栈并返回 503，便于客户端自动重试
     */
    @ExceptionHandler(SQLException.class)
    public ResponseEntity<String> handleSqlException(SQLException ex) {
        log.error("瓦片数据库连接或查询异常: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.TEXT_PLAIN)
                .body("瓦片数据库暂时不可用");
    }

    /**
     * 客户端在地图平移、快速缩放时主动取消尚未完成的瓦片请求
     * 包括 HTTP/2 流重置（CloseNowException）与 HTTP/1.1 客户端断开（ClientAbortException），
     * 均属于地图前端正常交互行为，降级为 DEBUG 日志静默处理，不再打印 ERROR 错误堆栈。
     */
    @ExceptionHandler({CloseNowException.class, ClientAbortException.class})
    public void handleClientCancellation(Exception ex) {
        log.debug("客户端主动中止未完成的瓦片传输（地图快速平移或缩放取消）: {}", ex.getMessage());
    }

    /**
     * 常规 I/O 异常 — 识别并静默管道断开（Broken pipe）或连接被对端重置
     */
    @ExceptionHandler(IOException.class)
    public void handleIOException(IOException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if (msg.contains("broken pipe") || msg.contains("connection reset") || msg.contains("closed")) {
            log.debug("客户端网络连接中断: {}", ex.getMessage());
            return;
        }
        log.warn("瓦片网络传输 I/O 异常: {}", ex.getMessage());
    }

    /**
     * 兜底未知系统级异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGenericException(Exception ex) {
        log.error("服务器内部未捕获异常: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body("服务器内部错误");
    }
}
