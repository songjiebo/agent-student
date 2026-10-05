package com.example.agent.ingestion.task;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 入库任务实体（对应表 ingest_task）。
 * 任务记录同时承担"进度可见性"与"审计"职责：策略、质量分、chunk 数全部落表。
 */
@Getter
@Setter
@Builder
public class IngestTask {

    /** 任务 ID（UUID） */
    private String taskId;

    /** 文档 ID（内容 hash 派生，幂等键） */
    private String docId;

    /** 消毒后的展示文件名 */
    private String fileName;

    /** 服务端落盘路径 */
    private String filePath;

    /** 文件内容 SHA-256 */
    private String fileSha256;

    /** 文件字节数 */
    private long fileSize;

    /** 页数（预检阶段写入） */
    private Integer pageCount;

    /** 任务状态 */
    private IngestTaskStatus status;

    /** 当前/最后阶段 */
    private String stage;

    /** 实际命中的解析策略 */
    private String strategy;

    /** 解析质量分 */
    private Double qualityScore;

    /** 写入 chunk 数 */
    private Integer chunkCount;

    /** 失败原因（截断保存） */
    private String errorMsg;

    /** 人工重试次数 */
    private int retryCount;

    /** 解析/切分算法版本 */
    private String pipelineVersion;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
