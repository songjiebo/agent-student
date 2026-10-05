package com.example.agent.ingestion.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * PDF 入库流水线配置（agent.ingest.*）。
 * 默认值按"混合文档画像"（文本型 + 扫描件 + 复杂版式混合）给保守值，均支持外部覆盖。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agent.ingest")
public class AgentIngestProperties {

    /** 上传文件落盘目录（生产建议替换为对象存储适配层） */
    private String storageDir = "./data/ingest";

    /** 解析/切分算法版本号；算法升级后按版本批量重刷向量 */
    private String pipelineVersion = "v1";

    /** 资源限额（安全防护） */
    private Limits limits = new Limits();

    /** 质量评分阈值 */
    private Quality quality = new Quality();

    /** 切分参数 */
    private Chunk chunk = new Chunk();

    /** RapidOCR 参数 */
    private Ocr ocr = new Ocr();

    /** MinerU 4.x 自托管 V1 API 参数 */
    private Mineru mineru = new Mineru();

    /** embedding 写入参数 */
    private Embed embed = new Embed();

    /** 资源限额：防 PDF 炸弹与资源耗尽 */
    @Getter
    @Setter
    public static class Limits {
        /** 单文件字节上限，默认 50MB */
        private long maxFileBytes = 50L * 1024 * 1024;
        /** 页数上限，默认 1000 页 */
        private int maxPages = 1000;
    }

    /** 质量阈值：驱动解析策略降级链 */
    @Getter
    @Setter
    public static class Quality {
        /** 达标分数：≥ 该值直接采用当前策略结果 */
        private double acceptScore = 0.60;
        /** 复核阈值：< 该值入库但标记 NEED_REVIEW */
        private double needReviewScore = 0.40;
        /** 每页最少有效字符，低于视为无文本层（扫描件特征） */
        private int minCharsPerPage = 50;
    }

    /** 父子 chunk 切分参数 */
    @Getter
    @Setter
    public static class Chunk {
        /** 子 chunk 目标下限（字符） */
        private int minChildChars = 200;
        /** 子 chunk 目标上限（字符） */
        private int maxChildChars = 800;
        /** 父 chunk 上限（字符），超出按段落二次拆分 */
        private int maxParentChars = 4000;
    }

    /** RapidOCR 参数 */
    @Getter
    @Setter
    public static class Ocr {
        /** 是否启用 OCR 策略（false 时扫描件直接降级到 MinerU 或 NEED_REVIEW） */
        private boolean enabled = true;
        /** 页面栅格化 DPI：识别率与耗时的平衡点 */
        private float dpi = 200f;
    }

    /** MinerU V1 HTTP API 参数（契约见 docs/pdf-ingestion-pipeline.md） */
    @Getter
    @Setter
    public static class Mineru {
        /** 是否启用 MinerU 兜底策略 */
        private boolean enabled = false;
        /** 服务根地址（api-server 监听地址，客户端自动拼 /v1 前缀） */
        private String baseUrl = "http://127.0.0.1:8000";
        /** Bearer 凭证；本地匿名访问留空 */
        private String apiKey = "";
        /** 解析质量档位：flash/basic/standard/advanced */
        private String tier = "standard";
        /** 需要拉取的产物格式 */
        private List<String> outputFormats = List.of("markdown");
        /** 任务轮询间隔（毫秒） */
        private long pollIntervalMs = 2000;
        /** 轮询上限次数（有界轮询，防止无限等待） */
        private int maxPolls = 300;
        /** 连接超时（毫秒） */
        private long connectTimeoutMs = 5000;
        /** 读超时（毫秒），解析大文档耗时长，需放宽 */
        private long readTimeoutMs = 120000;
    }

    /** embedding 批量写入参数 */
    @Getter
    @Setter
    public static class Embed {
        /** 每批写入 Milvus 的 chunk 数（受 embedding 服务 QPM 约束） */
        private int batchSize = 32;
        /** 单批最大重试次数 */
        private int maxRetries = 3;
        /** 重试指数退避基数（毫秒） */
        private long initialBackoffMs = 500;
    }
}
