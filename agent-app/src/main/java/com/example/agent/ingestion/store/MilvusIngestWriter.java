package com.example.agent.ingestion.store;

import com.example.agent.ingestion.config.AgentIngestProperties;
import com.example.agent.ingestion.model.Chunk;
import com.example.agent.ingestion.model.ChunkRole;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.OpenAIRetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Milvus 写入器：幂等写入 + 批量 embedding + 指数退避重试。
 *
 * <p>幂等策略（向量库没有事务，"先删后写 + 确定性 chunkId"是业界标准做法）：
 * <ol>
 *   <li>按 docId 表达式删除旧向量（覆盖重刷/失败重试的所有历史残留）；</li>
 *   <li>chunkId 确定性生成，重复写入同内容天然覆盖而非堆积。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusIngestWriter {

    /** metadata 键：文档 ID（幂等/过滤键） */
    public static final String META_DOC_ID = "docId";
    /** metadata 键：文档名（引用溯源展示） */
    public static final String META_DOC_NAME = "docName";
    /** metadata 键：页码 */
    public static final String META_PAGE_NO = "pageNo";
    /** metadata 键：章节路径 */
    public static final String META_SECTION_PATH = "sectionPath";
    /** metadata 键：父 chunk ID（父子检索关联键） */
    public static final String META_PARENT_CHUNK_ID = "parentChunkId";
    /** metadata 键：chunk 角色 parent/child */
    public static final String META_ROLE = "role";
    /** metadata 键：主导块类型 */
    public static final String META_BLOCK_TYPE = "blockType";
    /** metadata 键：文件 hash（增量/审计） */
    public static final String META_FILE_HASH = "fileHash";
    /** metadata 键：入库时间 */
    public static final String META_INGEST_TIME = "ingestTime";

    private final VectorStore vectorStore;
    private final AgentIngestProperties properties;

    /**
     * 以"整文档替换"语义写入 chunk：先删旧向量，再分批写入。
     *
     * @param docId   文档 ID
     * @param docName 文档名（展示/引用）
     * @param fileHash 文件内容 hash
     * @param chunks  父子 chunk 列表
     */
    public void replaceDocument(String docId, String docName, String fileHash, List<Chunk> chunks) {
        deleteByDocId(docId);
        if (chunks.isEmpty()) {
            log.warn("文档 {} 没有产出任何 chunk，跳过写入", docId);
            return;
        }
        List<Document> documents = chunks.stream()
                .map(chunk -> toDocument(docId, docName, fileHash, chunk))
                .toList();
        writeInBatches(documents);
    }

    /**
     * 删除文档全部向量（重刷前置 + 文档删除）。
     * 失败统一包装为 {@link IngestWriteException}，保证任务错误信息口径一致。
     *
     * @param docId 文档 ID
     */
    public void deleteByDocId(String docId) {
        try {
            vectorStore.delete(new Filter.Expression(
                    Filter.ExpressionType.EQ,
                    new Filter.Key(META_DOC_ID),
                    new Filter.Value(docId)));
        } catch (RuntimeException e) {
            throw new IngestWriteException("向量删除失败: " + brief(e), e);
        }
        log.info("已清理文档旧向量: docId={}", docId);
    }

    /**
     * 分批写入：批大小受 embedding 服务单次条数上限约束；批内失败按指数退避重试。
     */
    private void writeInBatches(List<Document> documents) {
        int batchSize = properties.getEmbed().getBatchSize();
        for (int from = 0; from < documents.size(); from += batchSize) {
            List<Document> batch = documents.subList(from, Math.min(from + batchSize, documents.size()));
            addWithRetry(batch);
        }
        log.info("文档向量写入完成: chunks={}, batches={}", documents.size(),
                (documents.size() + batchSize - 1) / batchSize);
    }

    /**
     * 单批写入重试。
     * 关键语义：只有"可重试错误"（网络/超时/限流/5xx）才退避重试；
     * 参数类错误（400/401/404 等）是确定性失败，立即失败并透出根因，不做无意义重试。
     */
    private void addWithRetry(List<Document> batch) {
        int maxRetries = properties.getEmbed().getMaxRetries();
        long backoff = properties.getEmbed().getInitialBackoffMs();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                vectorStore.add(batch);
                return;
            } catch (RuntimeException e) {
                last = e;
                if (!isRetryable(e)) {
                    // 不可重试：快速失败，让问题立刻暴露（如批量超限、密钥错误）
                    throw new IngestWriteException("向量写入遇到不可重试错误: " + brief(e), e);
                }
                log.warn("向量写入失败（第 {}/{} 次），{}ms 后重试: {}", attempt, maxRetries, backoff, brief(e));
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IngestWriteException("向量写入被中断", ie);
                }
                backoff *= 2;
            }
        }
        throw new IngestWriteException("向量写入重试耗尽: " + brief(last), last);
    }

    /**
     * 可重试性判定（遍历 cause 链）：
     * <ul>
     *   <li>可重试：OpenAI SDK 显式标记的瞬态异常 / IO 网络异常 / 限流(429) / 服务端错误(5xx)；</li>
     *   <li>不可重试：其余服务端返回的确定性错误（400 参数错误、401 鉴权失败等），默认快速失败。</li>
     * </ul>
     * 宁可少重试也不空转：确定性错误重试多少次结果都一样，只会拖慢失败反馈并放大故障。
     */
    private boolean isRetryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            // SDK 显式瞬态异常 + 网络 IO 级异常
            if (t instanceof OpenAIRetryableException || t instanceof OpenAIIoException
                    || t instanceof org.springframework.web.client.ResourceAccessException
                    || t instanceof java.io.InterruptedIOException
                    || t instanceof java.net.ConnectException) {
                return true;
            }
            // HTTP 状态类异常：仅限流与服务端错误可重试
            if (t instanceof OpenAIServiceException serviceException) {
                int status = serviceException.statusCode();
                return status == 429 || status >= 500;
            }
        }
        return false;
    }

    /**
     * 异常摘要：根因类名 + （HTTP 状态码）+ 消息，便于任务错误信息直接定位问题（无需翻堆栈）。
     * 状态码显式输出而非依赖异常消息文本——消息格式由 SDK 决定，不稳定。
     */
    private String brief(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        if (error instanceof OpenAIServiceException serviceException) {
            return error.getClass().getSimpleName() + "(status=" + serviceException.statusCode()
                    + "): " + error.getMessage();
        }
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    /**
     * Chunk → Spring AI Document：全文进 text，溯源信息进 metadata。
     */
    private Document toDocument(String docId, String docName, String fileHash, Chunk chunk) {
        return Document.builder()
                .id(chunk.getChunkId())
                .text(chunk.getText())
                .metadata(META_DOC_ID, docId)
                .metadata(META_DOC_NAME, docName)
                .metadata(META_PAGE_NO, chunk.getPageNum())
                .metadata(META_SECTION_PATH, chunk.getSectionPath())
                .metadata(META_PARENT_CHUNK_ID,
                        chunk.getRole() == ChunkRole.CHILD ? chunk.getParentChunkId() : "")
                .metadata(META_ROLE, chunk.getRole().name().toLowerCase())
                .metadata(META_BLOCK_TYPE, chunk.getBlockType())
                .metadata(META_FILE_HASH, fileHash)
                .metadata(META_INGEST_TIME, Instant.now().toString())
                .build();
    }
}
