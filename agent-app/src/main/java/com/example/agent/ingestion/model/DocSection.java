package com.example.agent.ingestion.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 结构化后的章节：标题路径 + 归属块列表。
 * 章节是父 chunk 的切分单位，标题路径会作为上下文前缀注入子 chunk（提升检索对齐）。
 */
@Getter
@Builder
public class DocSection {

    /** 标题路径，如 ["第3章 收费标准", "3.2 优惠规则"] */
    private List<String> headingPath;

    /** 本节叶子标题；文档前言（无标题区）为空串 */
    private String title;

    /** 归属本节的块（按阅读顺序，已剔除页眉页脚） */
    private List<DocBlock> blocks;

    /** 起始页码（1 起），用于答案溯源 */
    private int startPage;
}
