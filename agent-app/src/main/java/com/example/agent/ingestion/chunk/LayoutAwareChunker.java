package com.example.agent.ingestion.chunk;

import com.example.agent.ingestion.config.AgentIngestProperties;
import com.example.agent.ingestion.model.BlockType;
import com.example.agent.ingestion.model.Chunk;
import com.example.agent.ingestion.model.ChunkRole;
import com.example.agent.ingestion.model.DocBlock;
import com.example.agent.ingestion.model.DocSection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 布局感知的父子 chunk 切分器。
 *
 * <p>两级结构动机（相对纯字符窗口切分的三个关键改进）：
 * <ul>
 *   <li>标题路径前缀：chunk 文本携带 "第3章 > 3.2" 上下文，embedding 与用户 query 的语义对齐更强；</li>
 *   <li>子 chunk（CHILD）小而准负责检索命中，父 chunk（PARENT）整节回填给 LLM，答案上下文完整；</li>
 *   <li>表格整块保留：表格跨 chunk 硬切会直接导致数字问答出错。</li>
 * </ul>
 * 切分只在句子边界断开；chunkId 确定性生成，同文档重复入库产出相同 ID（幂等前提）。
 */
@Component
@RequiredArgsConstructor
public class LayoutAwareChunker {

    /** 句末标点（用于切分点选择） */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?；;])");

    /** Markdown 表格表头行数（表头 + 分隔行），超长表格切分时每段重复表头 */
    private static final int TABLE_HEADER_LINES = 2;

    private final AgentIngestProperties properties;

    /**
     * 章节 → 父子 chunk。
     *
     * @param docId    文档 ID（chunkId 前缀，幂等键）
     * @param sections 结构化章节
     * @return 父 chunk + 子 chunk 列表（父在前、子按序在后）
     */
    public List<Chunk> chunk(String docId, List<DocSection> sections) {
        List<Chunk> chunks = new ArrayList<>();
        AgentIngestProperties.Chunk cfg = properties.getChunk();
        int parentSeq = 0;

        for (DocSection section : sections) {
            String sectionPath = String.join(" > ", section.getHeadingPath());
            List<BlockText> blocks = toBlockTexts(section.getBlocks());

            // 章节内容先按父粒度打包（超过 maxParentChars 的章节二次拆分）
            for (List<BlockText> parentGroup : pack(blocks, cfg.getMaxParentChars())) {
                parentSeq++;
                String parentChunkId = docId + "#p" + parentSeq + "c0";
                String parentBody = joinBlocks(parentGroup);

                chunks.add(Chunk.builder()
                        .chunkId(parentChunkId)
                        .parentChunkId(null)
                        .role(ChunkRole.PARENT)
                        .text(withSectionPrefix(sectionPath, parentBody))
                        .pageNum(firstPage(parentGroup))
                        .chunkIndex(0)
                        .sectionPath(sectionPath)
                        .blockType(dominantType(parentGroup))
                        .build());

                // 父内再按子粒度打包：子 chunk 是 embedding 检索单元
                int childSeq = 0;
                for (List<BlockText> childGroup : pack(parentGroup, cfg.getMaxChildChars())) {
                    childSeq++;
                    chunks.add(Chunk.builder()
                            .chunkId(parentChunkId + "c" + childSeq)
                            .parentChunkId(parentChunkId)
                            .role(ChunkRole.CHILD)
                            .text(withSectionPrefix(sectionPath, joinBlocks(childGroup)))
                            .pageNum(firstPage(childGroup))
                            .chunkIndex(childSeq)
                            .sectionPath(sectionPath)
                            .blockType(dominantType(childGroup))
                            .build());
                }
            }
        }
        return chunks;
    }

    /**
     * 块打包：贪心累积到目标长度；超长块（表格/长段落）预切分后作为原子参与打包。
     *
     * @param blocks   块列表
     * @param maxChars 单组最大字符数
     * @return 打包后的块组
     */
    private List<List<BlockText>> pack(List<BlockText> blocks, int maxChars) {
        List<List<BlockText>> groups = new ArrayList<>();
        List<BlockText> current = new ArrayList<>();
        int currentChars = 0;

        for (BlockText block : blocks) {
            // 超长块预切分（表格按行组切分并重复表头；段落按句子边界切分）
            for (BlockText part : splitOversized(block, maxChars)) {
                int partChars = part.text().length();
                if (!current.isEmpty() && currentChars + partChars > maxChars) {
                    groups.add(current);
                    current = new ArrayList<>();
                    currentChars = 0;
                }
                current.add(part);
                currentChars += partChars;
            }
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * 超长块预切分：表格按行切并重复表头；段落按句子边界硬切；其余按长度硬切。
     */
    private List<BlockText> splitOversized(BlockText block, int maxChars) {
        if (block.text().length() <= maxChars) {
            return List.of(block);
        }
        if (block.type() == BlockType.TABLE) {
            return splitTable(block, maxChars);
        }
        return splitBySentences(block, maxChars);
    }

    /**
     * 表格切分：每段携带表头 + 分隔行，保证片段脱离上下文后仍自解释。
     */
    private List<BlockText> splitTable(BlockText block, int maxChars) {
        String[] lines = block.text().split("\\r?\\n", -1);
        if (lines.length <= TABLE_HEADER_LINES) {
            return splitBySentences(block, maxChars);
        }
        List<BlockText> parts = new ArrayList<>();
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < Math.min(TABLE_HEADER_LINES, lines.length); i++) {
            header.append(lines[i]).append('\n');
        }
        StringBuilder body = new StringBuilder(header);
        for (int i = TABLE_HEADER_LINES; i < lines.length; i++) {
            String candidate = body + lines[i] + '\n';
            if (body.length() > header.length() && candidate.length() > maxChars) {
                parts.add(new BlockText(BlockType.TABLE, body.toString().stripTrailing(), block.pageNum()));
                body = new StringBuilder(header);
            }
            body.append(lines[i]).append('\n');
        }
        if (body.length() > header.length()) {
            parts.add(new BlockText(BlockType.TABLE, body.toString().stripTrailing(), block.pageNum()));
        }
        return parts.isEmpty() ? List.of(block) : parts;
    }

    /**
     * 段落按句子边界切分：切分点优先落在句末标点后。
     */
    private List<BlockText> splitBySentences(BlockText block, int maxChars) {
        List<BlockText> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE_END.split(block.text())) {
            if (sentence.isBlank()) {
                continue;
            }
            if (current.length() > 0 && current.length() + sentence.length() > maxChars) {
                parts.add(new BlockText(block.type(), current.toString().trim(), block.pageNum()));
                current = new StringBuilder();
            }
            if (sentence.length() > maxChars) {
                // 单句仍超长（URL/长编号等）：按长度硬切，保证不出现超限 chunk
                for (int offset = 0; offset < sentence.length(); offset += maxChars) {
                    parts.add(new BlockText(block.type(),
                            sentence.substring(offset, Math.min(offset + maxChars, sentence.length())).trim(),
                            block.pageNum()));
                }
                continue;
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            parts.add(new BlockText(block.type(), current.toString().trim(), block.pageNum()));
        }
        return parts.isEmpty() ? List.of(block) : parts;
    }

    /**
     * 组内块拼接：段落间空行分隔，列表/表格独立成段。
     */
    private String joinBlocks(List<BlockText> group) {
        StringBuilder sb = new StringBuilder();
        for (BlockText block : group) {
            if (sb.length() > 0) {
                sb.append('\n').append(block.type() == BlockType.TABLE || block.type() == BlockType.LIST_ITEM ? "\n" : "");
            }
            sb.append(block.text());
        }
        return sb.toString().strip();
    }

    /**
     * 标题路径前缀：chunk 的"自解释上下文"，检索对齐的关键。
     */
    private String withSectionPrefix(String sectionPath, String body) {
        if (sectionPath == null || sectionPath.isBlank()) {
            return body;
        }
        return sectionPath + "\n\n" + body;
    }

    private int firstPage(List<BlockText> group) {
        return group.stream()
                .mapToInt(BlockText::pageNum)
                .filter(p -> p > 0)
                .min()
                .orElse(1);
    }

    /**
     * 主导块类型：纯表格组标记 TABLE，其余标记 TEXT。
     */
    private String dominantType(List<BlockText> group) {
        boolean allTable = group.stream().allMatch(b -> b.type() == BlockType.TABLE);
        return allTable ? BlockType.TABLE.name() : "TEXT";
    }

    private List<BlockText> toBlockTexts(List<DocBlock> blocks) {
        List<BlockText> result = new ArrayList<>(blocks.size());
        for (DocBlock block : blocks) {
            if (block.getText() != null && !block.getText().isBlank()) {
                result.add(new BlockText(block.getType(), block.getText().strip(), block.getPageNum()));
            }
        }
        return result;
    }

    /**
     * 切分内部的轻量块模型（类型 + 文本 + 页码）。
     */
    private record BlockText(BlockType type, String text, int pageNum) {
    }
}
