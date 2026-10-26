package com.yuewei.reader.application;

import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.BookSource;
import com.yuewei.reader.domain.Chapter;
import com.yuewei.reader.domain.ContentState;
import com.yuewei.reader.domain.DownloadRangeType;
import com.yuewei.reader.domain.DownloadState;
import com.yuewei.reader.domain.DownloadTask;
import com.yuewei.reader.infrastructure.db.BookDao;
import com.yuewei.reader.infrastructure.db.BookSourceDao;
import com.yuewei.reader.infrastructure.db.ChapterDao;
import com.yuewei.reader.infrastructure.db.DownloadDao;
import com.yuewei.reader.infrastructure.network.HttpGateway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量下载：任务排队、并发抓正文、暂停/继续/取消、失败重试与离线标记。
 * 只处理在线书；本地 TXT 导入即已缓存，不产生下载任务。
 */
public class DownloadService {

    /** 任务状态变化通知，回调可能来自后台线程，UI 需自行切回 FX 线程。 */
    public interface Listener {
        void onChange(DownloadTask task, String note);
    }

    /** 工作线程上限；实际并发由设置里的大小决定（每批派发的章节数）。 */
    private static final int MAX_WORKERS = 8;

    private final BookDao bookDao;
    private final ChapterDao chapterDao;
    private final DownloadDao downloadDao;
    private final BookSourceDao sourceDao;
    private final OnlineBookService online;
    private final BookService bookService;
    private final SettingsService settings;

    private final ExecutorService workers;
    private final ExecutorService coordinator;
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger threadSeq = new AtomicInteger();

