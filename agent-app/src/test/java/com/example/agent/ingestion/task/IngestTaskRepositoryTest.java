package com.example.agent.ingestion.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务仓储测试：H2 内存库 + 真实建表脚本（与生产 schema 同源，防止脚本与代码漂移）。
 */
class IngestTaskRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private IngestTaskRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(
                new org.h2.Driver(), "jdbc:h2:mem:ingest_task_test;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new IngestTaskRepository(jdbcTemplate);

        // 用真实 schema 脚本建表（classpath 同源），保证 DDL 与实体映射不漂移
        try (InputStream in = getClass().getResourceAsStream("/schema-ingest-task.sql")) {
            String schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // 先剥离 SQL 注释行，再按分号切分执行（注释块混入语句会导致整句被跳过）
            String withoutComments = schema.lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b);
            for (String statement : withoutComments.split(";")) {
                if (!statement.isBlank()) {
                    jdbcTemplate.execute(statement);
                }
            }
        }
        jdbcTemplate.execute("DELETE FROM ingest_task");
    }

    private IngestTask newTask(String taskId, String sha) {
        return IngestTask.builder()
                .taskId(taskId)
                .docId("doc-1")
                .fileName("a.pdf")
                .filePath("/tmp/a.pdf")
                .fileSha256(sha)
                .fileSize(100)
                .status(IngestTaskStatus.PENDING)
                .stage("ACCEPTED")
                .retryCount(0)
                .pipelineVersion("v1")
                .build();
    }

    @Test
    @DisplayName("任务插入与查询")
    void insertAndFind() {
        repository.insert(newTask("t1", "sha-1"));
        IngestTask found = repository.findById("t1").orElseThrow();
        assertEquals("doc-1", found.getDocId());
        assertEquals(IngestTaskStatus.PENDING, found.getStatus());
        assertEquals("v1", found.getPipelineVersion());
    }

    @Test
    @DisplayName("判重：按 hash 查找已完成任务")
    void findsCompletedBySha256() {
        repository.insert(newTask("t1", "sha-1"));
        repository.updateResult("t1", IngestTaskStatus.COMPLETED, 12, null);

        Optional<IngestTask> hit = repository.findCompletedBySha256("sha-1");
        assertTrue(hit.isPresent());
        assertEquals("t1", hit.get().getTaskId());

        // 未完成的任务不参与判重
        repository.insert(newTask("t2", "sha-2"));
        assertTrue(repository.findCompletedBySha256("sha-2").isEmpty());
    }

    @Test
    @DisplayName("失败原因超长时截断到 2000 字符")
    void truncatesErrorMessage() {
        repository.insert(newTask("t1", "sha-1"));
        repository.updateResult("t1", IngestTaskStatus.FAILED, null, "x".repeat(5000));
        assertEquals(2000, repository.findById("t1").orElseThrow().getErrorMsg().length());
    }

    @Test
    @DisplayName("重试：计数 +1 并清空错误信息")
    void marksPendingForRetry() {
        repository.insert(newTask("t1", "sha-1"));
        repository.updateResult("t1", IngestTaskStatus.FAILED, null, "boom");
        repository.markPendingForRetry("t1");

        IngestTask found = repository.findById("t1").orElseThrow();
        assertEquals(IngestTaskStatus.PENDING, found.getStatus());
        assertEquals(1, found.getRetryCount());
        assertNull(found.getErrorMsg());
    }

    @Test
    @DisplayName("文档级删除清理全部任务记录")
    void deletesByDocId() {
        repository.insert(newTask("t1", "sha-1"));
        repository.insert(newTask("t2", "sha-2"));
        assertEquals(2, repository.deleteByDocId("doc-1"));
        assertTrue(repository.findByDocId("doc-1").isEmpty());
    }
}
