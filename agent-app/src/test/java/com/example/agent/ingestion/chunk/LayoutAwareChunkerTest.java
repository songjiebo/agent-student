package com.example.agent.ingestion.chunk;

import com.example.agent.ingestion.config.AgentIngestProperties;
import com.example.agent.ingestion.model.BlockType;
import com.example.agent.ingestion.model.Chunk;
import com.example.agent.ingestion.model.ChunkRole;
import com.example.agent.ingestion.model.DocBlock;
import com.example.agent.ingestion.model.DocSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 父子 chunk 切分测试：确定性 ID、表格整块保留、超长表格重复表头、标题路径前缀。
 */
class LayoutAwareChunkerTest {

    private final LayoutAwareChunker chunker = new LayoutAwareChunker(new AgentIngestProperties());

    private DocSection section(List<String> path, List<DocBlock> blocks) {
        return DocSection.builder().headingPath(path).title(path.isEmpty() ? "" : path.get(path.size() - 1))
                .blocks(blocks).startPage(1).build();
    }

    private DocBlock paragraph(String text) {
        return DocBlock.builder().type(BlockType.PARAGRAPH).text(text).pageNum(1).build();
    }

    @Test
    @DisplayName("产出父子两级 chunk，子 chunk 带标题路径前缀")
    void buildsParentChildStructure() {
        List<DocSection> sections = List.of(section(
                List.of("第3章 收费标准"),
                List.of(paragraph("年度签约客户享受九折优惠，具体以合同约定为准。".repeat(5)))));

        List<Chunk> chunks = chunker.chunk("doc-1", sections);
        List<Chunk> parents = chunks.stream().filter(c -> c.getRole() == ChunkRole.PARENT).toList();
        List<Chunk> children = chunks.stream().filter(c -> c.getRole() == ChunkRole.CHILD).toList();

        assertTrue(!parents.isEmpty() && !children.isEmpty());
        assertTrue(children.get(0).getText().startsWith("第3章 收费标准"), "子 chunk 应带标题路径前缀");
        assertEquals(parents.get(0).getChunkId(), children.get(0).getParentChunkId());
    }

    @Test
    @DisplayName("chunkId 确定性生成：同输入两次切分结果一致（幂等前提）")
    void generatesDeterministicIds() {
        List<DocSection> sections = List.of(section(
                List.of("一、总则"),
                List.of(paragraph("第一条 目的。"), paragraph("第二条 依据。"))));
        List<Chunk> first = chunker.chunk("doc-1", sections);
        List<Chunk> second = chunker.chunk("doc-1", sections);

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getChunkId(), second.get(i).getChunkId());
        }
    }

    @Test
    @DisplayName("表格整块保留为单个 TABLE chunk")
    void keepsTableWhole() {
        String table = "| 套餐 | 月费 |\n| --- | --- |\n| 经济版 | 29 |\n| 标准版 | 59 |";
        List<DocSection> sections = List.of(section(
                List.of("资费表"),
                List.of(DocBlock.builder().type(BlockType.TABLE).text(table).pageNum(1).build())));

        List<Chunk> children = chunker.chunk("doc-1", sections).stream()
                .filter(c -> c.getRole() == ChunkRole.CHILD).toList();
        assertEquals(1, children.size());
        assertEquals("TABLE", children.get(0).getBlockType());
        assertTrue(children.get(0).getText().contains("标准版"));
    }

    @Test
    @DisplayName("chunkId 长度不超过 Milvus 主键 VARCHAR(36) 限制（超长会导致插入被拒）")
    void keepsChunkIdWithinPrimaryKeyLimit() {
        // docId 为 36 位 UUID 时最容易超限，用满长 docId 验证
        String longDocId = "46a33d67-8c64-35a6-a58d-7a7ca1147fed";
        List<DocBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            blocks.add(paragraph("第" + i + "段内容用于生成足够多的父子 chunk，验证 chunkId 长度约束。"));
        }
        List<Chunk> chunks = chunker.chunk(longDocId, List.of(section(List.of("约束验证"), blocks)));
        for (Chunk chunk : chunks) {
            assertTrue(chunk.getChunkId().length() <= 36,
                    "chunkId 超过 36 字符会导致 Milvus 插入失败: " + chunk.getChunkId());
            if (chunk.getParentChunkId() != null) {
                assertTrue(chunk.getParentChunkId().length() <= 36);
            }
        }
    }

    @Test
    @DisplayName("超长表格切分后每个片段重复表头，列结构不丢失")
    void splitsOversizedTableWithHeader() {
        StringBuilder table = new StringBuilder("| 项目 | 金额 |\n| --- | --- |\n");
        for (int i = 0; i < 120; i++) {
            table.append("| 项目").append(i).append(" | ").append(i * 10).append(" |\n");
        }
        List<DocSection> sections = List.of(section(
                List.of("大额清单"),
                List.of(DocBlock.builder().type(BlockType.TABLE).text(table.toString().stripTrailing()).pageNum(1).build())));

        List<Chunk> children = chunker.chunk("doc-1", sections).stream()
                .filter(c -> c.getRole() == ChunkRole.CHILD).toList();
        assertTrue(children.size() > 1, "超长表格应被切分");
        for (Chunk child : children) {
            assertTrue(child.getText().contains("| 项目 | 金额 |"), "每个表格片段应携带表头");
            assertTrue(child.getText().length() <= new AgentIngestProperties().getChunk().getMaxChildChars() + 200,
                    "chunk 长度不应大幅超出上限");
        }
    }

    @Test
    @DisplayName("子 chunk 长度受上限约束")
    void respectsMaxChildChars() {
        List<DocBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            blocks.add(paragraph("这是第" + i + "段较长的正文内容，用于验证切分器对 chunk 尺寸的约束是否生效。"));
        }
        List<Chunk> children = chunker.chunk("doc-1", List.of(section(List.of("多段文档"), blocks))).stream()
                .filter(c -> c.getRole() == ChunkRole.CHILD).toList();

        int maxChars = new AgentIngestProperties().getChunk().getMaxChildChars();
        for (Chunk child : children) {
            // 前缀（标题路径）会使长度略超，容忍 200 字符
            assertTrue(child.getText().length() <= maxChars + 200,
                    "chunk 超长: " + child.getText().length());
        }
    }
}
