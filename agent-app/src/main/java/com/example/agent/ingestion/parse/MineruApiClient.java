package com.example.agent.ingestion.parse;

import com.example.agent.ingestion.config.AgentIngestProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MinerU 4.x 自托管 V1 HTTP API 客户端（复杂版式解析兜底）。
 *
 * <p>请求周期（与官方 scripts/http_api_example.sh 契约一致）：
 * <ol>
 *   <li>POST /v1/uploads 创建上传（携带 sha256sum，服务端可秒级去重复用）；</li>
 *   <li>pending 时按响应返回的 method/url/headers 上传字节，再 POST /v1/uploads/{id}/complete；</li>
 *   <li>POST /v1/parse/jobs 提交解析任务；</li>
 *   <li>GET /v1/parse/jobs/{job_id} 有界轮询到终态（completed/partial/failed/canceled）；</li>
 *   <li>GET /v1/files/{file_id}/content 下载产物。</li>
 * </ol>
 *
 * <p>安全约束（与官方 SDK 规则一致）：Bearer 凭证只在与 API 同源（scheme+host+端口）的
 * 上传地址上携带；跨源预签名 URL（对象存储）绝不附带 MinerU 凭证，防止密钥泄露。
 */
@Slf4j
@Component
public class MineruApiClient {

    /** 上传用途标识（官方契约固定值） */
    private static final String UPLOAD_PURPOSE = "parse";

    private final AgentIngestProperties properties;
    private final RestClient restClient;

