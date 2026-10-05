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
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * OCR 解析策略（RapidOCR）：处理扫描件/图片型页，是策略链的第二站。
 *
 * <p>采用"整文档 OCR"而非"按页混合"的原因：文本层损坏的典型形态是 ToUnicode 缺失导致的
 * 乱码（字符数不少但语义已坏），按字符数判断文本层是否可用会误判。整文档 OCR 从渲染图像
 * 识别，天然绕开文本层损坏问题，只在文本层策略质量不达标时才会被执行，成本可控。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RapidOcrPdfParser implements PdfParseStrategy {

    /** 策略名 */
    public static final String NAME = "rapid-ocr";

    private final AgentIngestProperties properties;
    private final RapidOcrClient rapidOcrClient;
    private final TextLayerPdfParser textLayerPdfParser;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean supports(PdfProbeResult probe) {
        return properties.getOcr().isEnabled();
    }

    @Override
    public ParsedDocument parse(Path file, PdfProbeResult probe) {
        List<DocBlock> blocks = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                blocks.addAll(ocrPage(renderer, pageIndex, file, probe));
            }
        } catch (IOException e) {
            throw new IngestRejectedException("PDF 渲染失败：" + e.getMessage(), e);
        }
        return ParsedDocument.builder()
                .strategyName(NAME)
                .pageCount(probe.pageCount())
                .blocks(blocks)
                .build();
    }

    /**
     * OCR 单页：栅格化 → 临时 PNG → RapidOCR 识别 → 归一为块。
     * 单页失败降级为该页文本层内容，保证"部分成功 > 整体失败"。
     *
     * @param renderer   PDF 渲染器
     * @param pageIndex  页下标（0 起）
     * @param file       PDF 文件（降级回退用）
     * @param probe      文档画像
     * @return 该页的块列表
     */
    private List<DocBlock> ocrPage(PDFRenderer renderer, int pageIndex, Path file, PdfProbeResult probe) {
        Path tempImage = null;
        try {
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, properties.getOcr().getDpi());
            tempImage = Files.createTempFile("ingest-ocr-page-" + pageIndex + "-", ".png");
            if (!ImageIO.write(image, "png", tempImage.toFile())) {
                throw new IOException("PNG 编码器不可用");
            }

            List<DocBlock> blocks = new ArrayList<>();
            for (RapidOcrClient.OcrLine line : rapidOcrClient.recognize(tempImage)) {
                blocks.add(DocBlock.builder()
                        .type(BlockType.PARAGRAPH)
                        .text(line.text())
                        .pageNum(pageIndex + 1)
                        // OCR 无字体信息，字号置 0；标题识别退化为结构规则（见 LayoutStructurer）
                        .fontSize(0f)
                        .headingLevel(0)
                        .build());
            }
            return blocks;
        } catch (OcrUnavailableException e) {
            // 引擎级失败（环境问题）：直接向上传播，路由层降级到下一策略
            throw e;
        } catch (Exception e) {
            // 单页失败：回退该页文本层内容并继续，避免一页坏图丢掉整个文档
            log.warn("第 {} 页 OCR 失败，回退文本层内容: {}", pageIndex + 1, e.getMessage());
            return fallbackTextBlocks(file, probe, pageIndex);
        } finally {
            if (tempImage != null) {
                try {
                    Files.deleteIfExists(tempImage);
                } catch (IOException e) {
                    log.debug("OCR 临时文件清理失败: {}", tempImage);
                }
            }
        }
    }

    /**
     * 单页回退：从文本层策略产出中取该页的块（已含双栏修复）。
     */
    private List<DocBlock> fallbackTextBlocks(Path file, PdfProbeResult probe, int pageIndex) {
        try {
            ParsedDocument textParsed = textLayerPdfParser.parse(file, probe);
            return textParsed.getBlocks().stream()
                    .filter(b -> b.getPageNum() == pageIndex + 1)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("第 {} 页文本层回退也失败: {}", pageIndex + 1, e.getMessage());
            return List.of();
        }
    }
}
