-- =====================================================================
-- Spring AI ChatMemory 会话记忆表结构
-- 对应 Spring AI 官方 JdbcChatMemoryRepository 的默认 schema
-- 参考: https://docs.spring.io/spring-ai/reference/api/chat-memory.html
-- 适配 H2 (MODE=LEGACY) 内存数据库，可平滑切换到 MySQL / PostgreSQL
-- =====================================================================

-- 会话记忆主表：每次对话消息存一行
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    -- 主键：消息唯一标识（UUID）
    conversation_id VARCHAR(64) NOT NULL,
    -- 会话内消息序号（用于还原对话顺序）
    message_index   bigint  NOT NULL,
    -- 消息内容（JSON 序列化后的 Message 对象）
    content         TEXT         NOT NULL,
    -- 消息类型：USER / ASSISTANT / SYSTEM / TOOL
    type            VARCHAR(10)  NOT NULL,
    -- 创建时间，便于清理过期会话
    create_time datetime NOT NULL ,
    PRIMARY KEY (conversation_id, message_index)
);

-- 按会话 ID + 时间范围查询（Advisor 加载历史时的高频路径）
CREATE INDEX IF NOT EXISTS idx_chat_memory_conversation_ts
    ON SPRING_AI_CHAT_MEMORY (conversation_id, create_time);
