package com.example.agent.ingestion.quality;

import com.example.agent.ingestion.model.BlockType;
import com.example.agent.ingestion.model.DocBlock;
import com.example.agent.ingestion.model.ParsedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质量评分测试：分数区间、指标口径、复核标记。
 */
class QualityScorerTest {

    private final QualityScorer scorer = new QualityScorer();

    /** 构造 n 个正常正文块的解析结果 */
    private ParsedDocument richDocument(int blocks) {
        List<DocBlock> list = new ArrayList<>();
        for (int i = 0; i < blocks; i++) {
            list.add(DocBlock.builder()
                    .type(BlockType.PARAGRAPH)
                    .text("这是第" + i + "段正常中文正文内容，用于模拟数字原生 PDF 的抽取结果。")
                    .pageNum(1)
                    .build());
        }
        return ParsedDocument.builder().strategyName("text-layer").pageCount(1).blocks(list).build();
    }

    @Test
    @DisplayName("内容丰富的文档得高分且不需要复核")
    void scoresRichDocumentHigh() {
        QualityReport report = scorer.score(richDocument(50), 50, 0.4);
        assertTrue(report.score() > 0.8, "丰富内容应得高分，实际: " + report.score());
        assertFalse(report.needReview());
    }

    @Test
    @DisplayName("空解析结果得低分并标记复核")
    void scoresEmptyDocumentLow() {
        ParsedDocument empty = ParsedDocument.builder()
                .strategyName("text-layer").pageCount(100).blocks(List.of()).build();
        QualityReport report = scorer.score(empty, 50, 0.4);
        assertTrue(report.score() < 0.4, "空结果应得低分，实际: " + report.score());
        assertTrue(report.needReview());
    }

    @Test
    @DisplayName("乱码率高时显著降分")
    void penalizesGarbageText() {
        List<DocBlock> garbage = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            garbage.add(DocBlock.builder()
                    .type(BlockType.PARAGRAPH)
                    .text("\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD乱码内容模拟字体 ToUnicode 缺失")
                    .pageNum(1)
                    .build());
        }
        ParsedDocument parsed = ParsedDocument.builder()
                .strategyName("text-layer").pageCount(1).blocks(garbage).build();
        QualityReport report = scorer.score(parsed, 50, 0.4);
        QualityReport clean = scorer.score(richDocument(50), 50, 0.4);
        assertTrue(report.score() < clean.score(), "乱码文档得分应低于正常文档");
        assertTrue(report.replacementRatio() > 0.1);
    }

    @Test
    @DisplayName("字符密度指标口径：有效字符 / 页数")
    void computesCharsPerPage() {
        ParsedDocument parsed = richDocument(20);
        QualityReport report = scorer.score(parsed, 50, 0.4);
        // 单页文档：charsPerPage 应约等于全文有效字符数
        assertEquals((double) parsed.totalChars(), report.charsPerPage(), 1.0);
    }

    @Test
    @DisplayName("分数被截断在 [0,1] 区间")
    void clampsScoreRange() {
        ParsedDocument empty = ParsedDocument.builder()
                .strategyName("x").pageCount(500).blocks(List.of()).build();
        QualityReport report = scorer.score(empty, 50, 0.4);
        assertTrue(report.score() >= 0 && report.score() <= 1);
    }
}
