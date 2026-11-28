package com.yuewei.reader.application;

import com.yuewei.reader.domain.Book;
import com.yuewei.reader.infrastructure.db.BookDao;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 追更检查：按间隔静默检查书架中在线书的目录增量，把新增章节数写回书籍标记。
 *
 * <p>只请求目录页、串行执行、遵守请求间隔，单轮有上限；失败不影响原标记。</p>
 */
public class UpdateCheckService {

    /** 单轮最多检查的书目数，其余下一轮继续。 */
    public static final int DEFAULT_MAX_BOOKS = 20;

    private final SettingsService settings;
    private final BookDao bookDao;
    private final OnlineBookService onlineBookService;

    public UpdateCheckService(SettingsService settings, BookDao bookDao,
                              OnlineBookService onlineBookService) {
        this.settings = settings;
        this.bookDao = bookDao;
        this.onlineBookService = onlineBookService;
    }

    /** 一轮检查结果：检查数 / 有更新数 / 失败数 / 有更新的书名。 */
    public record Result(int checked, int updated, int failed, List<String> updatedTitles) {
        public String summary() {
            StringBuilder builder = new StringBuilder("更新检查：检查 ").append(checked).append(" 本");
            if (updated > 0) {
                builder.append("，").append(updated).append(" 本有更新");
            }
            if (failed > 0) {
                builder.append("，").append(failed).append(" 本失败");
            }
            return builder.toString();
        }
    }

    /** 是否到了该检查的时间（开关关闭或间隔为 0 时不自动检查）。 */
    public boolean due() {
        if (!settings.updateCheckEnabled()) {
            return false;
        }
        int interval = settings.updateCheckIntervalMin();
        if (interval <= 0) {
            return false;
        }
        String last = settings.lastUpdateCheckAt();
        if (last == null || last.isBlank()) {
            return true;
        }
        try {
            LocalDateTime at = LocalDateTime.parse(last);
            return Duration.between(at, LocalDateTime.now()).toMinutes() >= interval;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** 执行一轮检查：按最近阅读排序，最多 maxBooks 本，串行。 */
    public Result runOnce(int maxBooks) {
        int limit = maxBooks <= 0 ? DEFAULT_MAX_BOOKS : maxBooks;
        List<Book> candidates = new ArrayList<>();
        for (Book book : bookDao.findAll()) {
            if (book.isOnline() && !book.sourceMissing && book.sourceId != null) {
                candidates.add(book);
            }
        }
        int checked = 0;
        int updated = 0;
        int failed = 0;
        List<String> titles = new ArrayList<>();
        int interval = settings.requestInterval();
        for (Book book : candidates) {
            if (checked >= limit) {
                break;
            }
            checked++;
            try {
                int added = onlineBookService.refreshToc(book);
                bookDao.updateNewChapters(book.id, Math.max(0, added));
                if (added > 0) {
                    updated++;
                    titles.add(book.title);
                }
            } catch (RuntimeException e) {
                // 单本失败不改动原标记，下一轮再试
                failed++;
            }
            if (interval > 0 && checked < limit) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        settings.markUpdateCheckAt();
        return new Result(checked, updated, failed, titles);
    }
}
