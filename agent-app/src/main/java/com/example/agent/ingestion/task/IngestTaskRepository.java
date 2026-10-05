package com.example.agent.ingestion.task;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 入库任务仓储（JdbcTemplate + 显式 RowMapper）。
 * 显式映射而非 BeanPropertyRowMapper：时间戳/枚举转换行为可预期，便于迁移 MySQL。
 */
@Repository
@RequiredArgsConstructor
public class IngestTaskRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 显式行映射：字段与 ingest_task 表一一对应 */
    private static final RowMapper<IngestTask> ROW_MAPPER = (rs, rowNum) -> IngestTask.builder()
            .taskId(rs.getString("task_id"))
            .docId(rs.getString("doc_id"))
            .fileName(rs.getString("file_name"))
            .filePath(rs.getString("file_path"))
            .fileSha256(rs.getString("file_sha256"))
            .fileSize(rs.getLong("file_size"))
            .pageCount(getInteger(rs, "page_count"))
            .status(IngestTaskStatus.valueOf(rs.getString("status")))
            .stage(rs.getString("stage"))
            .strategy(rs.getString("strategy"))
            .qualityScore(getDouble(rs, "quality_score"))
            .chunkCount(getInteger(rs, "chunk_count"))
            .errorMsg(rs.getString("error_msg"))
            .retryCount(rs.getInt("retry_count"))
            .pipelineVersion(rs.getString("pipeline_version"))
            .createTime(toLocalDateTime(rs.getTimestamp("create_time")))
            .updateTime(toLocalDateTime(rs.getTimestamp("update_time")))
            .build();

    /**
     * 新建任务。
     */
    public void insert(IngestTask task) {
        LocalDateTime now = LocalDateTime.now();
        task.setCreateTime(now);
        task.setUpdateTime(now);
        jdbcTemplate.update("""
                INSERT INTO ingest_task
                    (task_id, doc_id, file_name, file_path, file_sha256, file_size, page_count,
                     status, stage, strategy, quality_score, chunk_count, error_msg,
                     retry_count, pipeline_version, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                task.getTaskId(), task.getDocId(), task.getFileName(), task.getFilePath(),
                task.getFileSha256(), task.getFileSize(), task.getPageCount(),
                task.getStatus().name(), task.getStage(), task.getStrategy(), task.getQualityScore(),
                task.getChunkCount(), task.getErrorMsg(), task.getRetryCount(),
                task.getPipelineVersion(), task.getCreateTime(), task.getUpdateTime());
    }

    /**
     * 按任务 ID 查询。
     */
    public Optional<IngestTask> findById(String taskId) {
        List<IngestTask> tasks = jdbcTemplate.query(
                "SELECT * FROM ingest_task WHERE task_id = ?", ROW_MAPPER, taskId);
        return tasks.stream().findFirst();
    }

    /**
     * 判重：按内容 hash 查找已完成任务（同 hash 直接复用 docId，跳过重复解析）。
     */
    public Optional<IngestTask> findCompletedBySha256(String sha256) {
        List<IngestTask> tasks = jdbcTemplate.query(
                "SELECT * FROM ingest_task WHERE file_sha256 = ? AND status IN ('COMPLETED', 'NEED_REVIEW') "
                        + "ORDER BY create_time DESC",
                ROW_MAPPER, sha256);
        return tasks.stream().findFirst();
    }

    /**
     * 按文档 ID 查询任务历史（重刷/删除审计）。
     */
    public List<IngestTask> findByDocId(String docId) {
        return jdbcTemplate.query(
                "SELECT * FROM ingest_task WHERE doc_id = ? ORDER BY create_time DESC", ROW_MAPPER, docId);
    }

    /**
     * 更新任务进度（阶段流转 + 页数）。
     */
    public void updateProgress(String taskId, IngestTaskStatus status, String stage, Integer pageCount) {
        jdbcTemplate.update("""
                UPDATE ingest_task
                   SET status = ?, stage = ?, page_count = COALESCE(?, page_count), update_time = ?
                 WHERE task_id = ?
                """, status.name(), stage, pageCount, LocalDateTime.now(), taskId);
    }

    /**
     * 更新解析结果（策略 + 质量分）。
     */
    public void updateParseResult(String taskId, String strategy, Double qualityScore) {
        jdbcTemplate.update("""
                UPDATE ingest_task
                   SET strategy = ?, quality_score = ?, update_time = ?
                 WHERE task_id = ?
                """, strategy, qualityScore, LocalDateTime.now(), taskId);
    }

    /**
     * 终态更新（成功/复核/失败）。
     */
    public void updateResult(String taskId, IngestTaskStatus status, Integer chunkCount, String errorMsg) {
        String truncated = truncate(errorMsg, 2000);
        jdbcTemplate.update("""
                UPDATE ingest_task
                   SET status = ?, chunk_count = ?, error_msg = ?, update_time = ?
                 WHERE task_id = ?
                """, status.name(), chunkCount, truncated, LocalDateTime.now(), taskId);
    }

    /**
     * 重试计数 +1 并置回 PENDING。
     */
    public void markPendingForRetry(String taskId) {
        jdbcTemplate.update("""
                UPDATE ingest_task
                   SET status = 'PENDING', retry_count = retry_count + 1, error_msg = NULL, update_time = ?
                 WHERE task_id = ?
                """, LocalDateTime.now(), taskId);
    }

    /**
     * 删除文档全部任务记录（文档级删除）。
     */
    public int deleteByDocId(String docId) {
        return jdbcTemplate.update("DELETE FROM ingest_task WHERE doc_id = ?", docId);
    }

    private static Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double getDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /**
     * 错误信息截断：防止超长堆栈信息撑爆字段。
     */
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
