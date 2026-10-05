package com.example.agent.ingestion.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文件摘要工具：SHA-256 是判重、增量更新、幂等键的统一事实依据。
 */
public final class Hashing {

    private Hashing() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * 计算文件 SHA-256（十六进制小写）。
     * 流式读取，避免大文件一次性载入内存。
     *
     * @param file 文件路径
     * @return 64 位十六进制摘要
     * @throws IOException 文件读取失败
     */
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    /**
     * 计算字节数组 SHA-256（十六进制小写）。
     *
     * @param bytes 输入字节
     * @return 64 位十六进制摘要
     */
    public static String sha256Hex(byte[] bytes) {
        return toHex(newSha256().digest(bytes));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // JDK 必带 SHA-256，走到这里说明运行环境被破坏
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
