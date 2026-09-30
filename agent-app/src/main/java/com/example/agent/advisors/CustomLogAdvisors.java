package com.example.agent.advisors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;



@Slf4j
public class CustomLogAdvisors implements CallAdvisor, StreamAdvisor {
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        log.info("com.example.agent.advisors.CustomLogAdvisors.adviseCall()调用前:{}", chatClientRequest.prompt().getUserMessage().getText());
        ChatClientResponse chatClientResponse=callAdvisorChain.nextCall(chatClientRequest);
        log.info("com.example.agent.advisors.CustomLogAdvisors.adviseCall()调用后:{}", chatClientResponse.chatResponse().getResult().getOutput().getText());
        return chatClientResponse;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        log.info("com.example.agent.advisors.CustomLogAdvisors.adviseStream()调用前:{}", chatClientRequest.prompt().getUserMessage().getText());
        Flux<ChatClientResponse> chatClientResponse=streamAdvisorChain.nextStream(chatClientRequest);
        return new ChatClientMessageAggregator().aggregateChatClientResponse(chatClientResponse,resp->{
            log.info("com.example.agent.advisors.CustomLogAdvisors.adviseStream()调用后:{}", resp.chatResponse().getResult().getOutput().getText());

        });
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
