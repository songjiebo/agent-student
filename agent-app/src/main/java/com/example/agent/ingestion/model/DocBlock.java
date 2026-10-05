package com.example.agent.ingestion.model;

import lombok.Builder;
import lombok.Getter;

/**
 * 解析输出的最小版面块。
 * 所有解析策略（PDFBox 文本层 / RapidOCR / MinerU）统一归一到该模型，
 * 使下游"结构化 → 切分"只面对一种中间表示，与解析来源彻底解耦。
 */
@Getter
@Builder
public class DocBlock {

    /** 块类型 */
    private BlockType type;

    /** 块文本（表格块为 Markdown 线性化文本） */
    private String text;

    /** 页码（1 起） */
    private int pageNum;

    /** 字体大小；OCR / MinerU 来源无字体信息时为 0 */
    private float fontSize;

    /** 标题层级 1-3；非标题块为 0。MinerU 来源可直接给出 */
    private int headingLevel;
}
