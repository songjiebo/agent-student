package com.example.agent.ingestion.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 单个解析策略的输出结果：扁平的块列表 + 来源信息。
 * 注意：这里刻意不做层级，标题层级/段落合并统一由 LayoutStructurer 完成，
 * 保证不同解析策略产出的结果走同一套版面还原逻辑。
 */
@Getter
@Builder
public class ParsedDocument {

    /** 产出该结果的策略名（text-layer / rapid-ocr / mineru），用于质检报告与问题定位 */
    private String strategyName;

    /** 文档总页数 */
    private int pageCount;

    /** 归一化后的块列表（按阅读顺序） */
    private List<DocBlock> blocks;

    /**
     * 全文有效字符数（去空白后），质量评分与字符密度指标的分子。
     *
     * @return 有效字符总数
     */
    public int totalChars() {
        if (blocks == null) {
            return 0;
        }
        int total = 0;
        for (DocBlock block : blocks) {
            if (block.getText() != null) {
                total += block.getText().replaceAll("\\s+", "").length();
            }
        }
        return total;
    }
}
