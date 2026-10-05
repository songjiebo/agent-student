package com.example.agent.ingestion.parse;

import com.example.agent.ingestion.config.AgentIngestProperties;
import com.example.agent.ingestion.model.BlockType;
import com.example.agent.ingestion.model.DocBlock;
import com.example.agent.ingestion.model.ParsedDocument;
import com.example.agent.ingestion.precheck.IngestRejectedException;
import com.example.agent.ingestion.precheck.PdfProbeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文本层解析策略（PDFBox）：处理数字原生 PDF，是整条策略链的第一站。
 *
 * <p>关键处理点：
 * <ul>
 *   <li>逐行捕获：通过覆写 writeString 拿到"行文本 + 坐标 + 字号"，而不是裸 getText()；</li>
 *   <li>双栏阅读顺序修复：检测双栏版式后按"先左栏、后右栏"重排，避免左右穿插；</li>
 *   <li>行级输出：段落合并/标题识别统一交给 LayoutStructurer，解析器只负责忠实还原。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextLayerPdfParser implements PdfParseStrategy {

    /** 策略名 */
    public static final String NAME = "text-layer";

    /** 双栏判定：右栏起始行占比阈值 */
    private static final double COLUMN_LINE_RATIO = 0.20;

    /** 双栏判定：左/右分界线（相对页宽） */
    private static final double COLUMN_SPLIT = 0.55;

    private final AgentIngestProperties properties;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean supports(PdfProbeResult probe) {
        // 无文本层的文档（扫描件）不适用，直接跳到 OCR 策略
        //return probe.hasTextLayer(properties.getQuality().getMinCharsPerPage());
        return false;
    }

    @Override
    public ParsedDocument parse(Path file, PdfProbeResult probe) {
        List<LineItem> lines = extractLines(file);
        List<DocBlock> blocks = toBlocks(lines);
        return ParsedDocument.builder()
                .strategyName(NAME)
                .pageCount(probe.pageCount())
                .blocks(blocks)
                .build();
    }

    /**
     * 提取全文件的文本行（含坐标/字号），并完成双栏阅读顺序修复。
     *
     * @param file PDF 文件
     * @return 行列表（按修复后的阅读顺序）
     */
    private List<LineItem> extractLines(Path file) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            LineCapturingStripper stripper = new LineCapturingStripper();
            stripper.setSortByPosition(true);
            stripper.getText(document);

            List<LineItem> lines = new ArrayList<>(stripper.lines);
            fixColumnOrder(document, lines);
            return lines;
        } catch (IOException e) {
            throw new IngestRejectedException("PDF 文本层提取失败：" + e.getMessage(), e);
        }
    }

    /**
     * 双栏阅读顺序修复。
     * 判定条件：页面中"起始于右半区"与"起始于左半区"的行占比均超过阈值；
     * 满足时按左栏/右栏分组后重排（栏内保持自上而下）。
     *
     * @param document PDF 文档（用于取页宽）
     * @param lines    行列表（原地重排）
     */
    private void fixColumnOrder(PDDocument document, List<LineItem> lines) {
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            final int pageNum = page;
            List<LineItem> pageLines = lines.stream().filter(l -> l.pageNum() == pageNum).toList();
            if (pageLines.size() < 8) {
                continue;
            }
            PDPage pdPage = document.getPage(page - 1);
            float width = pdPage.getMediaBox().getWidth();

            long rightStart = pageLines.stream().filter(l -> l.x() > width * COLUMN_SPLIT).count();
            long leftStart = pageLines.stream().filter(l -> l.x() < width * (1 - COLUMN_SPLIT)).count();
            boolean twoColumn = rightStart > pageLines.size() * COLUMN_LINE_RATIO
                    && leftStart > pageLines.size() * COLUMN_LINE_RATIO;
            if (!twoColumn) {
                continue;
            }

            // 原地重排：先左栏后右栏，栏内按 y 升序（YDirAdj 自上而下递增）
            List<LineItem> ordered = new ArrayList<>(pageLines.size());
            pageLines.stream()
                    .filter(l -> l.x() <= width * COLUMN_SPLIT)
                    .sorted(Comparator.comparingDouble(LineItem::y))
                    .forEach(ordered::add);
            pageLines.stream()
                    .filter(l -> l.x() > width * COLUMN_SPLIT)
                    .sorted(Comparator.comparingDouble(LineItem::y))
                    .forEach(ordered::add);
            lines.removeIf(l -> l.pageNum() == pageNum);
            lines.addAll(ordered);
        }
        // 修复后整体再按页码稳定排序，保证跨页顺序正确
        lines.sort(Comparator.comparingInt(LineItem::pageNum));
    }

    /**
     * 行 → 解析块归一化。文本层来源的行统一标记 PARAGRAPH，
     * 页眉页脚/标题/表格由 LayoutStructurer 基于全局信息识别（单行局部信息不足）。
     */
    private List<DocBlock> toBlocks(List<LineItem> lines) {
        List<DocBlock> blocks = new ArrayList<>(lines.size());
        for (LineItem line : lines) {
            blocks.add(DocBlock.builder()
                    .type(BlockType.PARAGRAPH)
                    .text(line.text())
                    .pageNum(line.pageNum())
                    .fontSize(line.fontSize())
                    .headingLevel(0)
                    .build());
        }
        return blocks;
    }

    /**
     * 文本行中间模型：行文本 + 起始坐标 + 字号。
     */
    private record LineItem(int pageNum, String text, float x, float y, float fontSize) {
    }

    /**
     * 逐行捕获 Stripper：拿到行级文本、坐标、字号，为版面还原保留必要信息。
     */
    private static final class LineCapturingStripper extends PDFTextStripper {

        private final List<LineItem> lines = new ArrayList<>();
        private int pageIndex = -1;

        private LineCapturingStripper() throws IOException {
            super();
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            // PDFTextStripper 严格按页顺序回调，自维护页下标
            this.pageIndex++;
            super.startPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            if (text == null || text.isBlank() || textPositions.isEmpty()) {
                return;
            }
            float fontSize = 0f;
            for (TextPosition position : textPositions) {
                fontSize = Math.max(fontSize, position.getFontSize());
            }
            lines.add(new LineItem(
                    pageIndex + 1,
                    text.trim(),
                    textPositions.get(0).getXDirAdj(),
                    textPositions.get(0).getYDirAdj(),
                    fontSize));
        }
    }
}
