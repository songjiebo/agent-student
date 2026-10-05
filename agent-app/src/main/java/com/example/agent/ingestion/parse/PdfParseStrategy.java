package com.example.agent.ingestion.parse;

import com.example.agent.ingestion.model.ParsedDocument;
import com.example.agent.ingestion.precheck.PdfProbeResult;

import java.nio.file.Path;

/**
 * PDF 解析策略接口（策略链模式的核心抽象）。
 *
 * <p>设计动机：没有一个解析器能同时做到"快、便宜、准"。
 * 工业界的标准做法是便宜的解析器打头阵，质量评分不达标时自动升级到更贵的解析器，
 * 用成本换质量只发生在真正需要的文档上。策略实现按 order() 升序尝试。
 */
public interface PdfParseStrategy {

    /**
     * 策略名，写入质检报告与任务记录，用于问题定位。
     *
     * @return 策略名（如 text-layer / rapid-ocr / mineru）
     */
    String name();

    /**
     * 执行顺序，小的先执行。约定：10=文本层，20=OCR，30=外部解析服务。
     *
     * @return 排序值
     */
    int order();

    /**
     * 根据文档画像判断该策略是否适用（如纯文本层策略不适用于扫描件）。
     *
     * @param probe 文档画像
     * @return true 表示适用
     */
    boolean supports(PdfProbeResult probe);

    /**
     * 执行解析，产出归一化的 {@link ParsedDocument}。
     *
     * @param file  PDF 文件
     * @param probe 文档画像
     * @return 归一化解析结果
     */
    ParsedDocument parse(Path file, PdfProbeResult probe);
}
