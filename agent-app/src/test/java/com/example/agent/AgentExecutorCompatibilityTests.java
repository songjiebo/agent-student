package com.example.agent;

import com.example.agent.function.TestFunction;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.spring.ai.agentexecutor.AgentExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 LangGraph4j 1.9 与 Spring AI 2 的 AgentExecutor 基本调用链。
 */
class AgentExecutorCompatibilityTests {

    /**
     * 图应接收 GraphInput 中的 Spring AI 消息并返回模型回答。
     */
    @Test
    void agentProcessesGraphInput() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("处理完成")))));

        var agent = AgentExecutor.builder()
                .chatModel(chatModel)
                .toolsFromObject(new TestFunction())
                .build()
                .compile();

        var finalState = agent.stream(
                        GraphInput.args(Map.of("messages", new UserMessage("你好"))),
                        RunnableConfig.empty())
                .stream()
                .reduce((first, second) -> second)
                .orElseThrow()
                .state();

        assertTrue(finalState.lastMessage().orElseThrow().toString().contains("处理完成"));
    }
}
