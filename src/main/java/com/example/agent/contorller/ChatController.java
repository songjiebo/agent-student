package com.example.agent.contorller;

import com.example.agent.function.TestFunction;
import jakarta.annotation.PostConstruct;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.spring.ai.agentexecutor.AgentExecutor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
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


    private ChatClient chatClient;


    private String conversationId="123";

    public ChatController() {

    }

    @PostConstruct
    public void init(){
        ChatMemory chatMemory=MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
        chatClient = ChatClient.builder(openAiChatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(testFunction)
                .build();
    }

    /**
     * 对话接口：将用户输入交给模型，并挂载 @Tool 标注的工具方法供模型调用。
     * defaultTools 传入 @Tool 对象实例，由 ChatClient 将工具挂载到本次模型调用。
     *
     * @param input 用户输入
     * @return 流式响应内容
     */
    @GetMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chat(String input) {
        return chatClient.prompt(input).advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId)).stream().content();
    }

    /**
     * Agent 接口：使用 LangGraph4j AgentExecutor 编排 Tool Calling 流程。
     * LangGraph4j 1.9 使用 GraphInput 封装初始状态。
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

        // AgentExecutor 的 messages 状态需要 Spring AI Message 对象。
        var result = agent.stream(GraphInput.args(Map.of("messages", new UserMessage(input))), RunnableConfig.empty());

        var finalState = result.stream()
                .reduce((a, b) -> b)
                .orElseThrow()
                .state();
        return finalState.lastMessage().map(Object::toString).orElse("");
    }
}
