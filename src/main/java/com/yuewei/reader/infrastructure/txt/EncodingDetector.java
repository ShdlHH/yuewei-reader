package com.yuewei.reader.infrastructure.txt;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TXT 编码识别：BOM → 严格 UTF-8 → GB18030 → GBK → UTF-8 容错。
 */
public final class EncodingDetector {

    public record Result(String text, Charset charset) {
    }

    private EncodingDetector() {
    }

    public static Result fromFile(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    public static Result decode(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new Result(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new Result(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new Result(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE);
        }

        String utf8 = tryStrict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return new Result(utf8, StandardCharsets.UTF_8);
        }
        Charset gb18030 = Charset.forName("GB18030");
        String gb = tryStrict(bytes, gb18030);
        if (gb != null) {
            return new Result(gb, gb18030);
        }
        Charset gbk = Charset.forName("GBK");
        String gbkText = tryStrict(bytes, gbk);
        if (gbkText != null) {
            return new Result(gbkText, gbk);
        }
        return new Result(new String(bytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    public static String decodeWith(byte[] bytes, Charset charset) {
        if (charset.equals(StandardCharsets.UTF_8) && bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, charset);
        }
        return new String(bytes, charset);
    }

    private static String tryStrict(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
