package com.map.mbtiles.controller;

import com.map.mbtiles.model.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.servlet.http.HttpServletRequest;
import org.apache.catalina.connector.ClientAbortException;
import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 全局统一 RESTful 异常处理器（兼容 Java 8）
 * 拦截并统一包装各类 Web 异常为标准 ApiErrorResponse 结构，杜绝框架默认 HTML 报错与堆栈泄露
 * 显式声明 produces = application/json，彻底杜绝请求 .png/.pbf 等非文本路由时报 HttpMessageNotWritableException
 */
@RestControllerAdvice
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理 URL 路径参数或查询参数类型不匹配异常（如 z/x/y 传入了非数字字符）
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        String paramName = ex.getName();
        String expectedType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "未知类型";
        Object actualValue = ex.getValue();

        String message = String.format("参数 '%s' 类型不匹配: 传入值为 '%s'，预期类型为 %s",
                paramName, actualValue, expectedType);
        log.warn("客户端请求参数类型错误 [{}]: {}", request.getRequestURI(), message);

        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * 处理缺少必要请求参数异常
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {

        String message = String.format("缺少必填请求参数: '%s' (类型: %s)", ex.getParameterName(), ex.getParameterType());
        log.warn("客户端请求缺少必要参数 [{}]: {}", request.getRequestURI(), message);

        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * 处理 HTTP 请求 Method 不支持异常（如对 GET 接口发 POST）
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        String message = String.format("当前接口不支持 HTTP %s 请求，仅支持: %s",
                ex.getMethod(), String.join(", ", ex.getSupportedMethods() != null ? ex.getSupportedMethods() : new String[0]));
        log.warn("不支持的 HTTP 请求方法 [{}]: {}", request.getRequestURI(), message);

        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase(),
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * 处理 Content-Type 媒体类型不支持异常
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {

        String message = String.format("不支持的媒体类型 (Content-Type): %s", ex.getContentType());
        log.warn("不支持的媒体类型 [{}]: {}", request.getRequestURI(), message);

        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.getReasonPhrase(),
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * 处理非法参数业务异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {

        log.warn("非法业务参数请求 [{}]: {}", request.getRequestURI(), ex.getMessage());

        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * 处理客户端主动切断连接异常（如地图快速拖拽/滚轮缩放时前端 AbortController 取消未下完的切片，或用户关闭标签页）
     * 对应 Windows 错误: 您的主机中的软件中止了一个已建立的连接 (WSAECONNABORTED 10053)
     * 对应 Linux 错误: Broken pipe / Connection reset by peer
     * 此类情况属于客户端在地图交互时的正常行为，降级为 DEBUG 日志并静默结束，避免 ERROR 刷屏与二次写入已关闭 Socket
     */
    @ExceptionHandler({ClientAbortException.class, IOException.class})
    public void handleClientAbort(Exception ex, HttpServletRequest request) {
        if (isClientAbortException(ex)) {
            log.debug("客户端主动中止了瓦片请求连接 [{}]: {}", request.getRequestURI(), ex.getMessage());
            return;
        }
        // 如果是其他非连接中断的真实 I/O 异常，才作为警告记录
        log.warn("处理请求发生 I/O 异常 [{}]: {}", request.getRequestURI(), ex.getMessage());
    }

    /**
     * 兜底捕获所有未被处理的系统异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(
            Exception ex, HttpServletRequest request) {

        // 针对被其他外层包装类包装的客户端主动中断连接，执行静默短路，避免红色堆栈刷屏
        if (isClientAbortException(ex)) {
            log.debug("客户端主动中止了瓦片请求连接 [{}]: {}", request.getRequestURI(), ex.getMessage());
            return null;
        }

        log.error("系统处理请求发生未捕获异常 [{}]: {}", request.getRequestURI(), ex.getMessage(), ex);

        String detailMessage = (ex.getMessage() != null && !ex.getMessage().trim().isEmpty())
                ? ex.getMessage()
                : "服务器内部处理异常，请查看服务端运行日志";

        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                detailMessage,
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * 判断异常链中是否包含客户端主动断开连接相关的错误
     */
    private boolean isClientAbortException(Throwable ex) {
        if (ex == null) {
            return false;
        }
        if (ex instanceof ClientAbortException) {
            return true;
        }
        String msg = ex.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("中止")
                    || lower.contains("aborted")
                    || lower.contains("broken pipe")
                    || lower.contains("connection reset")
                    || lower.contains("connection was abort")) {
                return true;
            }
        }
        return isClientAbortException(ex.getCause());
    }
}
