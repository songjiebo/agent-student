package com.example.agent.ingestion.quality;

import com.example.agent.ingestion.model.DocBlock;
import com.example.agent.ingestion.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 解析质量评分器。
 *
 * <p>评分动机（对应线上真实事故模式）：
 * <ul>
 *   <li>字符密度过低 → 抽取出空文本/纯乱码却"成功入库"，是 RAG 幻觉的第一大来源；</li>
 *   <li>乱码率高 → 字体 ToUnicode 缺失或 OCR 引擎误识别，语义已损坏；</li>
 *   <li>块数量过少 → 版面还原失败（阅读顺序、表格塌陷）。</li>
 * </ul>
 * 评分采用可解释的线性扣分而非黑盒模型：问题定位时能直接看指标。
 */
@Component
public class QualityScorer {

    /** 乱码/控制字符：U+FFFD 替换符、C0/C1 控制符 */
    private static final String REPLACEMENT_CHARS = "\\uFFFD\\u0000-\\u001F\\u007F-\\u009F";

    /**
     * 对一次解析产出打分。
     *
     * @param parsed   解析产出
     * @param minChars 配置的"每页最少有效字符"阈值（低于视为无文本层）
     * @param needReviewScore 需人工复核的分数阈值
     * @return 质检报告
     */
    public QualityReport score(ParsedDocument parsed, int minChars, double needReviewScore) {
        List<DocBlock> blocks = parsed.getBlocks() == null ? List.of() : parsed.getBlocks();

        int totalChars = parsed.totalChars();
        double pages = Math.max(1, parsed.getPageCount());
        double charsPerPage = totalChars / pages;

        // 统计乱码/控制字符占比
        long replacement = 0;
        for (DocBlock block : blocks) {
            if (block.getText() == null) {
                continue;
            }
            replacement += block.getText().codePoints()
                    .filter(c -> (c == 0xFFFD) || (c < 0x20 && c != '\n' && c != '\t') || (c >= 0x7F && c <= 0x9F))
                    .count();
        }
        double replacementRatio = totalChars == 0 ? 1.0 : (double) replacement / totalChars;

        // 三项可解释指标 → 线性扣分
        double score = 1.0;
        // 1) 字符密度：达到 minChars*2 记满分，越低扣越重
        score -= clamp((minChars * 2.0 - charsPerPage) / (minChars * 2.0), 0, 1) * 0.5;
        // 2) 乱码率：超过 5% 开始扣分，50% 以上扣满
        score -= clamp(replacementRatio / 0.5, 0, 1) * 0.35;
        // 3) 块数量：10 个块以下按比例扣分（正常文档远多于此）
        score -= clamp((10.0 - blocks.size()) / 10.0, 0, 1) * 0.15;
        score = clamp(score, 0, 1);

        boolean needReview = score < needReviewScore;
        return new QualityReport(round3(score), parsed.getStrategyName(), round3(charsPerPage),
                round3(replacementRatio), blocks.size(), needReview);
    }

    /**
     * 数值截断到 [min, max]，防止指标越界导致扣分为负。
     */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 保留 3 位小数，保证报告落库/序列化稳定可比。
     */
    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
