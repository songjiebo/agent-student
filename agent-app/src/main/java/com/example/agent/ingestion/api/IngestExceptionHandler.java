package com.example.agent.ingestion.api;

import com.example.agent.ingestion.precheck.IngestRejectedException;
import com.example.agent.ingestion.store.IngestWriteException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 入库 API 异常映射：把业务异常翻译为语义明确的 HTTP 响应，避免堆栈泄露。
 */
@Slf4j
@RestControllerAdvice(assignableTypes = DocIngestController.class)
public class IngestExceptionHandler {

    /**
     * 预检拒绝（伪装文件/超限/加密）：400，不可重试。
     */
    @ExceptionHandler(IngestRejectedException.class)
    public ResponseEntity<Map<String, String>> handleRejected(IngestRejectedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "REJECTED", "message", String.valueOf(e.getMessage())));
    }

    /**
     * 参数/资源不存在：404。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "NOT_FOUND", "message", String.valueOf(e.getMessage())));
    }

    /**
     * 状态冲突（如非可重试状态发起重试）：409。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "CONFLICT", "message", String.valueOf(e.getMessage())));
    }

    /**
     * 向量写入失败：502（上游 embedding/向量库问题，任务已标记 FAILED 可重试）。
     */
    @ExceptionHandler(IngestWriteException.class)
    public ResponseEntity<Map<String, String>> handleWriteFailure(IngestWriteException e) {
        log.error("向量写入失败", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "WRITE_FAILED", "message", String.valueOf(e.getMessage())));
    }
}
