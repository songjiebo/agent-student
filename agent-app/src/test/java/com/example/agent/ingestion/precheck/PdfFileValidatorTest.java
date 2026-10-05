package com.example.agent.ingestion.precheck;

import com.example.agent.ingestion.config.AgentIngestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件安全校验测试：magic bytes / 大小限额 / 文件名消毒。
 */
class PdfFileValidatorTest {

    private final PdfFileValidator validator = new PdfFileValidator(new AgentIngestProperties());

    /** 合法 PDF 头 */
    private static byte[] pdfBytes(int padding) {
        byte[] magic = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        byte[] content = new byte[magic.length + padding];
        System.arraycopy(magic, 0, content, 0, magic.length);
        return content;
    }

    @Test
    @DisplayName("合法 PDF 通过校验")
    void acceptsValidPdf() {
        assertDoesNotThrow(() -> validator.validate("a.pdf", pdfBytes(100)));
    }

    @Test
    @DisplayName("伪装扩展名（非 PDF 字节）被拒绝")
    void rejectsNonPdfContent() {
        byte[] fake = "<html>evil</html>".getBytes(StandardCharsets.UTF_8);
        IngestRejectedException ex = assertThrows(IngestRejectedException.class,
                () -> validator.validate("fake.pdf", fake));
        assertTrue(ex.getMessage().contains("magic bytes"));
    }

    @Test
    @DisplayName("超过大小上限被拒绝")
    void rejectsOversizedFile() {
        AgentIngestProperties properties = new AgentIngestProperties();
        properties.getLimits().setMaxFileBytes(10);
        PdfFileValidator smallLimit = new PdfFileValidator(properties);
        assertThrows(IngestRejectedException.class, () -> smallLimit.validate("a.pdf", pdfBytes(100)));
    }

    @Test
    @DisplayName("空文件被拒绝")
    void rejectsEmptyFile() {
        assertThrows(IngestRejectedException.class, () -> validator.validate("a.pdf", new byte[0]));
    }

    @Test
    @DisplayName("文件名消毒：剥离路径穿越与控制字符")
    void sanitizesPathTraversal() {
        String sanitized = PdfFileValidator.sanitizeFileName("../../etc/passwd.pdf");
        assertEquals("passwd.pdf", sanitized);

        String windows = PdfFileValidator.sanitizeFileName("C:\\evil\\x.pdf");
        assertEquals("x.pdf", windows);

        String control = PdfFileValidator.sanitizeFileName("a\u0000b.pdf");
        assertEquals("a_b.pdf", control);
    }

    @Test
    @DisplayName("空文件名回退为默认名")
    void fallsBackToDefaultName() {
        assertEquals("unnamed.pdf", PdfFileValidator.sanitizeFileName("  "));
    }
}
