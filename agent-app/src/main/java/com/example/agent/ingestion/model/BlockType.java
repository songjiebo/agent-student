package com.example.agent.ingestion.model;

/**
 * 版面块类型。
 * 解析策略负责把各自的输出归一为该枚举，下游结构化/切分逻辑只依赖该分类。
 */
public enum BlockType {

    /** 标题（含章节标题），携带 headingLevel */
    TITLE,

    /** 正文段落 */
    PARAGRAPH,

    /** 列表项（有序/无序） */
    LIST_ITEM,

    /** 表格（text 中保存 Markdown 线性化结果） */
    TABLE,

    /** 图注/表注 */
    CAPTION,

    /** 页眉/页脚/页码等版式噪音，入库前会被剔除 */
    HEADER_FOOTER,

    /** 无法识别的块，保留原文但不参与标题层级推断 */
    UNKNOWN
}