    public DownloadService(BookDao bookDao, ChapterDao chapterDao, DownloadDao downloadDao,
                           BookSourceDao sourceDao, OnlineBookService online,
                           BookService bookService, SettingsService settings) {
        this.bookDao = bookDao;
        this.chapterDao = chapterDao;
        this.downloadDao = downloadDao;
        this.sourceDao = sourceDao;
        this.online = online;
        this.bookService = bookService;
        this.settings = settings;
        this.workers = Executors.newFixedThreadPool(MAX_WORKERS, r -> {
            Thread t = new Thread(r, "download-worker-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.coordinator = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "download-coordinator");
            t.setDaemon(true);
            return t;
        });
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** 启动时把上次未完成的任务统一置为暂停，避免应用重启后卡片一直显示“下载中”。 */
    public void pauseUnfinishedOnStartup() {
        downloadDao.pauseUnfinishedOnStartup();
    }

    public List<DownloadTask> tasks() {
        return downloadDao.findAll();
    }

    public DownloadTask start(Book book, DownloadRangeType range, Integer from, Integer to) {
        if (book == null) {
            throw new AppException("请选择一本书");
        }
        if (!book.isOnline()) {
            throw new AppException("本地书无需下载，章节已全部缓存");
        }
        BookSource source = book.sourceId == null ? null : sourceDao.findById(book.sourceId);
        if (source == null) {
            throw new AppException("所属书源已被删除，无法下载");
        }
        List<Chapter> chapters = chapterDao.findByBook(book.id);
        if (chapters.isEmpty()) {
            throw new AppException("这本书还没有目录，请先更新目录");
        }
        for (DownloadTask existing : downloadDao.findAll()) {
            if (existing.bookId == book.id && (existing.state == DownloadState.QUEUED
                    || existing.state == DownloadState.RUNNING || existing.state == DownloadState.PAUSED)) {
                throw new AppException("《" + book.title + "》已有未完成的下载任务，请先在下载页继续或取消");
            }
        }
        List<Integer> targets = targets(chapters, range, from, to);
        if (targets.isEmpty()) {
            throw new AppException("所选范围没有需要下载的章节");
        }

        DownloadTask task = new DownloadTask();
        task.bookId = book.id;
        task.bookTitle = book.title;
        task.rangeType = range;
        task.fromIndex = from;
        task.toIndex = to;
        task.state = DownloadState.QUEUED;
        downloadDao.insert(task);

        Job job = new Job(task, book, source, targets);
        jobs.put(task.id, job);
        coordinator.submit(() -> run(job));
        notifyListeners(job, "已加入下载队列，共 " + targets.size() + " 章");
        return task;
    }

    /** 暂停：当前批次已发出的请求会跑完，之后不再派发新章节。 */
    public void pause(long taskId) {
        Job job = jobs.get(taskId);
        if (job == null || job.paused) {
            return;
        }
        job.paused = true;
        synchronized (job.lock) {
            if (!job.running) {
                job.task.state = DownloadState.PAUSED;
                persist(job);
                notifyListeners(job, "已暂停");
            }
        }
    }

    public void resume(long taskId) {
        Job job = jobs.get(taskId);
        if (job == null) {
            job = rebuild(taskId);
            if (job == null) {
                throw new AppException("任务不存在");
            }
            jobs.put(taskId, job);
        }
        if (!job.paused && job.task.state != DownloadState.QUEUED) {
            return;
        }
        List<Integer> remaining = targets(chapterDao.findByBook(job.book.id),
                job.task.rangeType, job.task.fromIndex, job.task.toIndex);
        job.pending.clear();
        job.pending.addAll(remaining);
        job.paused = false;
        job.cancelled = false;
        job.networkFailure = false;
        synchronized (job.lock) {
            job.task.state = DownloadState.QUEUED;
            job.task.failCount = 0;
            job.task.failedIndexes.clear();
            persist(job);
        }
        Job target = job;
        coordinator.submit(() -> run(target));
        notifyListeners(job, "继续下载");
    }

    /** 书源被删除时，暂停其名下所有未完成任务。 */
    public void pauseBySource(long sourceId) {
        for (Job job : jobs.values()) {
            if (job.source.id == sourceId) {
                pause(job.task.id);
            }
        }
    }

    public void cancel(long taskId) {
        Job job = jobs.get(taskId);
        if (job == null) {
            DownloadTask task = findTask(taskId);
            if (task == null) {
                return;
            }
            task.state = DownloadState.CANCELLED;
            downloadDao.update(task);
            return;
        }
        job.cancelled = true;
        job.paused = false;
        synchronized (job.lock) {
            if (!job.running) {
                finish(job, DownloadState.CANCELLED, "已取消");
            }
        }
    }

    /** 关闭时停止线程池，未完成任务保持数据库中的当前状态（下次启动会置为暂停）。 */
    public void shutdown() {
        coordinator.shutdownNow();
        workers.shutdownNow();
    }

    // ------------------------------------------------------------------ 任务执行

    private void run(Job job) {
        synchronized (job.lock) {
            if (job.cancelled) {
                finish(job, DownloadState.CANCELLED, "已取消");
                return;
            }
            if (job.paused) {
                job.task.state = DownloadState.PAUSED;
                persist(job);
                notifyListeners(job, "已暂停");
                return;
            }
            job.running = true;
            job.networkFailure = false;
            job.task.state = DownloadState.RUNNING;
            persist(job);
        }
        notifyListeners(job, job.progressText());

        int batchSize = Math.max(1, settings.downloadConcurrency());
        while (true) {
            List<Integer> slice = new ArrayList<>();
            synchronized (job.lock) {
                if (job.cancelled) {
                    finish(job, DownloadState.CANCELLED, "已取消");
                    return;
                }
                if (job.paused) {
                    job.task.state = DownloadState.PAUSED;
                    persist(job);
                    notifyListeners(job, "已暂停");
                    return;
                }
                while (slice.size() < batchSize && !job.pending.isEmpty()) {
                    slice.add(job.pending.poll());
                }
                if (slice.isEmpty()) {
                    job.running = false;
                    boolean offline = bookService.refreshOffline(job.book.id);
                    DownloadState state = job.task.failCount > 0
                            ? DownloadState.PARTIAL_FAILED
                            : DownloadState.COMPLETED;
                    String note = state == DownloadState.COMPLETED
                            ? "已完成 · 共 " + job.task.successCount + " 章"
                            : "部分失败 · 成功 " + job.task.successCount + " 章，失败 " + job.task.failCount + " 章";
                    if (offline) {
                        note += " · 已标记离线可读";
                    }
                    finish(job, state, note);
                    return;
                }
            }

            List<Future<?>> futures = new ArrayList<>();
            for (Integer idx : slice) {
                futures.add(workers.submit(() -> downloadChapter(job, idx)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception ignored) {
                    // 单章异常已在 downloadChapter 内记录
                }
            }
            synchronized (job.lock) {
                persist(job);
            }
            if (job.networkFailure) {
                job.paused = true;
                synchronized (job.lock) {
                    job.running = false;
                    job.task.state = DownloadState.PAUSED;
                    persist(job);
                }
                notifyListeners(job, "网络异常，已暂停；恢复网络后可点「继续」");
                return;
            }
            notifyListeners(job, job.progressText());
        }
    }

    private void downloadChapter(Job job, int idx) {
        Chapter chapter = chapterDao.findByIndex(job.book.id, idx);
        if (chapter == null) {
            return;
        }
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                online.fetchContent(job.book, job.source, chapter);
                synchronized (job.lock) {
                    if (chapter.state != ContentState.CACHED) {
                        job.task.successCount++;
                    }
                    chapter.state = ContentState.CACHED;
                }
                return;
            } catch (Exception e) {
                last = e;
                if (attempt == 0) {
                    sleep(600);
                }
            }
        }
        synchronized (job.lock) {
            job.task.failCount++;
            job.task.failedIndexes.add(idx);
        }
        if (isNetworkDown(last)) {
            job.networkFailure = true;
        }
        String message = last == null ? "未知错误" : String.valueOf(last.getMessage());
        notifyListeners(job, "第 " + (idx + 1) + " 章失败：" + message);
    }

    /** 判断异常链是否属于网络不可用（HTTP 状态码异常说明服务器可达，不算断网）。 */
    private static boolean isNetworkDown(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpGateway.HttpStatusException) {
                return false;
            }
            if (cause instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }

    private void finish(Job job, DownloadState state, String note) {
        job.running = false;
        job.task.state = state;
        persist(job);
        jobs.remove(job.task.id);
        notifyListeners(job, note);
    }

    private void persist(Job job) {
        try {
            downloadDao.update(job.task);
        } catch (RuntimeException ignored) {
            // 落库失败不影响下载本身，内存状态仍然可用
        }
    }

    private Job rebuild(long taskId) {
        DownloadTask task = findTask(taskId);
        if (task == null || task.state == DownloadState.COMPLETED || task.state == DownloadState.CANCELLED) {
            return null;
        }
        Book book = bookDao.findById(task.bookId);
        if (book == null) {
            return null;
        }
        BookSource source = book.sourceId == null ? null : sourceDao.findById(book.sourceId);
        if (source == null) {
            return null;
        }
        List<Chapter> chapters = chapterDao.findByBook(book.id);
        List<Integer> targets = targets(chapters, task.rangeType, task.fromIndex, task.toIndex);
        if (targets.isEmpty()) {
            return null;
        }
        Job job = new Job(task, book, source, targets);
        job.paused = true;
        return job;
    }

    private DownloadTask findTask(long taskId) {
        for (DownloadTask t : downloadDao.findAll()) {
            if (t.id == taskId) {
                return t;
            }
        }
        return null;
    }

    /** 计算本次需要抓取的章节序号：范围内且尚未缓存。 */
    private List<Integer> targets(List<Chapter> chapters, DownloadRangeType range, Integer from, Integer to) {
        DownloadRangeType type = range == null ? DownloadRangeType.ALL : range;
        int lo = from == null ? 0 : Math.max(0, from);
        int hi = to == null ? Integer.MAX_VALUE : to;
        List<Integer> list = new ArrayList<>();
        for (Chapter ch : chapters) {
            if (ch.state == ContentState.CACHED) {
                continue;
            }
            boolean hit = switch (type) {
                case ALL -> true;
                case UNCACHED -> true;
                case RANGE -> ch.idx >= lo && ch.idx <= hi;
            };
            if (hit) {
                list.add(ch.idx);
            }
        }
        return list;
    }

    private void notifyListeners(Job job, String note) {
        for (Listener listener : listeners) {
            try {
                listener.onChange(job.task, note);
            } catch (RuntimeException ignored) {
                // 监听器异常不影响下载
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Job {
        final DownloadTask task;
        final Book book;
        final BookSource source;
        final Deque<Integer> pending;
        final Object lock = new Object();
        volatile boolean paused;
        volatile boolean cancelled;
        volatile boolean networkFailure;
        boolean running;

        Job(DownloadTask task, Book book, BookSource source, List<Integer> targets) {
            this.task = task;
            this.book = book;
            this.source = source;
            this.pending = new ArrayDeque<>(targets);
        }

        String progressText() {
            int total = task.successCount + task.failCount + pending.size();
            return "下载中 " + (task.successCount + task.failCount) + "/" + Math.max(total, 1);
        }
    }
}
