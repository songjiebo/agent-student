package com.example.agent.ingestion.precheck;

import com.example.agent.ingestion.config.AgentIngestProperties;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * PDF 文档画像探测：一次遍历统计每页有效字符数，产出 {@link PdfProbeResult}。
 * 该结果是解析策略路由的核心输入——文本型走文本层解析，图片页走 OCR，复杂版式走 MinerU。
 */
@Component
@RequiredArgsConstructor
public class PdfProbe {

    private final AgentIngestProperties properties;

    /**
     * 打开 PDF 完成安全性校验与文本层探测。
     *
     * @param file PDF 文件路径
     * @return 探测结果（页数 + 每页字符数）
     * @throws IngestRejectedException 加密 / 损坏 / 超页数限制 / 空文档（不可重试）
     */
    public PdfProbeResult probe(Path file) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            // 加密 PDF 一律拒绝：不暴力破解，也不在流水线里处理口令分发
            if (document.isEncrypted()) {
                throw new IngestRejectedException("PDF 已加密，暂不支持带口令文档");
            }
            int pageCount = document.getNumberOfPages();
            if (pageCount <= 0) {
                throw new IngestRejectedException("PDF 页数为 0，疑似损坏");
            }
            int maxPages = properties.getLimits().getMaxPages();
            if (pageCount > maxPages) {
                throw new IngestRejectedException("PDF 页数超过限制：" + pageCount + " > " + maxPages);
            }

            // 单次遍历统计每页字符数，避免逐页重复解析全文档（大文件性能关键点）
            int[] pageCharCounts = new int[pageCount];
            PageCharCountStripper stripper = new PageCharCountStripper(pageCharCounts);
            stripper.setSortByPosition(true);
            stripper.getText(document);

            int total = 0;
            for (int count : pageCharCounts) {
                total += count;
            }
            return new PdfProbeResult(pageCount, pageCharCounts, total);
        } catch (InvalidPasswordException e) {
            throw new IngestRejectedException("PDF 已加密且口令不为空，暂不支持", e);
        } catch (IOException e) {
            throw new IngestRejectedException("PDF 解析失败，文件可能已损坏：" + e.getMessage(), e);
        }
    }

    /**
     * 单遍统计每页有效字符数的 Stripper。
     * 通过 startPage 钩子记录当前页下标，writeString 回调中累计非空白字符。
     */
    private static final class PageCharCountStripper extends PDFTextStripper {

        private final int[] counts;
        private int currentPageIndex = -1;

        private PageCharCountStripper(int[] counts) throws IOException {
            super();
            this.counts = counts;
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            // PDFTextStripper 严格按页顺序回调 startPage，自维护下标比依赖内部 API 更稳
            this.currentPageIndex++;
            super.startPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            if (text != null && currentPageIndex >= 0 && currentPageIndex < counts.length) {
                counts[currentPageIndex] += text.replaceAll("\\s+", "").length();
            }
            super.writeString(text, textPositions);
        }
    }
}
