package com.example.agent.ingestion.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;

/**
 * multipart 上传层异常映射（全局 advice，不限定 controller）。
 *
 * <p>为什么是全局而不是 {@code @RestControllerAdvice(assignableTypes = DocIngestController.class)}：
 * multipart 解析发生在 DispatcherServlet 的 checkMultipart 阶段（handler 映射之前），
 * 异常抛出时 handler 尚未确定，带 assignableTypes 限定的 advice 匹配不到 handlerType=null 的场景，
 * 异常会穿透到容器层，返回空响应体的 413，客户端无法区分"上传超限"与其他错误。
 * 全局 advice 在 handler 为空时同样生效，保证两种解析时机（预解析/参数绑定期）都能拿到语义化响应。
 */
@Slf4j
@RestControllerAdvice
public class MultipartSizeExceptionHandler {

    /**
     * 上传体积超限（servlet 层粗闸拦截）：413，不可重试。
     *
     * @param e 超限异常
     * @return 413 + 语义化错误码
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUpload(MaxUploadSizeExceededException e) {
        log.warn("上传体积超过 servlet 层限制: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "PAYLOAD_TOO_LARGE", "message", "上传文件超过服务端限制"));
    }

    /**
     * 其他 multipart 解析失败（请求体截断、boundary 损坏等）：400，不可重试。
     *
     * @param e multipart 异常
     * @return 400 + 语义化错误码
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> handleMultipart(MultipartException e) {
        log.warn("multipart 请求解析失败", e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "MULTIPART_ERROR", "message", "multipart 请求解析失败"));
    }
}
