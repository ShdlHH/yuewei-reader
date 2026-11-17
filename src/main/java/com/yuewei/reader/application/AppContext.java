package com.yuewei.reader.application;

import com.yuewei.reader.infrastructure.cache.ChapterCache;
import com.yuewei.reader.infrastructure.db.BookDao;
import com.yuewei.reader.infrastructure.db.BookSourceDao;
import com.yuewei.reader.infrastructure.db.ChapterDao;
import com.yuewei.reader.infrastructure.db.Db;
import com.yuewei.reader.infrastructure.db.DownloadDao;
import com.yuewei.reader.infrastructure.db.ProgressDao;
import com.yuewei.reader.infrastructure.db.SettingDao;
import com.yuewei.reader.infrastructure.network.HttpGateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 应用服务装配：数据库、缓存、网络与服务层单例。 */
public class AppContext {

    public final Path dataDir;
    public final Db db;
    public final HttpGateway http;
    public final SettingsService settings;
    public final ImportService importService;
    public final BookService bookService;
    public final SourceService sourceService;
    public final SearchService searchService;
    public final OnlineBookService onlineBookService;
    public final ValidationService validationService;
    public final DownloadService downloadService;
    public final SyncService syncService;

    public AppContext(Path dataDir) {
        this.dataDir = dataDir;
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(dataDir.resolve("cache"));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据目录：" + dataDir, e);
        }

        this.db = new Db(dataDir.resolve("reader.db"));
        BookDao bookDao = new BookDao(db);
        ChapterDao chapterDao = new ChapterDao(db);
        ProgressDao progressDao = new ProgressDao(db);
        BookSourceDao sourceDao = new BookSourceDao(db);
        DownloadDao downloadDao = new DownloadDao(db);
        SettingDao settingDao = new SettingDao(db);
        ChapterCache cache = new ChapterCache(dataDir.resolve("cache"));

        http = new HttpGateway();
        settings = new SettingsService(settingDao);
        settings.applyTo(http);

        importService = new ImportService(bookDao, chapterDao, cache);
        bookService = new BookService(bookDao, chapterDao, progressDao, cache);
        sourceService = new SourceService(sourceDao);
        searchService = new SearchService(http, settings);
        onlineBookService = new OnlineBookService(http, bookDao, chapterDao, sourceDao, cache, settings);
        validationService = new ValidationService(http, sourceDao);
        downloadService = new DownloadService(bookDao, chapterDao, downloadDao, sourceDao,
                onlineBookService, bookService, settings);
        downloadService.pauseUnfinishedOnStartup();
        syncService = new SyncService(settings, bookDao, chapterDao, progressDao, sourceDao, onlineBookService);
    }

    /** 默认数据目录：%USERPROFILE%/.yuewei-reader，可用 -Dyuewei.dataDir 覆盖。 */
    public static Path defaultDataDir() {
        String override = System.getProperty("yuewei.dataDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".yuewei-reader");
    }

    public void close() {
        downloadService.shutdown();
    }

    /** 退出前尽力上传一次进度与书架，最多等 3 秒，避免卡住关闭。 */
    public void pushSyncOnExit() {
        if (!syncService.configured()) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                syncService.pushAll();
            } catch (Exception ignored) {
                // 退出路径不打扰用户，下次启动仍会重新同步
            }
        }, "sync-exit");
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
