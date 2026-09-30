package com.example.agent.contorller;

import com.example.agent.advisors.CustomLogAdvisors;
import com.example.agent.function.TestFunction;
import jakarta.annotation.PostConstruct;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.spring.ai.agentexecutor.AgentExecutor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
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

    @Autowired
    private SyncMcpToolCallbackProvider syncMcpToolCallbackProvider;

    @Autowired
    private VectorStore vectorStore;

    private ChatClient chatClient;

    @Autowired
    private  EmbeddingModel embeddingModel;


    private String conversationId="123";

    public ChatController() {

    }

    @PostConstruct
    public void init(){
        ChatMemory chatMemory=MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
        chatClient = ChatClient.builder(openAiChatModel)
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build(), ToolCallingAdvisor.builder().build(), QuestionAnswerAdvisor.builder(vectorStore).build(),MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();

    }

    @GetMapping("/embedding")
    public String embedding(@RequestParam(value = "message" ) String message){
        //EmbeddingResponse embeddingResponse = this.embeddingModel.embedForResponse(List.of(message));
        vectorStore.add(Arrays.asList(new Document(message)));
        return "ok";
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
        return chatClient.prompt(input).system("用户询问天气时，必须使用MCP工具去实时查询")
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .tools(syncMcpToolCallbackProvider)
                .stream()
                .content();
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
