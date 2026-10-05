package com.example.agent.ingestion.precheck;

/**
 * 入库预检拒绝异常：安全/限额类问题（伪装文件、超限、加密 PDF 等）。
 * 该异常不可重试，直接对用户返回明确原因，不进入任务重试链路。
 */
public class IngestRejectedException extends RuntimeException {

    public IngestRejectedException(String message) {
        super(message);
    }

    public IngestRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
