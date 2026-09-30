package com.example.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证升级后 Spring 容器和 OpenAI ChatModel 配置可以正确初始化。
 */
@SpringBootTest
class AgentStudentApplicationTests {

    @Autowired
    private OpenAiChatModel chatModel;

    /**
     * 检查展平后的 chat 属性绑定到模型选项。
     */
    @Test
    void contextLoads() {
        var options = chatModel.getOptions();
        assertEquals("mimo-v2.6-flash", options.getModel());
        assertEquals(Double.valueOf(0.3), options.getTemperature());
        assertEquals("high", options.getReasoningEffort());
    }

}
