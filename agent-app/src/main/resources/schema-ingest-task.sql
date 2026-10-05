-- =====================================================================
-- 文档入库任务表（异步任务状态机 + 质检报告）
-- 说明：H2 兼容语法；生产环境迁移 MySQL 时仅需调整自增/索引方言。
-- =====================================================================

CREATE TABLE IF NOT EXISTS ingest_task (
    task_id          VARCHAR(64)   PRIMARY KEY,          -- 任务主键（UUID）
    doc_id           VARCHAR(64)   NOT NULL,             -- 文档逻辑主键（同 hash 复用，幂等键）
    file_name        VARCHAR(512)  NOT NULL,             -- 原始文件名（已消毒）
    file_path        VARCHAR(1024) NOT NULL,             -- 落盘路径（服务端生成，不使用用户路径）
    file_sha256      CHAR(64)      NOT NULL,             -- 文件内容 hash，用于判重与增量
    file_size        BIGINT        NOT NULL,             -- 字节数
    page_count       INT,                                -- 页数（预检阶段写入）
    status           VARCHAR(32)   NOT NULL,             -- 任务状态：见 IngestTaskStatus
    stage            VARCHAR(32),                        -- 当前/最后执行阶段
    strategy         VARCHAR(64),                        -- 实际命中的解析策略
    quality_score    DECIMAL(5, 3),                      -- 解析质量分 0.000-1.000
    chunk_count      INT,                                -- 写入向量库的 chunk 数
    error_msg        VARCHAR(2000),                      -- 失败原因（截断保存，防止爆列）
    retry_count      INT          DEFAULT 0,             -- 人工重试次数
    pipeline_version VARCHAR(32)  NOT NULL,              -- 算法版本，支持批量重刷
    create_time      TIMESTAMP    NOT NULL,
    update_time      TIMESTAMP    NOT NULL
);

-- 按文档维度检索任务历史（重刷、删除联查）
CREATE INDEX IF NOT EXISTS idx_ingest_task_doc ON ingest_task (doc_id);
-- 按内容 hash 判重（同 hash 且已完成 → 直接复用 doc_id）
CREATE INDEX IF NOT EXISTS idx_ingest_task_sha ON ingest_task (file_sha256);
