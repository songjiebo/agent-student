package com.example.agent.ingestion.parse;

import com.example.agent.ingestion.config.AgentIngestProperties;
import com.example.agent.ingestion.model.DocBlock;
import com.example.agent.ingestion.model.ParsedDocument;
import com.example.agent.ingestion.precheck.PdfProbe;
import com.example.agent.ingestion.precheck.PdfProbeResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本层解析策略测试：用 PDFBox 现场生成 PDF（不依赖外部测试资源）。
 */
class TextLayerPdfParserTest {

    @TempDir
    Path tempDir;

    /** 生成两页文本型 PDF：第 1 页大标题 + 正文，第 2 页正文 */
    private Path generateSamplePdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDType1Font titleFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            PDPage page1 = new PDPage(PDRectangle.A4);
            document.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(document, page1)) {
                cs.beginText();
                cs.setFont(titleFont, 18);
                cs.newLineAtOffset(60, 760);
                cs.showText("Chapter 1 Overview");
                cs.endText();
                cs.beginText();
                cs.setFont(bodyFont, 11);
                cs.newLineAtOffset(60, 720);
                cs.showText("This document explains the billing rules for enterprise customers.");
                cs.endText();
            }

            PDPage page2 = new PDPage(PDRectangle.A4);
            document.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(document, page2)) {
                cs.beginText();
                cs.setFont(bodyFont, 11);
                cs.newLineAtOffset(60, 760);
                cs.showText("Discount applies to annual contracts.");
                cs.endText();
            }

            Path file = tempDir.resolve("sample.pdf");
            document.save(file.toFile());
            return file;
        }
    }

    @Test
    @DisplayName("文本型 PDF 能抽出全部文本行并保留字号")
    void extractsLinesWithFontSizes() throws Exception {
        Path file = generateSamplePdf();
        AgentIngestProperties properties = new AgentIngestProperties();
        PdfProbe probe = new PdfProbe(properties);
        PdfProbeResult probeResult = probe.probe(file);

        TextLayerPdfParser parser = new TextLayerPdfParser(properties);
        assertTrue(parser.supports(probeResult), "文本型 PDF 应被文本层策略支持");
        ParsedDocument parsed = parser.parse(file, probeResult);

        assertEquals(2, parsed.getPageCount());
        String allText = parsed.getBlocks().stream().map(DocBlock::getText).reduce("", (a, b) -> a + " " + b);
        assertTrue(allText.contains("Chapter 1 Overview"));
        assertTrue(allText.contains("billing rules"));
        assertTrue(allText.contains("annual contracts"));

        // 标题字号应大于正文，供 LayoutStructurer 做层级识别
        float titleSize = parsed.getBlocks().stream()
                .filter(b -> b.getText().contains("Chapter"))
                .findFirst().orElseThrow().getFontSize();
        float bodySize = parsed.getBlocks().stream()
                .filter(b -> b.getText().contains("billing"))
                .findFirst().orElseThrow().getFontSize();
        assertTrue(titleSize > bodySize, "标题字号应大于正文");
    }

    @Test
    @DisplayName("扫描件画像（无文本层）不被文本层策略支持")
    void rejectsImageOnlyProfile() {
        AgentIngestProperties properties = new AgentIngestProperties();
        TextLayerPdfParser parser = new TextLayerPdfParser(properties);
        PdfProbeResult imageOnly = new PdfProbeResult(5, new int[]{0, 0, 0, 0, 0}, 0);
        assertTrue(!parser.supports(imageOnly), "无文本层文档应跳过文本层策略");
    }
}
