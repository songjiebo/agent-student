package com.example.agent.ingestion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 入库流水线配置装配。
 * {@link AgentIngestProperties} 仅标注 @ConfigurationProperties 不会注册为 Bean，
 * 必须在此显式启用绑定（或改用 @ConfigurationPropertiesScan 全局扫描）。
 */
@Configuration
@EnableConfigurationProperties(AgentIngestProperties.class)
public class IngestConfiguration {
}
