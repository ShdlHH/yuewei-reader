package com.yuewei.reader.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.BookSource;
import com.yuewei.reader.domain.TocChapter;
import com.yuewei.reader.infrastructure.txt.ChapterSplitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用内存版 WebDAV 验证：进度能推上去，远端更新能拉回来，旧进度不覆盖本地。 */
class SyncServiceTest {

    @TempDir
    Path temp;

    private HttpServer server;
    private final Map<String, byte[]> remote = new ConcurrentHashMap<>();
    private AppContext ctx;
    private Book book;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/dav/";

        Path file = temp.resolve("三体.txt");
        StringBuilder text = new StringBuilder();
        String[] names = {"初见", "相逢", "离别", "远行", "归来", "重逢"};
        for (int i = 0; i < names.length; i++) {
            text.append("第").append(i + 1).append("章 ").append(names[i]).append('\n');
            text.append("这是").append(names[i]).append("的正文内容，用来验证分章与阅读进度同步。".repeat(4)).append("\n\n");
        }
        Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));

        ctx = new AppContext(temp.resolve("data"));
        ImportPreview preview = ctx.importService.preview(file);
        book = ctx.importService.importBook(file, preview.charset, ChapterSplitter.Mode.AUTO,
                "", "三体", "刘慈欣", false);
        ctx.bookService.saveProgress(book.id, 2, 5000);
        ctx.settings.saveSync(true, baseUrl, "", "", "yuewei");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (ctx != null) {
            ctx.close();
        }
    }

    @Test
    void pushesLegadoCompatibleProgressAndPullsNewerRemote() throws Exception {
        SyncService.Result pushed = ctx.syncService.pushAll();
        assertEquals(1, pushed.pushed());

        byte[] progressBytes = remote.get("/dav/yuewei/bookProgress/三体_刘慈欣.json");
        assertNotNull(progressBytes, "应写入 Legado 兼容的进度文件");
        JsonNode node = new ObjectMapper().readTree(progressBytes);
        assertEquals("三体", node.path("name").asText());
        assertEquals("刘慈欣", node.path("author").asText());
        assertEquals(2, node.path("durChapterIndex").asInt());
        assertTrue(node.path("durChapterPos").asInt() > 0, "章内位置应按字数换算");
        assertTrue(remote.containsKey("/dav/yuewei/shelf.json"));
        assertFalse(remote.containsKey("/dav/yuewei/settings.json"),
                "排版设置只在设备本地生效，不参与同步");

        long newer = System.currentTimeMillis() + 5000;
        remote.put("/dav/yuewei/bookProgress/三体_刘慈欣.json",
                ("{\"name\":\"三体\",\"author\":\"刘慈欣\",\"durChapterIndex\":4,"
                        + "\"durChapterPos\":60,\"durChapterTitle\":\"第5章 归来\","
                        + "\"durChapterTime\":" + newer + "}").getBytes(StandardCharsets.UTF_8));

        SyncService.Result pulled = ctx.syncService.pull();
        assertEquals(1, pulled.applied());
        assertEquals(4, ctx.bookService.progress(book.id).chapterIndex, "应合并远端更新的章节");

        remote.put("/dav/yuewei/bookProgress/三体_刘慈欣.json",
                ("{\"name\":\"三体\",\"author\":\"刘慈欣\",\"durChapterIndex\":0,"
                        + "\"durChapterPos\":0,\"durChapterTime\":1}").getBytes(StandardCharsets.UTF_8));
        SyncService.Result stale = ctx.syncService.pull();
        assertEquals(0, stale.applied(), "远端旧进度不应覆盖本地");
        assertEquals(4, ctx.bookService.progress(book.id).chapterIndex);
    }

    @Test
    void remoteReAddedOnlineBookRevivesLocalTombstone() throws Exception {
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String html;
            if (path.startsWith("/book/")) {
                html = "<html><body><div class=\"name\">复活之书</div>"
                        + "<div class=\"author\">作者丁</div>"
                        + "<a class=\"toc\" href=\"/toc/1\">目录</a></body></html>";
            } else if (path.startsWith("/toc/")) {
                html = "<html><body><ul id=\"list\"><li><a href=\"/c/1\">第1章</a></li>"
                        + "<li><a href=\"/c/2\">第2章</a></li></ul></body></html>";
            } else {
                html = "<html><body>正文</body></html>";
            }
            respond(exchange, 200, html.getBytes(StandardCharsets.UTF_8));
        });
        site.start();
        try {
            String siteUrl = "http://127.0.0.1:" + site.getAddress().getPort();
            String json = """
                    {
                      "bookSourceName": "复活测试源",
                      "bookSourceUrl": "%s",
                      "enabled": true,
                      "searchUrl": "/search?q={{key}}",
                      "ruleSearch": {
                        "bookList": "class.book-list@tag.li",
                        "name": "class.name@text",
                        "author": "class.author@text",
                        "bookUrl": "class.name@tag.a@href"
                      },
                      "ruleBookInfo": {
                        "name": "class.name@text",
                        "author": "class.author@text",
                        "tocUrl": "class.toc@href"
                      },
                      "ruleToc": {
                        "chapterList": "id.list@tag.li",
                        "chapterName": "tag.a@text",
                        "chapterUrl": "tag.a@href"
                      }
                    }
                    """.formatted(siteUrl);
            ctx.sourceService.importJson(json, false);
            BookSource source = ctx.sourceService.findAll().get(0);

            BookDetail detail = new BookDetail();
            detail.title = "复活之书";
            detail.author = "作者丁";
            detail.bookUrl = siteUrl + "/book/1";
            detail.chapters = List.of(
                    new TocChapter(0, "第1章", siteUrl + "/c/1"),
                    new TocChapter(1, "第2章", siteUrl + "/c/2"));
            Book online = ctx.onlineBookService.addToShelf(source, detail);
            ctx.bookService.delete(online.id, true);
            assertNull(ctx.bookService.find(online.id), "删除后应从书架隐藏");

            String activatedAt = LocalDateTime.now().plusSeconds(30).toString();
            String shelf = "{\"version\":1,\"books\":[{\"deleted\":false,"
                    + "\"title\":\"复活之书\",\"author\":\"作者丁\",\"sourceType\":\"ONLINE\","
                    + "\"sourceUrl\":\"" + siteUrl + "\",\"bookUrl\":\"" + siteUrl + "/book/1\","
                    + "\"fileHash\":\"\",\"chapterCount\":2,"
                    + "\"addedAt\":\"" + activatedAt + "\"}]}";
            remote.put("/dav/yuewei/shelf.json", shelf.getBytes(StandardCharsets.UTF_8));

            ctx.syncService.pull();

            Book revived = ctx.bookService.find(online.id);
            assertNotNull(revived, "远端在删除后重新入架，本地应复活");
            assertEquals(2, ctx.bookService.chapters(online.id).size(), "复活时应重新抓目录");
        } finally {
            site.stop(0);
        }
    }

    @Test
    void mergesRemoteShelfOnlyWhenNewer() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        long progressTime = System.currentTimeMillis() + 8000;
        String newerRead = LocalDateTime.now().plusSeconds(5).toString();

        ObjectNode shelf = mapper.createObjectNode();
        shelf.put("version", 1);
        shelf.put("updatedAt", newerRead);
        var books = shelf.putArray("books");
        ObjectNode matched = books.addObject();
        matched.put("title", "三体");
        matched.put("author", "刘慈欣");
        matched.put("sourceType", "LOCAL");
        matched.put("fileHash", ctx.bookService.find(book.id).fileHash);
        matched.put("chapterCount", 12);
        matched.put("lastReadAt", newerRead);
        matched.put("chapterIndex", 4);
        matched.put("percent", 2500);
        matched.put("progressAt", iso(progressTime));
        ObjectNode ghost = books.addObject();
        ghost.put("title", "手机上新增的本地书");
        ghost.put("sourceType", "LOCAL");
        ghost.put("fileHash", "deadbeef");
        remote.put("/dav/yuewei/shelf.json", mapper.writeValueAsBytes(shelf));

        long settingsTime = System.currentTimeMillis() + 9000;
        ObjectNode settingsNode = mapper.createObjectNode();
        settingsNode.put("updatedAt", iso(settingsTime));
        ObjectNode values = settingsNode.putObject("values");
        values.put(SettingsService.FONT_SIZE, "26");
        values.put(SettingsService.LINE_SPACING, "2.2");
        values.put(SettingsService.THEME, "night");
        values.put(SettingsService.PUNCT_ENABLED, "false");
        values.put(SettingsService.PUNCT_DIALOGUE_COLOR, "#123456");
        remote.put("/dav/yuewei/settings.json", mapper.writeValueAsBytes(settingsNode));

        SyncService.Result result = ctx.syncService.pull();
        assertTrue(result.message().contains("合并 1 本书架"), result.message());
        assertFalse(result.message().contains("应用远端设置"), result.message());

        assertEquals(4, ctx.bookService.progress(book.id).chapterIndex);
        assertEquals(2500, ctx.bookService.progress(book.id).charOffset);
        assertEquals(newerRead, ctx.bookService.find(book.id).lastReadAt);
        assertEquals(12, ctx.bookService.find(book.id).chapterCount);
        assertEquals(18, ctx.settings.fontSize(), "桌面默认字号");
        assertEquals("paper", ctx.settings.theme(), "远端排版设置不应用到本机");
        assertTrue(ctx.settings.punctEnabled());
    }

    @Test
    void syncsDeletionsThroughShelfTombstone() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String hash = ctx.bookService.find(book.id).fileHash;

        ctx.bookService.delete(book.id, true);
        assertNull(ctx.bookService.find(book.id), "本地删除后书架应不可见");
        ctx.syncService.pushAll();
        JsonNode shelf = mapper.readTree(remote.get("/dav/yuewei/shelf.json"));
        boolean tombstone = false;
        for (JsonNode item : shelf.path("books")) {
            if (item.path("fileHash").asText().equals(hash) && item.path("deleted").asBoolean()) {
                tombstone = true;
            }
        }
        assertTrue(tombstone, "书架快照应包含删除墓碑");

        Path second = temp.resolve("第二本.txt");
        Files.write(second, "第一章 开始\n内容内容内容。\n第二章 继续\n更多更多内容。\n"
                .getBytes(StandardCharsets.UTF_8));
        ImportPreview preview = ctx.importService.preview(second);
        Book other = ctx.importService.importBook(second, preview.charset, ChapterSplitter.Mode.AUTO,
                "", "第二本", "作者", false);

        ObjectNode remoteShelf = mapper.createObjectNode();
        remoteShelf.put("version", 1);
        remoteShelf.put("updatedAt", LocalDateTime.now().toString());
        ObjectNode entry = remoteShelf.putArray("books").addObject();
        entry.put("deleted", true);
        entry.put("title", other.title);
        entry.put("author", other.author);
        entry.put("sourceType", "LOCAL");
        entry.put("fileHash", other.fileHash);
        entry.put("chapterCount", other.chapterCount);
        remote.put("/dav/yuewei/shelf.json", mapper.writeValueAsBytes(remoteShelf));

        ctx.syncService.pull();
        assertNull(ctx.bookService.find(other.id), "远端墓碑应让本地书架隐藏这本书");
        boolean marked = ctx.bookService.books().findAllWithDeleted().stream()
                .anyMatch(item -> item.id == other.id && item.deleted);
        assertTrue(marked, "本地应保留墓碑记录");
    }

    @Test
    void pushKeepsRemoteShelfEntriesThatCannotBeImportedLocally() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode shelf = mapper.createObjectNode();
        shelf.put("version", 1);
        ObjectNode mobileLocal = shelf.putArray("books").addObject();
        mobileLocal.put("deleted", false);
        mobileLocal.put("title", "手机本地书");
        mobileLocal.put("author", "作者甲");
        mobileLocal.put("sourceType", "LOCAL");
        mobileLocal.put("fileHash", "hash-mobile-only");
        remote.put("/dav/yuewei/shelf.json", mapper.writeValueAsBytes(shelf));

        ctx.syncService.pushAll();

        JsonNode pushed = mapper.readTree(remote.get("/dav/yuewei/shelf.json"));
        boolean kept = false;
        for (JsonNode item : pushed.path("books")) {
            if ("hash-mobile-only".equals(item.path("fileHash").asText())) {
                kept = true;
            }
        }
        assertTrue(kept, "无法在本机导入的远端条目不应被整份覆盖删除");
    }

    @Test
    void pushKeepsRemoteTombstoneForUnknownBook() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode shelf = mapper.createObjectNode();
        shelf.put("version", 1);
        ObjectNode tombstone = shelf.putArray("books").addObject();
        tombstone.put("deleted", true);
        tombstone.put("title", "别处删除的书");
        tombstone.put("author", "作者乙");
        tombstone.put("sourceType", "LOCAL");
        tombstone.put("fileHash", "hash-ghost");
        tombstone.put("lastReadAtMs", System.currentTimeMillis());
        remote.put("/dav/yuewei/shelf.json", mapper.writeValueAsBytes(shelf));

        ctx.syncService.pushAll();

        JsonNode pushed = mapper.readTree(remote.get("/dav/yuewei/shelf.json"));
        boolean kept = false;
        for (JsonNode item : pushed.path("books")) {
            if ("hash-ghost".equals(item.path("fileHash").asText()) && item.path("deleted").asBoolean()) {
                kept = true;
            }
        }
        assertTrue(kept, "其它设备创建的墓碑不应被本机全量推送抹掉");
    }

    @Test
    void pullDoesNotZeroRemotePositionWhenChapterNotCached() throws Exception {
        String json = """
                {
                  "bookSourceName": "未缓存测试源",
                  "bookSourceUrl": "https://offline.test",
                  "enabled": true
                }
                """;
        ctx.sourceService.importJson(json, false);
        BookSource source = ctx.sourceService.findAll().get(0);
        BookDetail detail = new BookDetail();
        detail.title = "未缓存之书";
        detail.author = "作者戊";
        detail.bookUrl = "https://offline.test/book/1";
        detail.chapters = List.of(new TocChapter(0, "第1章", "https://offline.test/c/1"));
        ctx.onlineBookService.addToShelf(source, detail);

        long remoteTime = System.currentTimeMillis() + 5000;
        remote.put("/dav/yuewei/bookProgress/未缓存之书_作者戊.json",
                ("{\"name\":\"未缓存之书\",\"author\":\"作者戊\",\"durChapterIndex\":0,"
                        + "\"durChapterPos\":500,\"durChapterTime\":" + remoteTime + "}")
                        .getBytes(StandardCharsets.UTF_8));

        ctx.syncService.pull();
        ctx.syncService.pushAll();

        JsonNode node = new ObjectMapper().readTree(remote.get("/dav/yuewei/bookProgress/未缓存之书_作者戊.json"));
        assertEquals(500, node.path("durChapterPos").asInt(), "章内字数未知时不应把远端位点清零");
    }

    private static String iso(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).toString();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
        String method = exchange.getRequestMethod();
        if ("MKCOL".equals(method)) {
            respond(exchange, 201, new byte[0]);
            return;
        }
        if ("PUT".equals(method)) {
            remote.put(path, exchange.getRequestBody().readAllBytes());
            respond(exchange, 201, new byte[0]);
            return;
        }
        if ("GET".equals(method)) {
            byte[] body = remote.get(path);
            if (body == null) {
                respond(exchange, 404, new byte[0]);
            } else {
                exchange.getResponseHeaders().add("Last-Modified", "Tue, 17 Nov 2026 02:00:00 GMT");
                respond(exchange, 200, body);
            }
            return;
        }
        if ("PROPFIND".equals(method)) {
            respond(exchange, 207, listXml(path).getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(exchange, 405, new byte[0]);
    }

    private String listXml(String directory) {
        String prefix = directory.endsWith("/") ? directory : directory + "/";
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:">
                """);
        xml.append("<d:response><d:href>").append(prefix).append("</d:href>")
                .append("<d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>")
                .append("<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>");
        for (Map.Entry<String, byte[]> entry : remote.entrySet()) {
            if (!entry.getKey().startsWith(prefix) || entry.getKey().equals(prefix)) {
                continue;
            }
            xml.append("<d:response><d:href>").append(encodePath(entry.getKey())).append("</d:href>")
                    .append("<d:propstat><d:prop><d:getlastmodified>Tue, 17 Nov 2026 02:00:00 GMT</d:getlastmodified>")
                    .append("<d:getcontentlength>").append(entry.getValue().length).append("</d:getcontentlength>")
                    .append("<d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>");
        }
        xml.append("</d:multistatus>");
        return xml.toString();
    }

    private static String encodePath(String path) {
        String[] parts = path.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
