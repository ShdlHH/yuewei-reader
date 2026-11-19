package com.yuewei.reader.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** 在线阅读整本下载：顺序抓取、可取消，再次下载会跳过已完成章节（即重试失败章）。 */
final class DownloadManager {

    interface Listener {
        void progress(int done, int total, int failed);

        void finished(int done, int failed, boolean cancelled);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private volatile boolean cancelled;

    boolean running() {
        return running;
    }

    void cancel() {
        cancelled = true;
    }

    boolean start(Context context, Library library, Library.Book book, Listener listener) {
        if (running || book == null || book.deleted || !"ONLINE".equals(book.sourceType)) {
            return false;
        }
        SourceStore.Source source = new SourceStore(context).find(book.sourceUrl);
        if (source == null || book.chapterTitles.isEmpty()) {
            return false;
        }
        running = true;
        cancelled = false;
        int total = book.chapterTitles.size();
        long limitBytes = new AppSettings(context).cacheLimitMb() * 1024L * 1024L;
        new Thread(() -> {
            int done = 0;
            int failed = 0;
            boolean wasCancelled;
            try {
                for (int i = 0; i < total && !cancelled; i++) {
                    Library.Book latest = library.find(book.id);
                    if (latest == null || latest.deleted) {
                        break;
                    }
                    if (library.chapterFile(book.id, i).isFile()) {
                        done++;
                        postProgress(listener, done, total, failed);
                        continue;
                    }
                    String url = i < book.chapterUrls.size() ? book.chapterUrls.get(i) : "";
                    if (url == null || url.isEmpty()) {
                        failed++;
                        postProgress(listener, done, total, failed);
                        continue;
                    }
                    try {
                        String content = new OnlineService().content(source, url);
                        library.cacheChapter(book, i, content);
                        done++;
                    } catch (Exception e) {
                        failed++;
                    }
                    postProgress(listener, done, total, failed);
                    if (i % 10 == 0) {
                        CacheManager.pruneOnline(library, limitBytes, book.id);
                    }
                }
            } finally {
                wasCancelled = cancelled;
                final int doneCount = done;
                final int failedCount = failed;
                running = false;
                MAIN.post(() -> {
                    if (listener != null) {
                        listener.finished(doneCount, failedCount, wasCancelled);
                    }
                });
            }
        }, "yuewei-download").start();
        return true;
    }

    private void postProgress(Listener listener, int done, int total, int failed) {
        MAIN.post(() -> {
            if (listener != null) {
                listener.progress(done, total, failed);
            }
        });
    }
}
