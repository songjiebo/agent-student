package com.example.agent.ingestion.layout;

import com.example.agent.ingestion.model.BlockType;
import com.example.agent.ingestion.model.DocBlock;
import com.example.agent.ingestion.model.DocSection;
import com.example.agent.ingestion.model.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 版面结构化：把解析策略产出的"行/块流"还原为带标题层级的章节树。
 *
 * <p>处理步骤（顺序敏感）：
 * <ol>
 *   <li>页眉页脚剔除：跨页重复行 + 页码行识别（版式噪音是高频低质 chunk 的主要来源）；</li>
 *   <li>表格聚合：多列对齐的连续行聚合为 Markdown 表格（表格塌陷是数字问答幻觉的主因）；</li>
 *   <li>列表识别；</li>
 *   <li>段落合并：文本层来源按"行"输出，需还原为语义段落；</li>
 *   <li>标题识别：文本层用字号（≥ 正文 1.15 倍），MinerU 直接携带层级；</li>
 *   <li>章节构建：按标题层级压栈，产出章节树（父 chunk 的切分单位）。</li>
 * </ol>
 */
@Slf4j
@Component
public class LayoutStructurer {

    /** 页码行：纯数字、-3-、第 3 页 等形态 */
    private static final Pattern PAGE_NUMBER_LINE =
            Pattern.compile("^(第\\s*\\d+\\s*页(\\s*/\\s*\\d+)?|[-–—]?\\s*\\d+\\s*[-–—]?|page\\s*\\d+(\\s*/\\s*\\d+)?)$",
                    Pattern.CASE_INSENSITIVE);

    /** 列表项前缀 */
    private static final Pattern LIST_PREFIX = Pattern.compile("^\\s*([-*•·]|\\d{1,3}[.、)])\\s+.*");

    /** 表格列分隔：2 个及以上空白（文本层表格行的典型形态） */
    private static final Pattern COLUMN_GAP = Pattern.compile("\\s{2,}");

    /** 段落合并的最小长度：达到后遇到句末标点即断段 */
    private static final int MIN_PARAGRAPH_CHARS = 60;

    /** 标题识别的字号放大系数 */
    private static final double HEADING_FONT_RATIO = 1.15;

    /** 标题最大长度（超长更可能是正文强调，而非标题） */
    private static final int MAX_HEADING_CHARS = 80;

    /**
     * 结构化主入口。
     *
     * @param parsed 解析产出（扁平块流）
     * @return 章节列表（带标题路径）
     */
    public List<DocSection> structure(ParsedDocument parsed) {
        List<DocBlock> blocks = parsed.getBlocks() == null ? List.of() : parsed.getBlocks();
        List<DocBlock> cleaned = removeHeaderFooter(blocks, parsed.getPageCount());
        List<DocBlock> tableGrouped = groupTables(cleaned);
        List<DocBlock> listed = detectListItems(tableGrouped);
        // 顺序关键：标题识别必须在段落合并之前（合并会吞掉行级字号信息）
        List<DocBlock> withHeadings = detectHeadings(listed);
        List<DocBlock> merged = mergeParagraphs(withHeadings);
        return buildSections(merged);
    }

    /**
     * 页眉页脚剔除：
     * <ul>
     *   <li>页码行直接剔除；</li>
     *   <li>归一化后（压缩空白、数字归一）在 ≥30% 页数（至少 2 页）重复出现的短行剔除。</li>
     * </ul>
     * 基于"跨页重复"而非固定版面区域判定，兼容页眉在不同位置的文档。
     */
    private List<DocBlock> removeHeaderFooter(List<DocBlock> blocks, int pageCount) {
        if (pageCount <= 1 || blocks.isEmpty()) {
            // 单页文档无法靠重复识别页眉页脚，仅剔除页码行
            return blocks.stream()
                    .filter(b -> !isPageNumber(b.getText()))
                    .toList();
        }
        // 统计归一化文本出现过的页集合
        Map<String, java.util.Set<Integer>> pagesByText = new HashMap<>();
        for (DocBlock block : blocks) {
            if (block.getText() == null || block.getText().isBlank()) {
                continue;
            }
            pagesByText.computeIfAbsent(normalize(block.getText()), k -> new java.util.HashSet<>())
                    .add(block.getPageNum());
        }
        int repeatThreshold = Math.max(2, (int) Math.ceil(pageCount * 0.3));

        List<DocBlock> result = new ArrayList<>(blocks.size());
        for (DocBlock block : blocks) {
            String text = block.getText() == null ? "" : block.getText();
            if (text.isBlank() || isPageNumber(text)) {
                continue;
            }
            String key = normalize(text);
            boolean repeatedShortLine = text.length() <= 80
                    && pagesByText.getOrDefault(key, java.util.Set.of()).size() >= repeatThreshold;
            if (repeatedShortLine) {
                continue;
            }
            result.add(block);
        }
        return result;
    }

