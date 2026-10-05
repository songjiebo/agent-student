package com.example.agent.ingestion.model;

import lombok.Builder;
import lombok.Getter;

/**
 * 切分后的最终检索单元（父子 chunk 两级结构）。
 *
 * <p>两级设计的动机：
 * <ul>
 *   <li>子 chunk（CHILD）：小而准，用于 embedding 检索，保证召回粒度；</li>
 *   <li>父 chunk（PARENT）：整节上下文，命中子 chunk 后回填给 LLM，避免答案上下文被切碎。</li>
 * </ul>
 * chunkId 采用确定性生成（docId + 序号），同文档重复入库生成相同 ID，天然幂等。
 */
@Getter
@Builder
public class Chunk {

    /** 确定性 chunkId：{docId}#p{父序号}c{子序号}（父 chunk 子序号为 0） */
    private String chunkId;

    /** 所属父 chunkId；父 chunk 自身为 null */
    private String parentChunkId;

    /** 角色：PARENT（上下文整节）/ CHILD（检索单元） */
    private ChunkRole role;

    /** chunk 文本（子 chunk 带 "标题路径" 上下文前缀） */
    private String text;

    /** 起始页码（1 起） */
    private int pageNum;

    /** 同一父下的子序号（0 起），父 chunk 固定为 0 */
    private int chunkIndex;

    /** 标题路径，如 "第3章 收费标准 > 3.2 优惠规则" */
    private String sectionPath;

    /** 主导块类型；表格 chunk 为 TABLE（整块保留，不跨块硬切） */
    private String blockType;
}
