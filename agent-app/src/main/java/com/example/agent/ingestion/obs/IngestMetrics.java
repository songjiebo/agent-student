package com.example.agent.ingestion.obs;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 入库流水线内存指标（轻量版，未接 Actuator/Prometheus）。
 * 学习项目避免引入额外依赖；生产替换为 Micrometer + Prometheus 时，只需替换本组件的实现。
 */
@Component
public class IngestMetrics {

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong needReview = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong chunksWritten = new AtomicLong();
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong parseMillis = new AtomicLong();
    private final AtomicLong embedMillis = new AtomicLong();

    public void onSubmitted(long fileSizeBytes) {
        submitted.incrementAndGet();
        bytesIn.addAndGet(fileSizeBytes);
    }

    public void onCompleted(int chunkCount, long parseMs, long embedMs) {
        completed.incrementAndGet();
        chunksWritten.addAndGet(chunkCount);
        parseMillis.addAndGet(parseMs);
        embedMillis.addAndGet(embedMs);
    }

    public void onNeedReview(int chunkCount) {
        needReview.incrementAndGet();
        chunksWritten.addAndGet(chunkCount);
    }

    public void onFailed() {
        failed.incrementAndGet();
    }

    public void onRejected() {
        rejected.incrementAndGet();
    }

    /**
     * 导出当前指标快照。
     *
     * @return 指标名 → 值
     */
    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put("ingest.submitted", submitted.get());
        snapshot.put("ingest.completed", completed.get());
        snapshot.put("ingest.needReview", needReview.get());
        snapshot.put("ingest.failed", failed.get());
        snapshot.put("ingest.rejected", rejected.get());
        snapshot.put("ingest.chunksWritten", chunksWritten.get());
        snapshot.put("ingest.bytesIn", bytesIn.get());
        snapshot.put("ingest.parseMillisTotal", parseMillis.get());
        snapshot.put("ingest.embedMillisTotal", embedMillis.get());
        return snapshot;
    }
}
