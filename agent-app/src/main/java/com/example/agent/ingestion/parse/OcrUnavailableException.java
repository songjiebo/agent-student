package com.example.agent.ingestion.parse;

/**
 * OCR 引擎不可用异常（原生库加载失败 / 平台不支持 / 模型缺失）。
 * 该异常表示"策略不可用"而非"文档有问题"，路由层捕获后自动降级到下一策略。
 */
public class OcrUnavailableException extends RuntimeException {

    public OcrUnavailableException(String message) {
        super(message);
    }

    public OcrUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