    /**
     * 表格聚合：把连续的"多列对齐行"聚合为 Markdown 表格块。
     * 仅处理 PARAGRAPH 行块（MinerU 产出的 TABLE 块已是 Markdown，不重复处理）。
     */
    private List<DocBlock> groupTables(List<DocBlock> blocks) {
        List<DocBlock> result = new ArrayList<>(blocks.size());
        List<String> rows = new ArrayList<>();
        int tablePage = 0;

        for (DocBlock block : blocks) {
            List<String> cells = splitRow(block.getText());
            boolean rowLike = block.getType() == BlockType.PARAGRAPH
                    && cells != null && cells.size() >= 2;
            if (rowLike) {
                if (rows.isEmpty()) {
                    tablePage = block.getPageNum();
                }
                rows.add(String.join(" | ", cells));
                continue;
            }
            flushTable(result, rows, tablePage);
            result.add(block);
        }
        flushTable(result, rows, tablePage);
        return result;
    }

    /**
     * 行 → 单元格：按 2+ 空白切分；不足 2 列返回 null（视为普通文本行）。
     */
    private List<String> splitRow(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = COLUMN_GAP.split(text.trim());
        if (parts.length < 2) {
            return null;
        }
        List<String> cells = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isBlank()) {
                cells.add(part.trim());
            }
        }
        return cells.size() >= 2 ? cells : null;
    }

    /**
     * 表格缓冲冲刷：≥2 行才认定为表格（单行多列大概率是排版巧合）。
     */
    private void flushTable(List<DocBlock> result, List<String> rows, int tablePage) {
        if (rows.size() >= 2) {
            result.add(DocBlock.builder()
                    .type(BlockType.TABLE)
                    .text(toMarkdownTable(rows))
                    .pageNum(tablePage)
                    .build());
        } else if (!rows.isEmpty()) {
            // 单行不构成表格，降级为普通段落行
            result.add(DocBlock.builder()
                    .type(BlockType.PARAGRAPH)
                    .text(rows.get(0).replace('|', ' ').replaceAll("\\s+", " ").trim())
                    .pageNum(tablePage)
                    .build());
        }
        rows.clear();
    }

    /**
     * 行列表 → Markdown 表格：首行为表头，补分隔行，列数对齐到表头。
     */
    private String toMarkdownTable(List<String> rows) {
        String[] header = rows.get(0).split("\\s*\\|\\s*");
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", header)).append(" |\n");
        sb.append("|").append(" --- |".repeat(header.length)).append('\n');
        for (int i = 1; i < rows.size(); i++) {
            String[] cells = rows.get(i).split("\\s*\\|\\s*");
            StringBuilder row = new StringBuilder("| ");
            for (int c = 0; c < header.length; c++) {
                row.append(c < cells.length ? cells[c] : "").append(" | ");
            }
            sb.append(row.toString().stripTrailing()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 列表项识别：带项目符号/编号的行标记为 LIST_ITEM。
     */
    private List<DocBlock> detectListItems(List<DocBlock> blocks) {
        List<DocBlock> result = new ArrayList<>(blocks.size());
        for (DocBlock block : blocks) {
            if (block.getType() == BlockType.PARAGRAPH
                    && block.getText() != null
                    && LIST_PREFIX.matcher(block.getText()).matches()) {
                result.add(DocBlock.builder()
                        .type(BlockType.LIST_ITEM)
                        .text(block.getText())
                        .pageNum(block.getPageNum())
                        .fontSize(block.getFontSize())
                        .build());
            } else {
                result.add(block);
            }
        }
        return result;
    }

    /**
     * 段落合并：连续 PARAGRAPH 行合并为语义段落（文本层来源按行输出）。
     * 断段条件：达到最小段长且上一行以句末标点结尾；或遇到非段落块/换页。
     */
    private List<DocBlock> mergeParagraphs(List<DocBlock> blocks) {
        List<DocBlock> result = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        int paragraphPage = 0;
        float paragraphFont = 0f;

        for (DocBlock block : blocks) {
            boolean continuable = block.getType() == BlockType.PARAGRAPH
                    && block.getText() != null && !block.getText().isBlank();
            boolean pageChanged = paragraph.length() > 0 && block.getPageNum() != paragraphPage;
            if (!continuable || (pageChanged && paragraph.length() >= MIN_PARAGRAPH_CHARS)) {
                flushMergedParagraph(result, paragraph, paragraphPage, paragraphFont);
            }
            if (!continuable) {
                result.add(block);
                continue;
            }
            if (paragraph.length() == 0) {
                paragraphPage = block.getPageNum();
                paragraphFont = block.getFontSize();
            }
            appendJoined(paragraph, block.getText());
            paragraphFont = Math.max(paragraphFont, block.getFontSize());
            if (paragraph.length() >= MIN_PARAGRAPH_CHARS && endsWithSentence(block.getText())) {
                flushMergedParagraph(result, paragraph, paragraphPage, paragraphFont);
            }
        }
        flushMergedParagraph(result, paragraph, paragraphPage, paragraphFont);
        return result;
    }

    /**
     * CJK 感知拼接：中日韩字符之间不插空格，其他情况以空格连接。
     */
    private void appendJoined(StringBuilder paragraph, String text) {
        if (paragraph.length() == 0) {
            paragraph.append(text.trim());
            return;
        }
        char last = paragraph.charAt(paragraph.length() - 1);
        char first = text.charAt(0);
        if (isCjk(last) && isCjk(first)) {
            paragraph.append(text.trim());
        } else {
            paragraph.append(' ').append(text.trim());
        }
    }

    private boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private boolean endsWithSentence(String text) {
        String trimmed = text.stripTrailing();
        return !trimmed.isEmpty() && "。！？!?；;".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0;
    }

    private void flushMergedParagraph(List<DocBlock> result, StringBuilder paragraph, int page, float fontSize) {
        if (paragraph.length() > 0) {
            result.add(DocBlock.builder()
                    .type(BlockType.PARAGRAPH)
                    .text(paragraph.toString())
                    .pageNum(page)
                    .fontSize(fontSize)
                    .build());
            paragraph.setLength(0);
        }
    }

    /**
     * 标题识别：
     * <ul>
     *   <li>MinerU 来源：块已携带 headingLevel，直接标记 TITLE；</li>
     *   <li>文本层来源：字号 ≥ 正文基准字号 × 1.15 且长度受限的行视为标题，
     *       层级按字号从大到小映射为 1-3。</li>
     * </ul>
     */
    private List<DocBlock> detectHeadings(List<DocBlock> blocks) {
        float bodySize = bodyFontSize(blocks);

        List<DocBlock> result = new ArrayList<>(blocks.size());
        List<DocBlock> fontSizedHeadings = new ArrayList<>();
        for (DocBlock block : blocks) {
            if (block.getHeadingLevel() > 0) {
                result.add(withType(block, BlockType.TITLE, Math.min(block.getHeadingLevel(), 4)));
                continue;
            }
            boolean headingCandidate = block.getType() == BlockType.PARAGRAPH
                    && block.getFontSize() > 0
                    && bodySize > 0
                    && block.getFontSize() >= bodySize * HEADING_FONT_RATIO
                    && block.getText() != null
                    && block.getText().length() <= MAX_HEADING_CHARS
                    && !endsWithSentence(block.getText());
            if (headingCandidate) {
                // 先落地为 TITLE（层级待第二遍按字号档位补齐）
                DocBlock titled = withType(block, BlockType.TITLE, 0);
                fontSizedHeadings.add(titled);
                result.add(titled);
            } else {
                result.add(block);
            }
        }

        // 字号标题分层：不同字号档位从大到小映射为层级 1、2、3
        List<Float> distinctSizes = fontSizedHeadings.stream()
                .map(DocBlock::getFontSize)
                .distinct()
                .sorted((a, b) -> Float.compare(b, a))
                .toList();
        Map<Float, Integer> levelBySize = new HashMap<>();
        for (int i = 0; i < distinctSizes.size(); i++) {
            levelBySize.put(distinctSizes.get(i), Math.min(i + 1, 3));
        }

        List<DocBlock> leveled = new ArrayList<>(result.size());
        for (DocBlock block : result) {
            if (block.getType() == BlockType.TITLE && block.getHeadingLevel() == 0) {
                leveled.add(withType(block, BlockType.TITLE, levelBySize.getOrDefault(block.getFontSize(), 1)));
            } else {
                leveled.add(block);
            }
        }
        return leveled;
    }

    private DocBlock withType(DocBlock source, BlockType type, int headingLevel) {
        return DocBlock.builder()
                .type(type)
                .text(source.getText())
                .pageNum(source.getPageNum())
                .fontSize(source.getFontSize())
                .headingLevel(headingLevel)
                .build();
    }

    /**
     * 正文基准字号：按"字符数加权的众数"估计。
     * 不能用中位数——标题行在小文档中会把基准抬高，导致标题识别失效；
     * 正文占据绝大多数字符，字符加权众数在标题多的文档中依然稳健。
     */
    private float bodyFontSize(List<DocBlock> blocks) {
        Map<Float, Long> charsBySize = new HashMap<>();
        for (DocBlock block : blocks) {
            if (block.getType() == BlockType.PARAGRAPH && block.getFontSize() > 0 && block.getText() != null) {
                charsBySize.merge(block.getFontSize(), (long) block.getText().length(), Long::sum);
            }
        }
        return charsBySize.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0f);
    }

    /**
     * 章节构建：标题压栈产出生命周期章节，非标题块归属栈顶章节。
     */
    private List<DocSection> buildSections(List<DocBlock> blocks) {
        List<DocSection> sections = new ArrayList<>();
        Deque<TitleFrame> stack = new ArrayDeque<>();
        DocSection current = newSection(List.of(), "", 0);
        sections.add(current);

        for (DocBlock block : blocks) {
            if (block.getType() == BlockType.TITLE) {
                int level = Math.max(1, block.getHeadingLevel());
                while (!stack.isEmpty() && stack.peek().level() >= level) {
                    stack.pop();
                }
                // ArrayDeque 迭代顺序是自顶向下，需反转为"从根标题到父标题"的路径顺序
                List<TitleFrame> frames = new ArrayList<>(stack);
                List<String> path = new ArrayList<>(frames.size() + 1);
                for (int i = frames.size() - 1; i >= 0; i--) {
                    path.add(frames.get(i).title());
                }
                path.add(block.getText());
                stack.push(new TitleFrame(level, block.getText()));
                current = newSection(List.copyOf(path), block.getText(), block.getPageNum());
                sections.add(current);
            } else {
                current.getBlocks().add(block);
            }
        }
        // 过滤掉没有内容的空章节（连续标题场景）
        sections.removeIf(s -> s.getBlocks().isEmpty());
        return sections;
    }

    private DocSection newSection(List<String> headingPath, String title, int startPage) {
        return DocSection.builder()
                .headingPath(headingPath)
                .title(title)
                .blocks(new ArrayList<>())
                .startPage(Math.max(startPage, 1))
                .build();
    }

    private boolean isPageNumber(String text) {
        return text != null && PAGE_NUMBER_LINE.matcher(text.trim()).matches();
    }

    /**
     * 文本归一化：压缩空白 + 数字归一（页码/日期等变量部分不影响重复性判定）。
     */
    private String normalize(String text) {
        return text.replaceAll("\\s+", "").replaceAll("\\d+", "#");
    }

    /**
     * 栈帧：标题层级 + 标题文本。
     */
    private record TitleFrame(int level, String title) {
    }
}
