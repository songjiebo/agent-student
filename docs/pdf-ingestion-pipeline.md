# PDF 抽取入库流水线（实现说明）

> 模块位置：`agent-app/src/main/java/com/example/agent/ingestion/`
> 设计目标：工业级 PDF 内容抽取 → 父子 chunk → embedding → Milvus，全链路异步、幂等、可降级、可复核。

## 一、流水线总览

```
POST /agent/ingest/documents（秒回 taskId）
  │
  ├─ 受理：magic bytes 校验 / 大小限额 / SHA-256 判重 / 服务端落盘
  │
  └─ 异步（ingestExecutor 线程池）
      PARSING   PdfProbe 文档画像 → ParseStrategyRouter 策略链解析
                ┌─ text-layer（PDFBox，order=10）：数字原生 PDF，含双栏阅读顺序修复
                ├─ rapid-ocr （order=20）：扫描件/文本层损坏，PP-OCRv4 中文识别
                └─ mineru    （order=30）：复杂版式兜底（自托管 V1 API）
                每个策略产出 → QualityScorer 评分，达标即采用；全不达标取最高分并降级
      CHUNKING  LayoutStructurer（页眉页脚剔除/表格聚合/标题层级/章节树）
                → LayoutAwareChunker（父子 chunk + 标题路径前缀）
      EMBEDDING MilvusIngestWriter（先删后写幂等 + 批量 + 指数退避重试）
      终态      COMPLETED / NEED_REVIEW（低质量入库显式暴露）/ FAILED（可重试）
```

## 二、核心设计决策

| 决策 | 理由 |
|---|---|
| 策略链 + 质量评分降级 | 廉价解析器覆盖多数文档，贵的解析只花在被评分筛出的文档上；宁可显式 NEED_REVIEW，绝不静默入库垃圾 |
| 父子 chunk | 子 chunk 小而准负责检索命中，父 chunk 整节回填 LLM；标题路径进 chunk 正文提升检索对齐 |
| chunkId 确定性生成 | `{docId}#p{n}c{m}`，同文档重跑产出相同 ID，与"先删后写"共同构成幂等语义 |
| 任务状态机落库 | 大文件不能阻塞 HTTP；任务表同时承担进度可见性与审计（策略/质量分/chunk 数） |
| docId 由内容 hash 派生 | 同内容天然同 docId，判重与幂等键统一 |

## 三、MinerU 集成契约（MinerU 4.x 自托管 V1 API）

服务启动：

```bash
mineru-kit api-server --host 127.0.0.1 --port 8000 --tier standard
```

请求周期（`MineruApiClient` 严格按此实现，契约源自官方 `scripts/http_api_example.sh`）：

| 步骤 | 请求 | 说明 |
|---|---|---|
| 1 | `POST /v1/uploads` | body: `{filename, bytes, mime_type, purpose:"parse", sha256sum}`；响应 `{id, status: pending\|completed, upload_url, upload_method, upload_headers, file{id}}`；sha256 命中时直接 `completed` 复用 |
| 2 | `PUT {upload_url}` | 仅 `pending` 时；按响应返回的 method/url/headers 传字节 |
| 3 | `POST /v1/uploads/{id}/complete` | 取 `file.id` |
| 4 | `POST /v1/parse/jobs` | body: `{files:[{source:{type:"file_id", file_id}}], tier, output_formats}` → `{job_id, status}` |
| 5 | `GET /v1/parse/jobs/{job_id}` | 有界轮询（max-polls），终态 `completed\|partial\|failed\|canceled` |
| 6 | `GET /v1/files/{file_id}/content` | `files[].output_files` 是 `{格式: {file_id}}` 映射，下载 markdown |

安全约束（与官方 SDK 规则一致）：Bearer 凭证只在**同源**（scheme+host+端口）上传地址携带；
跨源预签名 URL（对象存储）绝不附带 MinerU 凭证，防止密钥泄露。

