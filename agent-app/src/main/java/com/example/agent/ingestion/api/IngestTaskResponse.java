package com.example.agent.ingestion.api;

import com.example.agent.ingestion.task.IngestTask;

import java.time.LocalDateTime;

/**
 * 任务查询响应 DTO。
 */
public record IngestTaskResponse(
        String taskId,
        String docId,
        String fileName,
        long fileSize,
        Integer pageCount,
        String status,
        String stage,
        String strategy,
        Double qualityScore,
        Integer chunkCount,
        String errorMsg,
        int retryCount,
        String pipelineVersion,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    /**
     * 实体 → DTO。
     *
     * @param task 任务实体
     * @return 响应 DTO
     */
    public static IngestTaskResponse from(IngestTask task) {
        return new IngestTaskResponse(
                task.getTaskId(), task.getDocId(), task.getFileName(), task.getFileSize(),
                task.getPageCount(), task.getStatus().name(), task.getStage(), task.getStrategy(),
                task.getQualityScore(), task.getChunkCount(), task.getErrorMsg(), task.getRetryCount(),
                task.getPipelineVersion(), task.getCreateTime(), task.getUpdateTime());
    }
}
