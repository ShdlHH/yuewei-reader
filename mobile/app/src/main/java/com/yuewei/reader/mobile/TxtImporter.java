package com.yuewei.reader.mobile;

import com.yuewei.reader.infrastructure.txt.ChapterSplitter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** TXT 导入：编码识别 → 分章 → 章节落盘，复用桌面端同一套分章规则。 */
public final class TxtImporter {

    private TxtImporter() {
    }

    public static Library.Book importBook(Library library, String displayName, byte[] bytes)
            throws IOException {
        String text = decode(bytes);
        ChapterSplitter.Result split;
        try {
            split = ChapterSplitter.splitAuto(text);
        } catch (ChapterSplitter.NoChaptersException e) {
            split = ChapterSplitter.splitByFixedSize(text, 5000);
        }
        String hash = sha256(bytes);
        Library.Book existing = library.findByHash(hash);
        if (existing != null) {
            return existing;
        }

        Library.Book book = new Library.Book();
        book.id = hash.substring(0, 16);
        book.title = titleOf(displayName);
        book.author = "未知";
        book.hash = hash;
        book.addedAt = System.currentTimeMillis();
        List<String> titles = split.titles;
        List<String> contents = split.contents;
        for (int i = 0; i < contents.size(); i++) {
            String content = contents.get(i);
            library.writeChapter(book.id, i, content);
            book.chapterTitles.add(i < titles.size() ? titles.get(i) : "第" + (i + 1) + "节");
            book.chapterLengths.add(content.length());
        }
        if (book.chapterTitles.isEmpty()) {
            library.writeChapter(book.id, 0, text);
            book.chapterTitles.add(book.title);
            book.chapterLengths.add(text.length());
        }
        library.books.add(book);
        library.save();
        return book;
    }

    private static String titleOf(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "未命名";
        }
        int dot = displayName.lastIndexOf('.');
        String name = dot > 0 ? displayName.substring(0, dot) : displayName;
        return name.isBlank() ? "未命名" : name.trim();
    }

    /** BOM → 严格 UTF-8 → GB18030 → GBK → 容错 UTF-8。 */
    public static String decode(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        String utf8 = strict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        for (String name : new String[]{"GB18030", "GBK"}) {
            String decoded = strict(bytes, Charset.forName(name));
            if (decoded != null) {
                return decoded;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String strict(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                sb.append(String.format("%02x", value));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(bytes.length) + System.currentTimeMillis();
        }
    }
}
