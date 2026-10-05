package com.example.agent.ingestion.parse;

/**
 * MinerU 外部解析服务不可用异常（未部署 / 网络不可达 / 服务异常）。
 * 与 {@link OcrUnavailableException} 同理：属于"策略不可用"，路由层降级处理。
 */
public class MineruUnavailableException extends RuntimeException {

    public MineruUnavailableException(String message) {
        super(message);
    }

    public MineruUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
