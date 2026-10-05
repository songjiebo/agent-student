package com.example.agent.ingestion.api;

import com.example.agent.ingestion.obs.IngestMetrics;
import com.example.agent.ingestion.store.RagRetriever;
import com.example.agent.ingestion.task.IngestTaskService;
import com.example.agent.ingestion.task.UploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 文档入库 API：上传受理（秒回）+ 任务进度 + 重试 + 删除 + 检索验证。
 */
@RestController
@RequestMapping("/ingest")
@RequiredArgsConstructor
public class DocIngestController {

    private final IngestTaskService ingestTaskService;
    private final RagRetriever ragRetriever;
    private final IngestMetrics ingestMetrics;

    /**
     * 上传 PDF：校验落盘后异步执行入库流水线，立即返回 taskId。
     *
     * @param file 上传的 PDF 文件（form 字段名 file）
     * @return 202 + 受理结果（taskId/docId/判重标记）
     */
    @PostMapping("/documents")
    public ResponseEntity<UploadResult> upload(@RequestParam("file") MultipartFile file) throws IOException {
        UploadResult result = ingestTaskService.submit(file);
        // 判重命中（reused=true）语义上是"已存在"，返回 200；新任务返回 202
        return ResponseEntity.status(result.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(result);
    }

    /**
     * 查询任务进度/结果。
     *
     * @param taskId 任务 ID
     * @return 任务状态（含策略、质量分、chunk 数、失败原因）
     */
    @GetMapping("/tasks/{taskId}")
    public IngestTaskResponse task(@PathVariable("taskId") String taskId) {
        return IngestTaskResponse.from(ingestTaskService.getTask(taskId));
    }

    /**
     * 重试任务（FAILED / NEED_REVIEW 可重试，写入幂等保证安全）。
     *
     * @param taskId 任务 ID
     * @return 重试受理结果
     */
    @PostMapping("/tasks/{taskId}/retry")
    public UploadResult retry(@PathVariable("taskId") String taskId) {
        return ingestTaskService.retry(taskId);
    }

    /**
     * 文档级删除：向量 + 任务记录 + 落盘文件一并清理。
     *
     * @param docId 文档 ID
     * @return 204
     */
    @DeleteMapping("/documents/{docId}")
    public ResponseEntity<Void> delete(@PathVariable("docId") String docId) {
        ingestTaskService.deleteDocument(docId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 父子检索验证接口：子 chunk 召回 + 父上下文回填 + 溯源信息。
     * 用于入库后人工验证检索质量（生产可替换为对接 ChatClient 的 QuestionAnswerAdvisor）。
     *
     * @param query 用户问题
     * @param docId 可选，限定单文档
     * @param topK  召回条数，默认 5
     * @return 检索结果
     */
    @GetMapping("/search")
    public List<RagRetriever.RetrievedChunk> search(@RequestParam("query") String query,
                                                    @RequestParam(value = "docId", required = false) String docId,
                                                    @RequestParam(value = "topK", defaultValue = "5") int topK) {
        return ragRetriever.search(query, docId, topK);
    }

    /**
     * 流水线指标快照（内存累计值）。
     *
     * @return 指标名 → 值
     */
    @GetMapping("/metrics")
    public Map<String, Long> metrics() {
        return ingestMetrics.snapshot();
    }
}
