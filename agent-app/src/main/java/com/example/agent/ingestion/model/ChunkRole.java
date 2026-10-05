package com.example.agent.ingestion.model;

/**
 * 父子 chunk 角色。
 */
public enum ChunkRole {

    /** 父 chunk：整节上下文，供 LLM 生成答案时回填 */
    PARENT,

    /** 子 chunk：embedding 检索单元 */
    CHILD
}
