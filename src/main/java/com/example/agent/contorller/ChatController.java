package com.example.agent.contorller;

import com.example.agent.function.TestFunction;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.spring.ai.agentexecutor.AgentExecutor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * AI 对话控制器，演示 Tool Calling（工具调用）能力。
 */
@RestController
@RequestMapping("/ai")
public class ChatController {

    @Autowired
    private OpenAiChatModel openAiChatModel;

    @Autowired
    private TestFunction testFunction;

    /**
     * 对话接口：将用户输入交给模型，并挂载 @Tool 标注的工具方法供模型调用。
     * 注意：defaultTools 需要传入对象实例（Spring AI 1.1+ 不再支持 bean name 字符串注册）。
     *
     * @param input 用户输入
     * @return 流式响应内容
     */
    @GetMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chat(String input) {
        ChatClient chatClient = ChatClient.builder(openAiChatModel)
                .defaultTools(testFunction)
                .build();
        return chatClient.prompt(input).stream().content();
    }

    /**
     * Agent 接口：使用 LangGraph4j AgentExecutor 编排 Tool Calling 流程。
     * 注意：langgraph4j 1.8.x 使用 Map 形式的 stream 重载（1.9 起废弃，但本项目锁定 1.8.x 与 Spring AI 1.1.2 兼容）。
     *
     * @param input 用户输入
     * @return Agent 执行结果文本
     * @throws GraphStateException 图编译异常
     */
    @GetMapping(value = "/agent", produces = "text/html;charset=utf-8")
    public String agent(String input) throws GraphStateException {
        var agent = AgentExecutor.builder()
                .chatModel(openAiChatModel)
                .toolsFromObject(testFunction)
                .build()
                .compile();

        // langgraph4j 1.8.x: messages 必须是 UserMessage 对象（不是 String），否则节点执行时会 ClassCastException
        var result = agent.stream(Map.of("messages", new UserMessage(input)), RunnableConfig.builder().build());

        var finalState = result.stream()
                .reduce((a, b) -> b)
                .orElseThrow()
                .state();
        return finalState.lastMessage().map(Object::toString).orElse("");
    }
}
