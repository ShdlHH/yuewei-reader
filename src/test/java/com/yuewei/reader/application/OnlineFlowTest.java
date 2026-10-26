package com.yuewei.reader.application;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.BookSource;
import com.yuewei.reader.domain.Chapter;
import com.yuewei.reader.domain.ContentState;
import com.yuewei.reader.domain.DownloadRangeType;
import com.yuewei.reader.domain.DownloadState;
import com.yuewei.reader.domain.DownloadTask;
import com.yuewei.reader.domain.SearchResult;
import com.yuewei.reader.infrastructure.db.DownloadDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** 用本地 HTTP 服务跑通：搜索 → 详情 → 目录 → 正文 → 下载离线。 */
class OnlineFlowTest {

    private static final String SEARCH_PAGE = """
            <html><body>
            <div class="book-list"><ul>
              <li>
                <h3 class="name"><a href="/book/9">测试之书</a></h3>
                <span class="author">作者甲</span>
                <span class="last">第2章 结束</span>
              </li>
            </ul></div>
            </body></html>
            """;

    private static final String DETAIL_PAGE = """
            <html><body>
            <h1 class="name">测试之书</h1>
            <p class="author">作者甲</p>
            <div class="intro">这是一本测试书。</div>
            <a class="toc" href="/book/9/toc">目录</a>
            </body></html>
            """;

    private static final String TOC_PAGE = """
            <html><body>
            <ul id="list">
              <li><a href="/book/9/c1.html">第1章 开始</a></li>
              <li><a href="/book/9/c2.html">第2章 结束</a></li>
            </ul>
            </body></html>
            """;

    private static final String CONTENT_PAGE = """
            <html><body>
            <div id="content">%s</div>
            </body></html>
            """;

    @TempDir
    Path temp;

    private HttpServer server;
    private AppContext ctx;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> respond(exchange, SEARCH_PAGE));
        server.createContext("/book/9/toc", exchange -> respond(exchange, TOC_PAGE));
        server.createContext("/book/9/c1.html", exchange -> respond(exchange,
                CONTENT_PAGE.formatted("第一段内容。<br>第二段内容。")));
        server.createContext("/book/9/c2.html", exchange -> respond(exchange,
                CONTENT_PAGE.formatted("第三章的开头。")));
        server.createContext("/book/9", exchange -> respond(exchange, DETAIL_PAGE));
        server.start();

        ctx = new AppContext(temp.resolve("data"));
        ctx.settings.saveNetwork(10, 4, 2, 0, ctx.settings.userAgent());
        ctx.settings.applyTo(ctx.http);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void completesSearchReadAndDownloadFlow() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String json = """
                {
                  "bookSourceName": "本地测试源",
                  "bookSourceUrl": "%s",
                  "bookSourceType": 0,
                  "enabled": true,
                  "searchUrl": "/search?q={{key}}",
                  "ruleSearch": {
                    "bookList": "class.book-list@tag.li",
                    "name": "class.name@text",
                    "author": "class.author@text",
                    "bookUrl": "class.name@tag.a@href",
                    "lastChapter": "class.last@text"
                  },
                  "ruleBookInfo": {
                    "name": "class.name@text",
                    "author": "class.author@text",
                    "intro": "class.intro@text",
                    "tocUrl": "class.toc@href"
                  },
                  "ruleToc": {
                    "chapterList": "id.list@tag.li",
                    "chapterName": "tag.a@text",
                    "chapterUrl": "tag.a@href"
                  },
                  "ruleContent": {
                    "content": "id.content@html"
                  }
                }
                """.formatted(base);

        ImportReport report = ctx.sourceService.importJson(json, false);
        assertEquals(1, report.added);
        assertEquals(0, report.failed);
        BookSource source = ctx.sourceService.findAll().get(0);
        assertEquals("本地测试源", source.name);

        ValidationReport validation = ctx.validationService.validate(source, "测试之书");
        assertTrue(validation.ok, validation.firstFailure());
        assertEquals(4, validation.stages.size());

        SearchOutcome outcome = ctx.searchService.search("测试之书", List.of(source), 1, progress -> {
        });
        assertEquals(1, outcome.results.size());
        assertEquals("测试之书", outcome.results.get(0).title);
        assertEquals(base + "/book/9", outcome.results.get(0).bookUrl);

        SearchResult result = outcome.results.get(0);
        BookDetail detail = ctx.onlineBookService.fetchDetail(source, result);
        assertEquals("作者甲", detail.author);
        assertEquals(2, detail.chapters.size());

        Book book = ctx.onlineBookService.addToShelf(source, detail);
        assertNotNull(book.id);
        assertEquals(2, book.chapterCount);
        assertFalse(book.offline);

        List<Chapter> chapters = ctx.bookService.chapters(book.id);
        String first = ctx.onlineBookService.loadContent(book, chapters.get(0));
        assertTrue(first.contains("第一段内容"));
        assertTrue(first.contains("第二段内容"));

        DownloadTask task = ctx.downloadService.start(book, DownloadRangeType.ALL, null, null);
        awaitState(task.id, DownloadState.COMPLETED);

        List<Chapter> after = ctx.bookService.chapters(book.id);
        assertTrue(after.stream().allMatch(chapter -> chapter.state == ContentState.CACHED),
                "全部章节应已缓存");
        assertTrue(ctx.bookService.find(book.id).offline, "全部缓存后应标记离线可读");
        assertTrue(ctx.bookService.cacheSize(book.id) > 0);
        assertEquals("第1章 开始", after.get(0).title);

        // 同一本书已有未完成任务时，不允许再建新任务
        DownloadTask paused = new DownloadTask();
        paused.bookId = book.id;
        paused.bookTitle = book.title;
        paused.rangeType = DownloadRangeType.ALL;
        paused.state = DownloadState.PAUSED;
        new DownloadDao(ctx.db).insert(paused);
        AppException conflict = assertThrows(AppException.class,
                () -> ctx.downloadService.start(book, DownloadRangeType.ALL, null, null));
        assertTrue(conflict.getMessage().contains("已有未完成的下载任务"));
    }

    private void awaitState(long taskId, DownloadState expected) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (DownloadTask task : ctx.downloadService.tasks()) {
                if (task.id != taskId) {
                    continue;
                }
                if (task.state == expected) {
                    return;
                }
                if (task.state == DownloadState.PARTIAL_FAILED || task.state == DownloadState.CANCELLED) {
                    fail("任务未按预期完成，实际状态：" + task.state);
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待下载任务被中断");
            }
        }
        fail("等待下载任务超时");
    }

    private void respond(HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
