package com.example.agent.ingestion.parse;

import com.example.agent.ingestion.config.AgentIngestProperties;
import com.example.agent.ingestion.model.BlockType;
import com.example.agent.ingestion.model.DocBlock;
import com.example.agent.ingestion.model.ParsedDocument;
import com.example.agent.ingestion.precheck.PdfProbeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MinerU 外部解析服务策略：复杂版式（跨栏表格、公式、密集票据）的兜底解析。
 * MinerU 产出 Markdown，本策略负责把 Markdown 归一为 {@link DocBlock} 流。
 *
 * <p>已知限制：MinerU Markdown 产物不携带页码，本策略产出的块 pageNum 记为 0（未知），
 * 溯源信息退化为章节路径；如需页码级溯源，应在 P2 引入 middle_json 产物解析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MineruPdfParser implements PdfParseStrategy {

    /** 策略名 */
    public static final String NAME = "mineru";

    /** Markdown 标题行 */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    /** 无序/有序列表项 */
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*([-*+] |\\d+[.、)])\\s+.*$");

    /** 图片引用行（图片内容不进文本索引） */
    private static final Pattern IMAGE_LINE = Pattern.compile("^!\\[.*]\\(.*\\)$");

    private final AgentIngestProperties properties;
    private final MineruApiClient mineruApiClient;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public boolean supports(PdfProbeResult probe) {
        return properties.getMineru().isEnabled();
    }

    @Override
    public ParsedDocument parse(Path file, PdfProbeResult probe) {
        String markdown = mineruApiClient.parseToMarkdown(file);
        return ParsedDocument.builder()
                .strategyName(NAME)
                .pageCount(probe.pageCount())
                .blocks(toBlocks(markdown))
                .build();
    }

    /**
     * Markdown → 块流归一化：标题/列表/表格/段落分类，连续表格行聚合、连续段落行合并。
     *
     * @param markdown MinerU 产出的 Markdown
     * @return 块列表（按文档顺序）
     */
    private List<DocBlock> toBlocks(String markdown) {
        List<DocBlock> blocks = new ArrayList<>();
        String[] lines = markdown.split("\\r?\\n", -1);

        StringBuilder paragraph = new StringBuilder();
        StringBuilder table = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine.stripTrailing();
            String trimmed = line.trim();

            // 表格行连续聚合为一个 TABLE 块（表格整体保留，避免行切碎破坏结构）
            if (trimmed.startsWith("|")) {
                flushParagraph(blocks, paragraph);
                table.append(trimmed).append('\n');
                continue;
            }
            flushTable(blocks, table);

            if (IMAGE_LINE.matcher(trimmed).matches()) {
                // 图片引用行不进文本索引（图片内容理解属于多模态扩展范畴）
                continue;
            }
            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                flushParagraph(blocks, paragraph);
                blocks.add(DocBlock.builder()
                        .type(BlockType.TITLE)
                        .text(heading.group(2).trim())
                        .pageNum(0)
                        .headingLevel(heading.group(1).length())
                        .build());
                continue;
            }
            if (LIST_ITEM.matcher(line).matches()) {
                flushParagraph(blocks, paragraph);
                blocks.add(DocBlock.builder().type(BlockType.LIST_ITEM).text(trimmed).pageNum(0).build());
                continue;
            }
            if (trimmed.isEmpty()) {
                flushParagraph(blocks, paragraph);
                continue;
            }
            // 正文行：跨行合并为段落（换行还原为空格）
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(trimmed);
        }
        flushTable(blocks, table);
        flushParagraph(blocks, paragraph);
        return blocks;
    }

    /**
     * 段落缓冲冲刷：非空时落一个 PARAGRAPH 块。
     */
    private void flushParagraph(List<DocBlock> blocks, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            blocks.add(DocBlock.builder()
                    .type(BlockType.PARAGRAPH)
                    .text(paragraph.toString())
                    .pageNum(0)
                    .build());
            paragraph.setLength(0);
        }
    }

    /**
     * 表格缓冲冲刷：非空时落一个 TABLE 块（Markdown 表格文本）。
     */
    private void flushTable(List<DocBlock> blocks, StringBuilder table) {
        if (table.length() > 0) {
            blocks.add(DocBlock.builder()
                    .type(BlockType.TABLE)
                    .text(table.toString().stripTrailing())
                    .pageNum(0)
                    .build());
            table.setLength(0);
        }
    }
}
