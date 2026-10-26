package com.yuewei.reader.application;

import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.Chapter;
import com.yuewei.reader.domain.ReadingProgress;
import com.yuewei.reader.domain.SourceType;
import com.yuewei.reader.domain.TocChapter;
import com.yuewei.reader.infrastructure.cache.ChapterCache;
import com.yuewei.reader.infrastructure.db.BookDao;
import com.yuewei.reader.infrastructure.db.ChapterDao;
import com.yuewei.reader.infrastructure.db.ProgressDao;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 书架、章节与缓存读取。 */
public class BookService {

    private final BookDao bookDao;
    private final ChapterDao chapterDao;
    private final ProgressDao progressDao;
    private final ChapterCache cache;

    public BookService(BookDao bookDao, ChapterDao chapterDao, ProgressDao progressDao, ChapterCache cache) {
        this.bookDao = bookDao;
        this.chapterDao = chapterDao;
        this.progressDao = progressDao;
        this.cache = cache;
    }

    public List<Book> shelf(String query, SourceType filter, String sort) {
        List<Book> all = bookDao.findAll();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Book> filtered = new ArrayList<>();
        for (Book b : all) {
            if (filter != null && b.sourceType != filter) {
                continue;
            }
            if (!q.isEmpty()) {
                String title = b.title == null ? "" : b.title.toLowerCase(Locale.ROOT);
                String author = b.author == null ? "" : b.author.toLowerCase(Locale.ROOT);
                if (!title.contains(q) && !author.contains(q)) {
                    continue;
                }
            }
            filtered.add(b);
        }
        Comparator<Book> comparator;
        if ("added".equals(sort)) {
            comparator = Comparator.comparing((Book b) -> nullSafe(b.addedAt)).reversed();
        } else if ("title".equals(sort)) {
            Collator collator = Collator.getInstance(Locale.CHINA);
            comparator = (a, b) -> collator.compare(a.title == null ? "" : a.title,
                    b.title == null ? "" : b.title);
        } else {
            comparator = Comparator.comparing((Book b) -> nullSafe(
                    b.lastReadAt != null ? b.lastReadAt : b.addedAt)).reversed();
        }
        filtered.sort(comparator);
        return filtered;
    }

    public Book find(long id) {
        return bookDao.findById(id);
    }

    public List<Chapter> chapters(long bookId) {
        return chapterDao.findByBook(bookId);
    }

    public Chapter chapter(long bookId, int idx) {
        return chapterDao.findByIndex(bookId, idx);
    }

    public ReadingProgress progress(long bookId) {
        return progressDao.findByBook(bookId);
    }

    public void saveProgress(long bookId, int chapterIndex, int charOffset) {
        progressDao.save(bookId, chapterIndex, charOffset);
        bookDao.touchRead(bookId);
    }

    public void delete(long bookId, boolean deleteCache) {
        bookDao.delete(bookId);
        if (deleteCache) {
            cache.deleteBook(bookId);
        }
    }

    /** 下载后重新计算离线标记：全部章节已缓存才算离线。 */
    public boolean refreshOffline(long bookId) {
        int total = chapterDao.countByBook(bookId);
        int cached = chapterDao.countCached(bookId);
        boolean offline = total > 0 && cached >= total;
        bookDao.markOffline(bookId, offline);
        return offline;
    }

    public void clearCache(long bookId) {
        cache.deleteBook(bookId);
        chapterDao.resetCacheState(bookId);
        bookDao.markOffline(bookId, false);
    }

    public void clearOnlineCache() {
        for (Book b : bookDao.findAll()) {
            if (b.isOnline()) {
                clearCache(b.id);
            }
        }
    }

    public long cacheSize(long bookId) {
        return cache.sizeOfBook(bookId);
    }

    /** 所有书籍的正文缓存占用（字节）。 */
    public long totalCacheSize() {
        long sum = 0;
        for (Book book : bookDao.findAll()) {
            sum += cache.sizeOfBook(book.id);
        }
        return sum;
    }

    public void addChapters(long bookId, List<TocChapter> chapters) {
        chapterDao.insertIfAbsent(bookId, chapters);
        bookDao.updateChapterCount(bookId, chapterDao.countByBook(bookId));
    }

    public BookDao books() {
        return bookDao;
    }

    public ChapterDao chapterDao() {
        return chapterDao;
    }

    public ChapterCache cache() {
        return cache;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
