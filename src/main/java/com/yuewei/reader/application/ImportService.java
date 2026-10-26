package com.yuewei.reader.application;

import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.SourceType;
import com.yuewei.reader.domain.TocChapter;
import com.yuewei.reader.infrastructure.cache.ChapterCache;
import com.yuewei.reader.infrastructure.db.BookDao;
import com.yuewei.reader.infrastructure.db.ChapterDao;
import com.yuewei.reader.infrastructure.txt.ChapterSplitter;
import com.yuewei.reader.infrastructure.txt.EncodingDetector;
import com.yuewei.reader.infrastructure.txt.TextCleaner;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 本地 TXT 导入：编码识别、分章、落库与缓存。 */
public class ImportService {

    private final BookDao bookDao;
    private final ChapterDao chapterDao;
    private final ChapterCache cache;

    public ImportService(BookDao bookDao, ChapterDao chapterDao, ChapterCache cache) {
        this.bookDao = bookDao;
        this.chapterDao = chapterDao;
        this.cache = cache;
    }

    public ImportPreview preview(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) {
            throw new AppException("文件内容为空");
        }
        ImportPreview preview = new ImportPreview();
        preview.fileName = file.getFileName().toString();
        preview.defaultTitle = stripExtension(file.getFileName().toString());
        preview.fileSize = bytes.length;
        EncodingDetector.Result decoded = EncodingDetector.decode(bytes);
        preview.charset = decoded.charset();
        try {
            ChapterSplitter.Result result = ChapterSplitter.splitAuto(decoded.text());
            preview.chapterCount = result.titles.size();
            preview.chapterTitles = result.titles.subList(0, Math.min(8, result.titles.size()));
        } catch (ChapterSplitter.NoChaptersException e) {
            preview.error = e.getMessage();
        }
        return preview;
    }

    public Book importBook(Path file, Charset charset, ChapterSplitter.Mode mode, String regex,
                           String title, String author, boolean forceDuplicate) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) {
            throw new AppException("文件内容为空");
        }
        String hash = sha256(bytes);
        Book existing = bookDao.findByHash(hash);
        if (existing != null && !forceDuplicate) {
            throw new AppException("该书已在书架：" + existing.title);
        }

        String text = charset == null
                ? EncodingDetector.decode(bytes).text()
                : EncodingDetector.decodeWith(bytes, charset);
        if (text.isBlank()) {
            throw new AppException("文件内容为空或不是文本");
        }

        ChapterSplitter.Result split = switch (mode) {
            case FIXED -> ChapterSplitter.splitByFixedSize(text, 3000);
            case SINGLE -> ChapterSplitter.singleChapter(text, "全文");
            case REGEX -> ChapterSplitter.splitByRegex(text, regex);
            default -> ChapterSplitter.splitAuto(text);
        };

        Book book = new Book();
        book.title = title == null || title.isBlank() ? stripExtension(file.getFileName().toString()) : title.trim();
        book.author = author == null || author.isBlank() ? "未知" : author.trim();
        book.sourceType = SourceType.LOCAL;
        book.chapterCount = split.titles.size();
        book.fileHash = hash;
        book.offline = true;
        long bookId = bookDao.insert(book);
        book.id = bookId;

        try {
            List<TocChapter> chapters = new ArrayList<>();
            List<Integer> wordCounts = new ArrayList<>();
            for (int i = 0; i < split.titles.size(); i++) {
                String content = TextCleaner.cleanLocal(split.contents.get(i));
                chapters.add(new TocChapter(i, split.titles.get(i), null));
                wordCounts.add(content.length());
                cache.write(bookId, i, content);
            }
            chapterDao.insertCachedBatch(bookId, chapters, wordCounts);
            return book;
        } catch (RuntimeException e) {
            bookDao.delete(bookId);
            cache.deleteBook(bookId);
            throw e;
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
