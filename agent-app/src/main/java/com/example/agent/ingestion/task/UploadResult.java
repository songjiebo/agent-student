package com.example.agent.ingestion.task;

/**
 * 上传受理结果。
 *
 * @param taskId 任务 ID（复用时为首次入库的任务 ID）
 * @param docId  文档 ID
 * @param status 当前任务状态
 * @param reused true 表示内容 hash 命中已有文档，直接复用（判重幂等）
 */
public record UploadResult(String taskId, String docId, String status, boolean reused) {
}
