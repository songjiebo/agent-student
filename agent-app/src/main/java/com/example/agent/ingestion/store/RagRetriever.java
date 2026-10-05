package com.example.agent.ingestion.store;

import com.example.agent.ingestion.model.ChunkRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 父子检索器：用子 chunk（小而准）做向量召回，命中后回填父 chunk（整节上下文）。
 *
 * <p>这是父子切分在查询侧的另一半价值：embedding 匹配精度来自小块，
 * LLM 生成答案的上下文完整性来自大块。同时输出溯源信息（文档名/页码/章节）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagRetriever {

    private final VectorStore vectorStore;

    /**
     * 检索结果：命中 chunk + 回填的父上下文 + 溯源信息。
     *
     * @param chunkId     命中的子 chunkId
     * @param parentText  回填的父 chunk 文本（LLM 上下文）
     * @param docName     文档名
     * @param pageNo      页码
     * @param sectionPath 章节路径
     * @param score       相似度得分
     */
    public record RetrievedChunk(String chunkId, String parentText, String docName,
                                 Object pageNo, String sectionPath, Double score) {
    }

    /**
     * 父子检索：子 chunk 召回 → 父 chunk 回填。
     *
     * @param query 用户问题
     * @param docId 可选，限定单文档范围（null 表示全库）
     * @param topK  召回条数
     * @return 检索结果（含父上下文与溯源）
     */
    public List<RetrievedChunk> search(String query, String docId, int topK) {
        Filter.Expression expression = childFilter(docId);
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(expression)
                .build();
        List<Document> children = vectorStore.similaritySearch(request);
        if (children == null || children.isEmpty()) {
            return List.of();
        }

        // 收集父 chunkId（保持去重与顺序），批量回填父上下文
        Map<String, Document> parentsByChild = fetchParents(children);
        List<RetrievedChunk> results = new ArrayList<>(children.size());
        for (Document child : children) {
            Document parent = parentsByChild.get(child.getId());
            results.add(new RetrievedChunk(
                    child.getId(),
                    parent != null ? parent.getText() : child.getText(),
                    String.valueOf(child.getMetadata().get(MetaKeys.DOC_NAME)),
                    child.getMetadata().get(MetaKeys.PAGE_NO),
                    String.valueOf(child.getMetadata().get(MetaKeys.SECTION_PATH)),
                    child.getScore()));
        }
        return results;
    }

    /**
     * 逐个父 chunkId 查询父 chunk（父数量 = topK 以内，量级可控）。
     */
    private Map<String, Document> fetchParents(List<Document> children) {
        Map<String, Document> result = new LinkedHashMap<>();
        for (Document child : children) {
            Object parentId = child.getMetadata().get(MetaKeys.PARENT_CHUNK_ID);
            if (parentId == null || parentId.toString().isBlank() || result.containsKey(parentId.toString())) {
                continue;
            }
            Filter.Expression expression = new Filter.Expression(
                    Filter.ExpressionType.EQ,
                    new Filter.Key(MetaKeys.PARENT_CHUNK_ID),
                    new Filter.Value(parentId.toString()));
            List<Document> parents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(child.getText())
                    .topK(1)
                    .filterExpression(expression)
                    .build());
            if (parents != null && !parents.isEmpty()) {
                result.put(parentId.toString(), parents.get(0));
            }
        }
        return result;
    }

    /**
     * 检索过滤：仅召回子 chunk；可选按文档限定范围。
     */
    private Filter.Expression childFilter(String docId) {
        Filter.Expression roleFilter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(MetaKeys.ROLE),
                new Filter.Value(ChunkRole.CHILD.name().toLowerCase()));
        if (docId == null || docId.isBlank()) {
            return roleFilter;
        }
        Filter.Expression docFilter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key(MetaKeys.DOC_ID),
                new Filter.Value(docId));
        return new Filter.Expression(Filter.ExpressionType.AND, roleFilter, docFilter);
    }

    /**
     * metadata 键常量（与 MilvusIngestWriter 保持一致）。
     */
    private static final class MetaKeys {
        private static final String DOC_ID = MilvusIngestWriter.META_DOC_ID;
        private static final String DOC_NAME = MilvusIngestWriter.META_DOC_NAME;
        private static final String PAGE_NO = MilvusIngestWriter.META_PAGE_NO;
        private static final String SECTION_PATH = MilvusIngestWriter.META_SECTION_PATH;
        private static final String PARENT_CHUNK_ID = MilvusIngestWriter.META_PARENT_CHUNK_ID;
        private static final String ROLE = MilvusIngestWriter.META_ROLE;

        private MetaKeys() {
        }
    }
}