## 四、配置参考（application.yml → `agent.ingest`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `limits.max-file-bytes` | 50MB | 上传大小上限（资源耗尽防护） |
| `limits.max-pages` | 1000 | 页数上限（PDF 炸弹防护） |
| `quality.accept-score` | 0.60 | 策略结果达标线，达标即采用 |
| `quality.need-review-score` | 0.40 | 低于此分入库但标记 NEED_REVIEW |
| `chunk.min/max-child-chars` | 200/800 | 检索单元粒度 |
| `chunk.max-parent-chars` | 4000 | LLM 上下文回填粒度 |
| `ocr.dpi` | 200 | 页面栅格化精度（识别率/耗时平衡） |
| `mineru.enabled` | false | 未部署 MinerU 服务时保持关闭 |
| `embed.batch-size` | 20 | embedding 单请求条数（**硬上限：DashScope 兼容模式 ≤ 20，超限直接 400**） |
| `embed.max-retries` | 3 | 仅对可重试错误生效（网络/超时/429/5xx）；参数类错误快速失败不重试 |

## 五、部署要点

1. **RapidOCR 原生库按平台打包**：`agent-app/pom.xml` 用 OS-activated profile 拉取
   `rapidocr-onnx-{macosx-arm64,macosx-x86_64,linux-x86_64,windows-x86_64}`。
   生产按目标部署平台构建（CI 矩阵），不要把所有平台包打进一个 jar。
   模型（PP-OCRv4 中文）随 `rapidocr-onnx-models` 依赖分发，无需手工下载。
2. **OCR 串行执行**：JNI 引擎非线程安全，`RapidOcrClient` 全局串行化；吞吐瓶颈在 OCR 时
   调大 `ingestExecutor` 无意义，应横向扩展实例或升级到 MinerU 服务。
3. **MinerU 服务**：自托管进程内状态（重启丢 upload/job id），本客户端任务不跨重启恢复，
   重启后失败任务通过 `POST /ingest/tasks/{taskId}/retry` 重跑（写入幂等，安全）。
4. **落盘目录** `agent.ingest.storage-dir` 默认 `./data/ingest`，生产替换为对象存储适配层。

## 六、API 速查

```bash
# 上传（秒回 taskId）
curl -F "file=@manual.pdf" http://localhost:8080/agent/ingest/documents
# 查任务（状态/策略/质量分/chunk 数/失败原因）
curl http://localhost:8080/agent/ingest/tasks/{taskId}
# 重试（FAILED / NEED_REVIEW）
curl -X POST http://localhost:8080/agent/ingest/tasks/{taskId}/retry
# 文档删除（向量 + 任务 + 落盘文件）
curl -X DELETE http://localhost:8080/agent/ingest/documents/{docId}
# 父子检索验证（子 chunk 召回 + 父上下文 + 溯源）
curl "http://localhost:8080/agent/ingest/search?query=收费标准&topK=5"
# 流水线指标
curl http://localhost:8080/agent/ingest/metrics
```

## 七、测试覆盖（28 个用例，全部 hermetic）

| 测试 | 覆盖点 |
|---|---|
| `PdfFileValidatorTest` | magic bytes 伪装、大小限额、路径穿越消毒 |
| `QualityScorerTest` | 分数区间、乱码降分、空文档低分、复核标记 |
| `LayoutStructurerTest` | 页眉页脚剔除、表格聚合、标题层级、章节路径、前言章节 |
| `LayoutAwareChunkerTest` | 父子结构、chunkId 确定性、表格整块保留、超长表格重复表头、长度约束 |
| `TextLayerPdfParserTest` | PDFBox 现场生成 PDF 端到端解析、字号保留、扫描件画像跳过 |
| `IngestTaskRepositoryTest` | 真实 schema 建表、判重、错误截断、重试计数、文档级删除 |
| `MilvusIngestWriterTest` | 批大小切分、400 参数错误快速失败、超时退避重试、429/5xx 可重试、瞬态恢复 |

## 八、已知限制与后续（P2+）

1. **MinerU Markdown 无页码**：其块 `pageNo` 记为 0，溯源退化为章节路径；
   页码级溯源需引入 `middle_json` 产物解析（版本相关，需按部署版本核对 schema）。
2. **跨栏表格**：文本层来源靠"2+ 空白列分隔"启发式识别表格，跨栏合并单元格无法还原；
   此类文档建议开 `mineru.enabled` 走 MinerU。
3. **公式**：文本层抽出的是公式字形碎片，会作为普通段落入库；公式密集文档同上。
4. **增量更新**：当前"同内容判重、异内容新 docId"，文档更新后的旧版本清理需 P2 的版本管理。
5. **指标**：`IngestMetrics` 为内存计数，生产替换 Micrometer + Prometheus（组件接口不变）。
