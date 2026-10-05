package com.example.agent.ingestion.parse;

import com.example.agent.ingestion.model.ParsedDocument;
import com.example.agent.ingestion.quality.QualityReport;

/**
 * 策略路由结果：最终采用的解析产出 + 质检报告 + 是否处于降级兜底状态。
 *
 * @param document 最终采用的解析产出
 * @param report   对应的质检报告
 * @param degraded true 表示所有策略均未达到 acceptScore，采用的是得分最高的兜底结果
 */
public record ParseOutcome(ParsedDocument document, QualityReport report, boolean degraded) {
}
