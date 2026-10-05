package com.example.agent.ingestion.quality;

/**
 * 解析质检报告：策略降级链的决策依据，同时落库供人工复核与调参。
 * 每个指标都刻意保持"可解释"，避免黑盒打分导致问题无法定位。
 */
public record QualityReport(
        /** 综合质量分 0.000-1.000 */
        double score,
        /** 产出该报告的策略名 */
        String strategy,
        /** 字符密度：有效字符数 / 页数，过低是扫描件/抽取失败的典型特征 */
        double charsPerPage,
        /** 乱码率：替换字符/控制字符占比 */
        double replacementRatio,
        /** 块数量（过少说明版面还原失败） */
        int blockCount,
        /** 是否低于 NEED_REVIEW 阈值，需要人工复核 */
        boolean needReview) {
}