    public MineruApiClient(AgentIngestProperties properties) {
        this.properties = properties;
        AgentIngestProperties.Mineru cfg = properties.getMineru();
        // JDK HttpClient：连接/读取超时显式设置，避免外部服务挂起拖死入库线程
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(cfg.getReadTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 解析 PDF 为 Markdown 文本（完整走一遍 上传 → 任务 → 轮询 → 下载 周期）。
     *
     * @param file PDF 文件路径
     * @return MinerU 产出的 Markdown
     * @throws MineruUnavailableException 服务不可用 / 任务失败 / 轮询超预算（路由层降级）
     */
    public String parseToMarkdown(Path file) {
        AgentIngestProperties.Mineru cfg = properties.getMineru();
        try {
            String fileId = upload(file);
            String jobId = createJob(fileId, cfg);
            MineruJobFile jobFile = pollUntilTerminal(jobId, cfg);
            return downloadMarkdown(jobFile);
        } catch (MineruUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new MineruUnavailableException("MinerU 服务调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传文件字节并返回 MinerU 侧 file_id（sha256 命中时服务端直接复用，跳过字节上传）。
     */
    private String upload(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String sha256 = com.example.agent.ingestion.util.Hashing.sha256Hex(bytes);

        Map<String, Object> createBody = Map.of(
                "filename", file.getFileName().toString(),
                "bytes", bytes.length,
                "mime_type", "application/pdf",
                "purpose", UPLOAD_PURPOSE,
                "sha256sum", sha256);
        UploadCreateResponse created = restClient.post()
                .uri("/v1/uploads")
                .body(createBody)
                .retrieve()
                .body(UploadCreateResponse.class);
        if (created == null || created.id() == null || created.id().isBlank()) {
            throw new MineruUnavailableException("MinerU /v1/uploads 响应缺少 upload id");
        }

        // completed = sha256 已存在，服务端直接返回 file 对象，无需再传字节
        if ("completed".equals(created.status()) && created.file() != null) {
            return created.file().id();
        }
        if (!"pending".equals(created.status())) {
            throw new MineruUnavailableException("MinerU 上传创建返回未知状态: " + created.status());
        }

        putBytes(created, bytes);
        UploadCreateResponse completed = restClient.post()
                .uri("/v1/uploads/{id}/complete", created.id())
                .retrieve()
                .body(UploadCreateResponse.class);
        if (completed == null || completed.file() == null || completed.file().id() == null) {
            throw new MineruUnavailableException("MinerU 上传完成响应缺少 file id");
        }
        return completed.file().id();
    }

    /**
     * 按服务端返回的 method/url/headers 上传字节。
     * 跨源上传地址（对象存储预签名 URL）不携带 MinerU 凭证。
     */
    private void putBytes(UploadCreateResponse created, byte[] bytes) {
        URI uploadUri = resolveUploadUrl(created.uploadUrl());
        boolean sameOrigin = isSameOrigin(uploadUri);
        Map<String, String> uploadHeaders = created.uploadHeaders() == null ? Map.of() : created.uploadHeaders();

        restClient.method(org.springframework.http.HttpMethod.valueOf(
                        created.uploadMethod() == null || created.uploadMethod().isBlank() ? "PUT" : created.uploadMethod()))
                .uri(uploadUri)
                .headers(headers -> {
                    uploadHeaders.forEach(headers::set);
                    if (sameOrigin) {
                        applyAuth(headers);
                    }
                })
                .body(bytes)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 提交解析任务，返回 job_id。
     */
    private String createJob(String fileId, AgentIngestProperties.Mineru cfg) {
        Map<String, Object> source = Map.of("type", "file_id", "file_id", fileId);
        Map<String, Object> body = Map.of(
                "files", List.of(Map.of("source", source)),
                "tier", cfg.getTier(),
                "output_formats", cfg.getOutputFormats());
        MineruJobResponse job = restClient.post()
                .uri("/v1/parse/jobs")
                .body(body)
                .retrieve()
                .body(MineruJobResponse.class);
        if (job == null || job.jobId() == null || job.jobId().isBlank()) {
            throw new MineruUnavailableException("MinerU /v1/parse/jobs 响应缺少 job_id");
        }
        return job.jobId();
    }

    /**
     * 有界轮询任务到终态（防止服务假死导致线程无限挂起）。
     */
    private MineruJobFile pollUntilTerminal(String jobId, AgentIngestProperties.Mineru cfg) throws InterruptedException {
        MineruJobResponse job = null;
        for (int poll = 0; poll < cfg.getMaxPolls(); poll++) {
            job = restClient.get()
                    .uri("/v1/parse/jobs/{jobId}", jobId)
                    .retrieve()
                    .body(MineruJobResponse.class);
            if (job == null || job.status() == null) {
                throw new MineruUnavailableException("MinerU 任务状态响应非法");
            }
            if (isTerminal(job.status())) {
                return resolveJobFile(job);
            }
            Thread.sleep(cfg.getPollIntervalMs());
        }
        throw new MineruUnavailableException("MinerU 任务轮询超预算（job_id=" + jobId
                + ", lastStatus=" + (job == null ? "null" : job.status()) + "），任务未取消，可继续轮询");
    }

    /**
     * 校验终态并取单文件结果：partial 时仅接受其中已完成的文件。
     */
    private MineruJobFile resolveJobFile(MineruJobResponse job) {
        MineruJobFile file = job.files() == null || job.files().isEmpty() ? null : job.files().get(0);
        if (file == null) {
            throw new MineruUnavailableException("MinerU 任务结果缺少文件条目（status=" + job.status() + "）");
        }
        if ("completed".equals(file.status())) {
            return file;
        }
        if ("partial".equals(job.status()) && "completed".equals(file.status())) {
            return file;
        }
        String errorCode = file.error() == null ? "unknown" : file.error().code();
        String errorMessage = file.error() == null ? "" : String.valueOf(file.error().message());
        throw new MineruUnavailableException("MinerU 任务未成功: jobStatus=" + job.status()
                + ", fileStatus=" + file.status() + ", code=" + errorCode + ", message=" + errorMessage);
    }

    /**
     * 下载 Markdown 产物。
     */
    private String downloadMarkdown(MineruJobFile file) {
        if (file.outputFiles() == null || file.outputFiles().isEmpty()) {
            throw new MineruUnavailableException("MinerU 文件结果缺少产物引用: " + file.name());
        }
        MineruOutputFile markdown = file.outputFiles().get("markdown");
        if (markdown == null || markdown.fileId() == null) {
            throw new MineruUnavailableException("MinerU 文件结果缺少 markdown 产物: " + file.name());
        }
        String content = restClient.get()
                .uri("/v1/files/{fileId}/content", markdown.fileId())
                .retrieve()
                .body(String.class);
        return content == null ? "" : content;
    }

    /**
     * 解析上传地址（服务端可能返回相对路径），并与 API 基址做同源比较。
     */
    private URI resolveUploadUrl(String uploadUrl) {
        if (uploadUrl == null || uploadUrl.isBlank()) {
            throw new MineruUnavailableException("MinerU 上传响应缺少 upload_url");
        }
        return URI.create(properties.getMineru().getBaseUrl()).resolve(uploadUrl);
    }

    /**
     * 同源判断：scheme + host + 有效端口（缺省端口按 scheme 归一）。
     * 仅同源请求携带 Bearer 凭证，跨源（对象存储预签名 URL）绝不附带。
     */
    private boolean isSameOrigin(URI target) {
        URI base = URI.create(properties.getMineru().getBaseUrl());
        return Objects.equals(base.getScheme(), target.getScheme())
                && Objects.equals(base.getHost(), target.getHost())
                && effectivePort(base) == effectivePort(target);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void applyAuth(org.springframework.http.HttpHeaders headers) {
        String apiKey = properties.getMineru().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }
    }

    private boolean isTerminal(String status) {
        return "completed".equals(status) || "partial".equals(status)
                || "failed".equals(status) || "canceled".equals(status);
    }

    // ==================== MinerU V1 API DTO（字段名与官方契约一一对应） ====================

    /**
     * POST /v1/uploads 响应。
     */
    public record UploadCreateResponse(
            @JsonProperty("id") String id,
            @JsonProperty("status") String status,
            @JsonProperty("upload_url") String uploadUrl,
            @JsonProperty("upload_method") String uploadMethod,
            @JsonProperty("upload_headers") Map<String, String> uploadHeaders,
            @JsonProperty("file") MineruFile file) {
    }

    /**
     * MinerU 侧文件对象。
     */
    public record MineruFile(@JsonProperty("id") String id) {
    }

    /**
     * /v1/parse/jobs 创建与轮询响应。
     */
    public record MineruJobResponse(
            @JsonProperty("job_id") String jobId,
            @JsonProperty("status") String status,
            @JsonProperty("files") List<MineruJobFile> files) {
    }

    /**
     * 任务内单文件结果。
     */
    public record MineruJobFile(
            @JsonProperty("name") String name,
            @JsonProperty("status") String status,
            @JsonProperty("error") MineruJobFileError error,
            @JsonProperty("output_files") Map<String, MineruOutputFile> outputFiles) {
    }

    /**
     * 产物引用：格式名 → 文件 id。
     */
    public record MineruOutputFile(@JsonProperty("file_id") String fileId) {
    }

    /**
     * 文件级错误。
     */
    public record MineruJobFileError(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message) {
    }
}
