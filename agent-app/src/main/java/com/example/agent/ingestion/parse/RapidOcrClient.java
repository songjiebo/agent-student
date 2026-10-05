package com.example.agent.ingestion.parse;

import com.benjaminwan.ocrlibrary.OcrResult;
import com.benjaminwan.ocrlibrary.TextBlock;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * RapidOCR 封装（纯 Java OCR：JNI + onnxruntime + PP-OCRv4 中文模型）。
 *
 * <p>工程要点：
 * <ul>
 *   <li>引擎是 JNI 单例且非线程安全，所有识别请求串行化，避免并发踩内存；</li>
 *   <li>原生库/模型加载失败属于"环境问题"而非"文档问题"，标记不可用并抛
 *       {@link OcrUnavailableException}，由策略路由降级，不影响主链路；</li>
 *   <li>识别输入是图片文件路径，页面栅格化结果写临时文件，识别完即清理。</li>
 * </ul>
 */
@Slf4j
@Component
public class RapidOcrClient {

    /** 引擎初始化成功标志 */
    private volatile boolean initialized;

    /** 不可用原因（为空且未初始化时为"尚未加载"） */
    private volatile String unavailableReason;

    /**
     * 识别单张图片，返回行级结果（文本 + 框坐标）。
     * JNI 调用串行化：synchronized 保证同一时刻只有一个识别任务进入原生层。
     *
     * @param imagePath 图片文件路径
     * @return 识别出的文本行（按框纵坐标自上而下）
     * @throws OcrUnavailableException 引擎不可用（环境问题，路由层降级）
     */
    public synchronized List<OcrLine> recognize(Path imagePath) {
        ensureInitialized();

        try {
            // RapidOCR JNI 接口只接受图片文件路径，页面栅格化结果由调用方落临时文件
            OcrResult result = InferenceEngine.getInstance(Model.ONNX_PPOCR_V4).runOcr(imagePath.toString());
            return toLines(result);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError | ExceptionInInitializerError e) {
            markUnavailable("RapidOCR 原生库加载失败: " + e.getMessage());
            throw new OcrUnavailableException(unavailableReason, e);
        } catch (Exception e) {
            // 识别单页失败不应拖垮整个文档，交由上层决定跳过该页或降级
            throw new OcrUnavailableException("RapidOCR 识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 RapidOCR 输出归一为行列表：按文本框左上角纵坐标排序（自上而下阅读顺序）。
     */
    private List<OcrLine> toLines(OcrResult result) {
        List<OcrLine> lines = new ArrayList<>();
        if (result == null || result.getTextBlocks() == null) {
            return lines;
        }
        for (TextBlock block : result.getTextBlocks()) {
            String text = block.getText() == null ? "" : block.getText().trim();
            if (text.isEmpty()) {
                continue;
            }
            int minY = Integer.MAX_VALUE;
            int minX = Integer.MAX_VALUE;
            if (block.getBoxPoint() != null) {
                for (var point : block.getBoxPoint()) {
                    minY = Math.min(minY, point.getY());
                    minX = Math.min(minX, point.getX());
                }
            }
            lines.add(new OcrLine(text, minX, minY, block.getBoxScore()));
        }
        lines.sort((a, b) -> Integer.compare(a.y(), b.y()));
        return lines;
    }

    /**
     * 引擎懒加载：首次识别时初始化；失败后短路，不再重复尝试。
     */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        if (unavailableReason != null) {
            throw new OcrUnavailableException(unavailableReason);
        }
        try {
            InferenceEngine.getInstance(Model.ONNX_PPOCR_V4);
            initialized = true;
            log.info("RapidOCR 引擎初始化完成（PP-OCRv4 中文模型）");
        } catch (Throwable t) {
            markUnavailable("RapidOCR 引擎初始化失败（缺少平台原生库或模型？）: " + t.getMessage());
            throw new OcrUnavailableException(unavailableReason, t);
        }
    }

    private void markUnavailable(String reason) {
        this.unavailableReason = reason;
        log.warn("{}", reason);
    }

    /**
     * OCR 行中间模型：文本 + 文本框左上角坐标 + 置信度。
     *
     * @param text  行文本
     * @param x     框左上角 x（像素）
     * @param y     框左上角 y（像素）
     * @param score 文本框置信度
     */
    public record OcrLine(String text, int x, int y, float score) {
    }
}
