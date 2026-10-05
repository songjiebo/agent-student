package com.example.agent.ingestion.store;

import com.example.agent.ingestion.config.AgentIngestProperties;
import com.example.agent.ingestion.model.Chunk;
import com.example.agent.ingestion.model.ChunkRole;
import com.openai.errors.OpenAIServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量写入器测试：批大小切分、可重试错误退避、确定性错误快速失败。
 * 覆盖真实事故模式：batch-size 超过 embedding 服务上限时的 400 参数错误不做无意义重试。
 */
class MilvusIngestWriterTest {

    /** 可编程的假 VectorStore：记录调用次数与批大小，可注入失败 */
    private static final class FakeVectorStore implements VectorStore {

        private final List<Integer> batchSizes = new ArrayList<>();
        private int addCalls;
        private RuntimeException error;
        /** 前 N 次调用失败后恢复 */
        private int failFirstCalls;

        @Override
        public void add(List<Document> documents) {
            addCalls++;
            batchSizes.add(documents.size());
            if (error != null && addCalls <= failFirstCalls) {
                throw error;
            }
        }

        @Override
        public void delete(List<String> ids) {
            // 测试不覆盖
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            // 测试不覆盖
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of();
        }
    }

    /** 模拟 OpenAI SDK 的 HTTP 状态类异常（400/401/429/5xx 共用基类） */
    private static final class FakeServiceException extends OpenAIServiceException {

        private final int statusCode;

        private FakeServiceException(int statusCode, String message) {
            super(message, null);
            this.statusCode = statusCode;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public com.openai.core.http.Headers headers() {
            return null;
        }

        @Override
        public com.openai.core.JsonValue body() {
            return null;
        }

        @Override
        public Optional<String> code() {
            return Optional.empty();
        }

        @Override
        public Optional<String> param() {
            return Optional.empty();
        }

        @Override
        public Optional<String> type() {
            return Optional.empty();
        }
    }

    private AgentIngestProperties properties(int batchSize, int maxRetries) {
        AgentIngestProperties properties = new AgentIngestProperties();
        properties.getEmbed().setBatchSize(batchSize);
        properties.getEmbed().setMaxRetries(maxRetries);
        properties.getEmbed().setInitialBackoffMs(1);
        return properties;
    }

    private List<Chunk> chunks(int count) {
        List<Chunk> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(Chunk.builder()
                    .chunkId("doc-1#p" + i + "c0")
                    .role(ChunkRole.PARENT)
                    .text("chunk-" + i)
                    .pageNum(1)
                    .chunkIndex(0)
                    .sectionPath("一、总则")
                    .blockType("TEXT")
                    .build());
        }
        return list;
    }

    @Test
    @DisplayName("按 batch-size 切分批次写入")
    void writesInConfiguredBatches() {
        FakeVectorStore store = new FakeVectorStore();
        MilvusIngestWriter writer = new MilvusIngestWriter(store, properties(2, 3));

        writer.replaceDocument("doc-1", "a.pdf", "sha", chunks(5));

        assertEquals(3, store.addCalls);
        assertEquals(List.of(2, 2, 1), store.batchSizes);
    }

    @Test
    @DisplayName("确定性错误（400 参数错误）快速失败：不重试，立即透出根因")
    void failsFastOnDeterministicError() {
        FakeVectorStore store = new FakeVectorStore();
        store.error = new FakeServiceException(400, "batch size is invalid, it should not be larger than 20");
        store.failFirstCalls = Integer.MAX_VALUE;
        MilvusIngestWriter writer = new MilvusIngestWriter(store, properties(20, 3));

        IngestWriteException ex = assertThrows(IngestWriteException.class,
                () -> writer.replaceDocument("doc-1", "a.pdf", "sha", chunks(3)));

        assertEquals(1, store.addCalls, "不可重试错误只应调用一次");
        assertTrue(ex.getMessage().contains("不可重试"), "错误信息应明确标注不可重试");
        assertTrue(ex.getMessage().contains("400"), "错误信息应透出状态码/根因");
    }

    @Test
    @DisplayName("可重试错误（网络超时）按最大次数退避重试后抛出")
    void retriesTransientError() {
        FakeVectorStore store = new FakeVectorStore();
        store.error = new RuntimeException(new SocketTimeoutException("read timeout"));
        store.failFirstCalls = Integer.MAX_VALUE;
        MilvusIngestWriter writer = new MilvusIngestWriter(store, properties(20, 3));

        assertThrows(IngestWriteException.class,
                () -> writer.replaceDocument("doc-1", "a.pdf", "sha", chunks(1)));

        assertEquals(3, store.addCalls, "可重试错误应重试到上限");
    }

    @Test
    @DisplayName("限流（429）与服务端错误（5xx）视为可重试")
    void treatsRateLimitAndServerErrorAsRetryable() {
        for (int status : new int[]{429, 500, 503}) {
            FakeVectorStore store = new FakeVectorStore();
            store.error = new FakeServiceException(status, "transient");
            store.failFirstCalls = Integer.MAX_VALUE;
            MilvusIngestWriter writer = new MilvusIngestWriter(store, properties(20, 2));

            assertThrows(IngestWriteException.class,
                    () -> writer.replaceDocument("doc-1", "a.pdf", "sha", chunks(1)));
            assertEquals(2, store.addCalls, "状态 " + status + " 应重试");
        }
    }

    @Test
    @DisplayName("瞬态失败后恢复：任务不失败")
    void recoversAfterTransientFailure() {
        FakeVectorStore store = new FakeVectorStore();
        store.error = new RuntimeException(new SocketTimeoutException("read timeout"));
        store.failFirstCalls = 1;
        MilvusIngestWriter writer = new MilvusIngestWriter(store, properties(20, 3));

        writer.replaceDocument("doc-1", "a.pdf", "sha", chunks(2));

        assertEquals(2, store.addCalls, "第一次失败后重试成功");
    }

    @Test
    @DisplayName("未知异常（如 Milvus 插入失败）默认有界重试，且错误信息透出完整 cause 链")
    void retriesUnknownErrorAndReportsCauseChain() {
        FakeVectorStore store = new FakeVectorStore();
        // 复现真实事故形态：根因挂在 cause 上，外层消息为空
        store.error = new RuntimeException("Failed to insert:",
                new IllegalStateException("the length of field doc_id exceeds max length (36)"));
        store.failFirstCalls = Integer.MAX_VALUE;
        MilvusIngestWriter writer = new MilvusIngestWriter(store, properties(20, 2));

        IngestWriteException ex = assertThrows(IngestWriteException.class,
                () -> writer.replaceDocument("doc-1", "a.pdf", "sha", chunks(1)));

        assertEquals(2, store.addCalls, "未知异常应有界重试");
        assertTrue(ex.getMessage().contains("Failed to insert"), "错误信息应含外层消息");
        assertTrue(ex.getMessage().contains("exceeds max length"), "错误信息应含根因消息（cause 链）");
    }
}
