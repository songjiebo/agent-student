package com.example.agent.ingestion.store;

import com.example.agent.ingestion.config.AgentIngestProperties;
import com.example.agent.ingestion.model.Chunk;
import com.example.agent.ingestion.model.ChunkRole;
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
     *
     * @param docId 文档 ID
     */
    public void deleteByDocId(String docId) {
        vectorStore.delete(new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(META_DOC_ID),
                new Filter.Value(docId)));
        log.info("已清理文档旧向量: docId={}", docId);
    }

    /**
     * 分批写入：批大小受 embedding 服务 QPM 约束；批内失败按指数退避重试。
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
     * 单批写入 + 指数退避重试：embedding 服务限流/抖动的常规容错。
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
                log.warn("向量写入失败（第 {}/{} 次），{}ms 后重试: {}", attempt, maxRetries, backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IngestWriteException("向量写入被中断", ie);
                }
                backoff *= 2;
            }
        }
        throw new IngestWriteException("向量写入重试耗尽: " + last.getMessage(), last);
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
