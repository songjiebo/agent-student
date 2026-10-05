package com.example.agent.ingestion.parse;

import com.example.agent.ingestion.config.AgentIngestProperties;
import com.example.agent.ingestion.model.ParsedDocument;
import com.example.agent.ingestion.precheck.IngestRejectedException;
import com.example.agent.ingestion.precheck.PdfProbeResult;
import com.example.agent.ingestion.quality.QualityReport;
import com.example.agent.ingestion.quality.QualityScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 解析策略路由器：策略链 + 质量评分降级（本流水线的核心决策组件）。
 *
 * <p>决策逻辑：
 * <ol>
 *   <li>按 order() 从便宜到贵尝试策略（文本层 → OCR → MinerU）；</li>
 *   <li>每个产出立即质量评分，达到 acceptScore 即采用并终止；</li>
 *   <li>策略"不可用"（环境问题）只降级不报错；</li>
 *   <li>全部不达标时采用得分最高者继续入库，但标记 degraded，任务状态置 NEED_REVIEW。</li>
 * </ol>
 * 这套机制的工程价值：廉价解析器覆盖 80% 常规文档，昂贵解析只花在真正需要的文档上，
 * 同时保证"宁可显式标记低质量，也绝不静默入库垃圾"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParseStrategyRouter {

    private final AgentIngestProperties properties;
    private final QualityScorer qualityScorer;
    private final List<PdfParseStrategy> strategies;

    /**
     * 路由解析：返回最终采用的解析产出与质检报告。
     *
     * @param file  PDF 文件
     * @param probe 文档画像
     * @return 解析结果（含质量报告与降级标记）
     * @throws IngestRejectedException 所有策略均无法产出结果
     */
    public ParseOutcome route(Path file, PdfProbeResult probe) {
        List<PdfParseStrategy> ordered = strategies.stream()
                .sorted(Comparator.comparingInt(PdfParseStrategy::order))
                .toList();

        ParsedDocument bestDocument = null;
        QualityReport bestReport = null;

        for (PdfParseStrategy strategy : ordered) {
            if (!strategy.supports(probe)) {
                log.debug("策略 {} 不适用于当前文档画像，跳过", strategy.name());
                continue;
            }
            try {
                ParsedDocument parsed = strategy.parse(file, probe);
                QualityReport report = qualityScorer.score(parsed,
                        properties.getQuality().getMinCharsPerPage(),
                        properties.getQuality().getNeedReviewScore());
                log.info("策略 {} 解析完成: score={}, charsPerPage={}, blocks={}",
                        strategy.name(), report.score(), report.charsPerPage(), report.blockCount());

                if (bestReport == null || report.score() > bestReport.score()) {
                    bestDocument = parsed;
                    bestReport = report;
                }
                if (report.score() >= properties.getQuality().getAcceptScore()) {
                    return new ParseOutcome(parsed, report, false);
                }
            } catch (OcrUnavailableException | MineruUnavailableException e) {
                // 策略不可用属于环境问题：降级到下一策略，不视为文档解析失败
                log.warn("策略 {} 不可用，降级到下一策略: {}", strategy.name(), e.getMessage());
            }
        }

        if (bestDocument == null) {
            throw new IngestRejectedException("没有可用的解析策略（文本层缺失且 OCR/MinerU 均不可用）");
        }
        log.warn("所有策略均未达到质量阈值，采用最高分结果兜底: strategy={}, score={}",
                bestDocument.getStrategyName(), bestReport.score());
        return new ParseOutcome(bestDocument, bestReport, true);
    }
}
