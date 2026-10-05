package com.example.agent.ingestion.task;

/**
 * 入库任务状态机。
 *
 * <pre>
 * PENDING → PARSING → CHUNKING → EMBEDDING → COMPLETED
 *                                      ↘ NEED_REVIEW（低质量入库，提示人工复核）
 *   任意阶段异常 → FAILED（可人工重试，幂等语义保证重试不产生重复数据）
 * </pre>
 */
public enum IngestTaskStatus {

    /** 已受理，等待执行 */
    PENDING,

    /** 解析中（含预检/探测/策略路由） */
    PARSING,

    /** 结构化 + 切分中 */
    CHUNKING,

    /** embedding + 向量写入中 */
    EMBEDDING,

    /** 成功入库 */
    COMPLETED,

    /** 解析质量低于阈值，已入库但需人工复核 */
    NEED_REVIEW,

    /** 失败（可重试） */
    FAILED
}
