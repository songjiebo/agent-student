package com.example.agent.ingestion.store;

/**
 * 向量库写入异常：批量写入重试耗尽后抛出。
 * 任务层将其标记为 FAILED；由于写入采用"先删后写"幂等语义，重试任务不会产生重复数据。
 */
public class IngestWriteException extends RuntimeException {

    public IngestWriteException(String message) {
        super(message);
    }

    public IngestWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
