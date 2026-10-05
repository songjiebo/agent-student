package com.example.agent.ingestion.precheck;

import com.example.agent.ingestion.config.AgentIngestProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 上传文件安全校验（默认输入不可信）。
 *
 * <p>覆盖的攻击面：
 * <ul>
 *   <li>伪装扩展名：只信 magic bytes（%PDF-），不信任 Content-Type 与文件后缀；</li>
 *   <li>资源耗尽：文件字节数上限；</li>
 *   <li>路径穿越：文件名消毒后仅作为展示名，落盘名由服务端生成；</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PdfFileValidator {

    /** PDF magic bytes：合法 PDF 必须以 %PDF- 开头（允许前导空白/BOM 类噪音 ≤ 1KB） */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    /** 允许扫描 magic bytes 的前置噪音长度 */
    private static final int MAGIC_SCAN_WINDOW = 1024;

    /** 文件名最大长度（展示用） */
    private static final int MAX_FILENAME_LENGTH = 200;

    private final AgentIngestProperties properties;

    /**
     * 校验文件大小与 PDF magic bytes。
     *
     * @param fileName 原始文件名（仅用于展示/记录）
     * @param content  文件字节
     * @throws IngestRejectedException 校验失败（不可重试）
     */
    public void validate(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new IngestRejectedException("文件为空");
        }
        long maxBytes = properties.getLimits().getMaxFileBytes();
        if (content.length > maxBytes) {
            throw new IngestRejectedException(
                    "文件超过大小限制：" + content.length + " > " + maxBytes + " 字节");
        }
        if (!hasPdfMagic(content)) {
            throw new IngestRejectedException("文件不是合法 PDF（magic bytes 校验失败）：" + sanitizeFileName(fileName));
        }
    }

    /**
     * 判断字节流是否为 PDF：在前 1KB 窗口内查找 "%PDF-"，兼容带前置空白的变种。
     *
     * @param content 文件字节
     * @return true 表示通过 magic bytes 校验
     */
    private boolean hasPdfMagic(byte[] content) {
        int window = Math.min(content.length, MAGIC_SCAN_WINDOW);
        for (int i = 0; i + PDF_MAGIC.length <= window; i++) {
            boolean matched = true;
            for (int j = 0; j < PDF_MAGIC.length; j++) {
                if (content[i + j] != PDF_MAGIC[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    /**
     * 文件名消毒：剥离路径分隔符与控制字符（防路径穿越），截断长度。
     * 仅影响展示名；落盘文件名由服务端生成，绝不使用用户提供的路径。
     *
     * @param fileName 原始文件名
     * @return 安全的展示用文件名
     */
    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unnamed.pdf";
        }
        // 取最后一段，防止 "a/b/c.pdf" 或 "..\\..\\x.pdf" 形式的路径穿越
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && sb.length() < MAX_FILENAME_LENGTH; i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? "unnamed.pdf" : result.toLowerCase(Locale.ROOT).endsWith(".pdf") ? result : result + ".pdf";
    }
}
