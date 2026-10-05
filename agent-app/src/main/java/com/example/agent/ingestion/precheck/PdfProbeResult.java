package com.example.agent.ingestion.precheck;

/**
 * PDF 文档画像探测结果：解析策略路由的输入。
 *
 * @param pageCount      总页数
 * @param pageCharCounts 每页有效字符数（下标 0 对应第 1 页）
 * @param totalChars     全文有效字符数
 */
public record PdfProbeResult(int pageCount, int[] pageCharCounts, int totalChars) {

    /**
     * 判断文档是否带可用文本层。
     * "可用" = 平均每页有效字符数不低于阈值（防止个别页空白误判）。
     *
     * @param minCharsPerPage 每页最少有效字符阈值
     * @return true 表示整体有文本层
     */
    public boolean hasTextLayer(int minCharsPerPage) {
        return (double) totalChars / Math.max(1, pageCount) >= minCharsPerPage;
    }

    /**
     * 判断指定页是否为"图片页"（无文本层，需要 OCR）。
     *
     * @param pageIndex      页下标（0 起）
     * @param minCharsPerPage 该页最少有效字符阈值
     * @return true 表示该页需要 OCR
     */
    public boolean isImageOnlyPage(int pageIndex, int minCharsPerPage) {
        return pageIndex < pageCharCounts.length && pageCharCounts[pageIndex] < minCharsPerPage;
    }
}
