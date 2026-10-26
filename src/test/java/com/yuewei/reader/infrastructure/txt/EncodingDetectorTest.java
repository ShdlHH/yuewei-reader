package com.yuewei.reader.infrastructure.txt;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncodingDetectorTest {

    private static final String SAMPLE = "第一章 开始\n这是一段中文内容，包含标点符号。";

    @Test
    void detectsUtf8WithBom() {
        byte[] body = SAMPLE.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xEF);
        out.write(0xBB);
        out.write(0xBF);
        out.write(body, 0, body.length);

        EncodingDetector.Result result = EncodingDetector.decode(out.toByteArray());
        assertEquals(StandardCharsets.UTF_8, result.charset());
        assertEquals(SAMPLE, result.text());
    }

    @Test
    void detectsGb18030() {
        byte[] bytes = SAMPLE.getBytes(Charset.forName("GBK"));
        EncodingDetector.Result result = EncodingDetector.decode(bytes);
        assertTrue(result.charset().name().startsWith("GB"), "实际识别：" + result.charset());
        assertEquals(SAMPLE, result.text());
    }

    @Test
    void detectsUtf16LeWithBom() {
        byte[] body = SAMPLE.getBytes(StandardCharsets.UTF_16LE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xFE);
        out.write(body, 0, body.length);

        EncodingDetector.Result result = EncodingDetector.decode(out.toByteArray());
        assertEquals(StandardCharsets.UTF_16LE, result.charset());
        assertEquals(SAMPLE, result.text());
    }

    @Test
    void decodeWithExplicitCharset() {
        byte[] bytes = SAMPLE.getBytes(Charset.forName("GBK"));
        assertEquals(SAMPLE, EncodingDetector.decodeWith(bytes, Charset.forName("GBK")));
    }
}
