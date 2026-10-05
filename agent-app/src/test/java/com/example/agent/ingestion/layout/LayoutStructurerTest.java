package com.example.agent.ingestion.layout;

import com.example.agent.ingestion.model.BlockType;
import com.example.agent.ingestion.model.DocBlock;
import com.example.agent.ingestion.model.DocSection;
import com.example.agent.ingestion.model.ParsedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版面结构化测试：页眉页脚剔除、表格聚合、标题层级、章节路径。
 */
class LayoutStructurerTest {

    private final LayoutStructurer structurer = new LayoutStructurer();

    private DocBlock line(String text, int page, float fontSize) {
        return DocBlock.builder().type(BlockType.PARAGRAPH).text(text).pageNum(page).fontSize(fontSize).build();
    }

    @Test
    @DisplayName("跨页重复的页眉被剔除，页码行被剔除")
    void removesHeaderFooter() {
        ParsedDocument parsed = ParsedDocument.builder()
                .strategyName("text-layer")
                .pageCount(3)
                .blocks(List.of(
                        line("某某公司内部管理手册", 1, 10f),
                        line("第一章 总则", 1, 14f),
                        line("本章规定了公司内部管理的基本要求和适用范围。", 1, 10f),
                        line("1", 1, 10f),
                        line("某某公司内部管理手册", 2, 10f),
                        line("第二章 组织", 2, 14f),
                        line("公司设立综合管理部负责制度建设。", 2, 10f),
                        line("2", 2, 10f),
                        line("某某公司内部管理手册", 3, 10f),
                        line("第三章 附则", 3, 14f),
                        line("本手册自发布之日起施行。", 3, 10f)))
                .build();

        List<DocSection> sections = structurer.structure(parsed);
        String allText = sections.stream()
                .flatMap(s -> s.getBlocks().stream())
                .map(DocBlock::getText)
                .reduce("", (a, b) -> a + b);
        assertFalse(allText.contains("某某公司内部管理手册"), "页眉应被剔除");
        assertTrue(allText.contains("本章规定"), "正文应保留");
    }

    @Test
    @DisplayName("字号识别标题并构建章节路径")
    void buildsHeadingPath() {
        ParsedDocument parsed = ParsedDocument.builder()
                .strategyName("text-layer")
                .pageCount(1)
                .blocks(List.of(
                        line("第3章 收费标准", 1, 16f),
                        line("3.2 优惠规则", 1, 13f),
                        line("年度签约客户享受九折优惠。", 1, 10f)))
                .build();

        List<DocSection> sections = structurer.structure(parsed);
        DocSection leaf = sections.get(sections.size() - 1);
        assertEquals(List.of("第3章 收费标准", "3.2 优惠规则"), leaf.getHeadingPath());
        assertEquals("3.2 优惠规则", leaf.getTitle());
    }

    @Test
    @DisplayName("多列对齐的连续行聚合为 Markdown 表格")
    void groupsTableRows() {
        ParsedDocument parsed = ParsedDocument.builder()
                .strategyName("text-layer")
                .pageCount(1)
                .blocks(List.of(
                        line("套餐名称   月费   流量", 1, 10f),
                        line("经济版     29     10GB", 1, 10f),
                        line("标准版     59     30GB", 1, 10f)))
                .build();

        List<DocSection> sections = structurer.structure(parsed);
        boolean hasTable = sections.stream()
                .flatMap(s -> s.getBlocks().stream())
                .anyMatch(b -> b.getType() == BlockType.TABLE && b.getText().contains("|"));
        assertTrue(hasTable, "多列行应聚合为表格");
    }

    @Test
    @DisplayName("MinerU 来源的标题层级被保留为章节树")
    void preservesMineruHeadingLevels() {
        ParsedDocument parsed = ParsedDocument.builder()
                .strategyName("mineru")
                .pageCount(2)
                .blocks(List.of(
                        DocBlock.builder().type(BlockType.TITLE).text("一、总则").pageNum(1).headingLevel(1).build(),
                        DocBlock.builder().type(BlockType.PARAGRAPH).text("制定目的说明。").pageNum(1).build(),
                        DocBlock.builder().type(BlockType.TITLE).text("（一）适用范围").pageNum(1).headingLevel(2).build(),
                        DocBlock.builder().type(BlockType.PARAGRAPH).text("适用于全体员工。").pageNum(1).build()))
                .build();

        List<DocSection> sections = structurer.structure(parsed);
        DocSection leaf = sections.stream()
                .filter(s -> s.getTitle().equals("（一）适用范围"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("一、总则", "（一）适用范围"), leaf.getHeadingPath());
    }

    @Test
    @DisplayName("文档前言（无标题区）保留为独立章节")
    void keepsPreambleSection() {
        ParsedDocument parsed = ParsedDocument.builder()
                .strategyName("text-layer")
                .pageCount(1)
                .blocks(List.of(
                        line("本手册依据国家相关法律法规制定。", 1, 10f),
                        line("第一章 总则", 1, 16f),
                        line("本章规定了基本要求。", 1, 10f)))
                .build();

        List<DocSection> sections = structurer.structure(parsed);
        assertTrue(sections.get(0).getHeadingPath().isEmpty(), "前言章节无标题路径");
        assertFalse(sections.get(0).getBlocks().isEmpty());
    }
}
