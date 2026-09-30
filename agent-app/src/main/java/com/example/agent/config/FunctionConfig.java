package com.example.agent.config;

import com.example.agent.function.TestFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具 Bean 配置：将 TestFunction 注册为 Spring Bean，
 * 由 ChatClient.defaultTools(...) 引用并挂载到 LLM。
 */
@Configuration(proxyBeanMethods = false)
public class FunctionConfig {

    @Bean
    public TestFunction testFunction() {
        return new TestFunction();
    }

}
