package com.example.agent.ingestion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 入库异步执行器配置。
 * 独立线程池隔离：解析/OCR 是 CPU 密集 + 长耗时任务，不能占用 Web 容器线程，
 * 也不能被对话请求的线程竞争拖垮。队列有界 + CallerRunsPolicy 防止任务堆积 OOM。
 */
@Configuration
public class IngestAsyncConfig {

    /**
     * 入库任务线程池。
     * 参数取值理由：解析是 CPU 密集型（PDFBox/OCR），核心数取 2（学习环境保守值，
     * 生产按 CPU 核数与 embedding QPM 额度调整）。
     *
     * @return 线程池执行器
     */
    @Bean(name = "ingestExecutor")
    public Executor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ingest-");
        // 队列满时由调用线程执行（背压传导，避免静默丢任务）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
