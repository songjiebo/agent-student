package com.example.agent.ingestion.task;

import com.example.agent.ingestion.chunk.LayoutAwareChunker;
import com.example.agent.ingestion.config.AgentIngestProperties;
import com.example.agent.ingestion.layout.LayoutStructurer;
import com.example.agent.ingestion.model.Chunk;
import com.example.agent.ingestion.model.DocSection;
import com.example.agent.ingestion.obs.IngestMetrics;
import com.example.agent.ingestion.parse.ParseOutcome;
import com.example.agent.ingestion.parse.ParseStrategyRouter;
import com.example.agent.ingestion.precheck.IngestRejectedException;
import com.example.agent.ingestion.precheck.PdfFileValidator;
import com.example.agent.ingestion.precheck.PdfProbe;
import com.example.agent.ingestion.precheck.PdfProbeResult;
import com.example.agent.ingestion.quality.QualityReport;
import com.example.agent.ingestion.store.MilvusIngestWriter;
import com.example.agent.ingestion.util.Hashing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 文档入库编排服务：受理 → 异步执行全流水线 → 状态机落库。
 *
 * <p>幂等设计（三个层次，缺一不可）：
 * <ul>
 *   <li>受理层：内容 SHA-256 判重，同内容直接复用 docId，不重复解析；</li>
 *   <li>写入层：先按 docId 删旧向量再写（失败重试/重刷不产生重复数据）；</li>
 *   <li>chunk 层：chunkId 确定性生成，同文档重跑产出相同 ID。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestTaskService {

    /** docId 派生源字节数（取 hash 前 32 位十六进制） */
    private static final int DOC_ID_HASH_LENGTH = 32;

    private final AgentIngestProperties properties;
    private final PdfFileValidator fileValidator;
    private final PdfProbe pdfProbe;
    private final ParseStrategyRouter parseStrategyRouter;
    private final LayoutStructurer layoutStructurer;
    private final LayoutAwareChunker chunker;
    private final MilvusIngestWriter vectorWriter;
    private final IngestTaskRepository taskRepository;
    private final IngestMetrics metrics;

    @Qualifier("ingestExecutor")
    private final Executor ingestExecutor;

    /**
     * 受理上传：校验 → 落盘 → 判重 → 建任务 → 异步执行（接口秒回）。
     *
     * @param file 上传文件
     * @return 受理结果（taskId + 判重标记）
     * @throws IOException 文件落盘失败
     */
    public UploadResult submit(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String fileName = PdfFileValidator.sanitizeFileName(file.getOriginalFilename());

        // 安全校验：magic bytes / 大小限额（失败即拒绝，不进入任务链路）
        try {
            fileValidator.validate(fileName, bytes);
        } catch (IngestRejectedException e) {
            metrics.onRejected();
            throw e;
        }

        String fileSha256 = Hashing.sha256Hex(bytes);
        String docId = UUID.nameUUIDFromBytes(fileSha256.substring(0, DOC_ID_HASH_LENGTH).getBytes()).toString();

        // 判重：同 hash 已有完成任务 → 复用，不重复解析/入库
        Optional<IngestTask> existing = taskRepository.findCompletedBySha256(fileSha256);
        if (existing.isPresent()) {
            IngestTask task = existing.get();
            log.info("内容判重命中，复用已有文档: docId={}, 原任务={}", task.getDocId(), task.getTaskId());
            return new UploadResult(task.getTaskId(), task.getDocId(), task.getStatus().name(), true);
        }

        // 落盘：文件名由服务端生成（docId），杜绝用户路径注入
        Path storageDir = Path.of(properties.getStorageDir());
        Files.createDirectories(storageDir);
        Path stored = storageDir.resolve(docId + ".pdf");
        Files.write(stored, bytes);

        IngestTask task = IngestTask.builder()
                .taskId(UUID.randomUUID().toString())
                .docId(docId)
                .fileName(fileName)
                .filePath(stored.toAbsolutePath().toString())
                .fileSha256(fileSha256)
                .fileSize(bytes.length)
                .status(IngestTaskStatus.PENDING)
                .stage("ACCEPTED")
                .retryCount(0)
                .pipelineVersion(properties.getPipelineVersion())
                .build();
        taskRepository.insert(task);
        metrics.onSubmitted(bytes.length);

        // 异步执行：接口立即返回 taskId，进度通过查询接口获取
        ingestExecutor.execute(() -> run(task.getTaskId()));
        return new UploadResult(task.getTaskId(), docId, task.getStatus().name(), false);
    }

    /**
     * 流水线执行体（worker 线程）：PARSING → CHUNKING → EMBEDDING → 终态。
     * 全部异常兜底为 FAILED，不允许异常逃逸导致任务永远停留在中间态。
     *
     * @param taskId 任务 ID
     */
    public void run(String taskId) {
        IngestTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务不存在: " + taskId));
        long parseStart = System.currentTimeMillis();
        try {
            // 1. 预检 + 探测（安全限额、文档画像）
            taskRepository.updateProgress(taskId, IngestTaskStatus.PARSING, "PROBE", null);
            Path file = Path.of(task.getFilePath());
            PdfProbeResult probe = pdfProbe.probe(file);
            taskRepository.updateProgress(taskId, IngestTaskStatus.PARSING, "PARSE", probe.pageCount());

            // 2. 策略路由解析 + 质量评分（文本层 → OCR → MinerU 降级链）
            ParseOutcome outcome = parseStrategyRouter.route(file, probe);
            QualityReport report = outcome.report();
            taskRepository.updateParseResult(taskId, outcome.document().getStrategyName(), report.score());

            // 3. 结构化 + 父子切分
            taskRepository.updateProgress(taskId, IngestTaskStatus.CHUNKING, "STRUCTURE", probe.pageCount());
            List<DocSection> sections = layoutStructurer.structure(outcome.document());
            List<Chunk> chunks = chunker.chunk(task.getDocId(), sections);
            long parseMs = System.currentTimeMillis() - parseStart;

            // 4. 幂等写入向量库（先删后写 + 批量 + 退避重试）
            long embedStart = System.currentTimeMillis();
            taskRepository.updateProgress(taskId, IngestTaskStatus.EMBEDDING, "WRITE", probe.pageCount());
            vectorWriter.replaceDocument(task.getDocId(), task.getFileName(), task.getFileSha256(), chunks);
            long embedMs = System.currentTimeMillis() - embedStart;

            // 5. 终态：质量不达标 → NEED_REVIEW（已入库但显式暴露问题，绝不静默假成功）
            boolean needReview = outcome.degraded() || report.needReview();
            IngestTaskStatus terminal = needReview ? IngestTaskStatus.NEED_REVIEW : IngestTaskStatus.COMPLETED;
            taskRepository.updateResult(taskId, terminal, chunks.size(), null);
            if (needReview) {
                metrics.onNeedReview(chunks.size());
                log.warn("任务 {} 低质量入库，需人工复核: strategy={}, score={}",
                        taskId, outcome.document().getStrategyName(), report.score());
            } else {
                metrics.onCompleted(chunks.size(), parseMs, embedMs);
            }
            log.info("任务 {} 完成: status={}, strategy={}, score={}, chunks={}, parseMs={}, embedMs={}",
                    taskId, terminal, outcome.document().getStrategyName(), report.score(), chunks.size(), parseMs, embedMs);
        } catch (IngestRejectedException e) {
            // 预检拒绝：不可重试，明确告知用户原因
            taskRepository.updateResult(taskId, IngestTaskStatus.FAILED, null, "REJECTED: " + e.getMessage());
            metrics.onFailed();
            log.warn("任务 {} 被预检拒绝: {}", taskId, e.getMessage());
        } catch (Exception e) {
            taskRepository.updateResult(taskId, IngestTaskStatus.FAILED, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            metrics.onFailed();
            log.error("任务 {} 执行失败", taskId, e);
        }
    }

    /**
     * 人工重试：仅 FAILED / NEED_REVIEW 可重试；写入幂等保证重试安全。
     *
     * @param taskId 任务 ID
     * @return 重试受理结果
     */
    public UploadResult retry(String taskId) {
        IngestTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
        if (task.getStatus() != IngestTaskStatus.FAILED && task.getStatus() != IngestTaskStatus.NEED_REVIEW) {
            throw new IllegalStateException("当前状态不允许重试: " + task.getStatus());
        }
        taskRepository.markPendingForRetry(taskId);
        ingestExecutor.execute(() -> run(taskId));
        return new UploadResult(taskId, task.getDocId(), IngestTaskStatus.PENDING.name(), false);
    }

    /**
     * 文档级删除：清理向量 + 任务记录 + 落盘文件。
     *
     * @param docId 文档 ID
     */
    public void deleteDocument(String docId) {
        vectorWriter.deleteByDocId(docId);
        List<IngestTask> tasks = taskRepository.findByDocId(docId);
        taskRepository.deleteByDocId(docId);
        // 清理落盘文件（避免删除后残留敏感原文）
        for (IngestTask task : tasks) {
            try {
                Files.deleteIfExists(Path.of(task.getFilePath()));
            } catch (IOException e) {
                log.warn("落盘文件清理失败: {}", task.getFilePath());
            }
        }
        log.info("文档已删除: docId={}, 任务数={}", docId, tasks.size());
    }

    /**
     * 任务查询。
     *
     * @param taskId 任务 ID
     * @return 任务实体
     */
    public IngestTask getTask(String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
    }
}
