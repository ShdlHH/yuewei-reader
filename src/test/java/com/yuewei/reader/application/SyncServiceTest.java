package com.yuewei.reader.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuewei.reader.domain.Book;
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
        assertTrue(remote.containsKey("/dav/yuewei/settings.json"));
        JsonNode settings = new ObjectMapper().readTree(remote.get("/dav/yuewei/settings.json"));
        assertEquals("true", settings.path("values").path("reader.punct.enabled").asText());
        assertTrue(settings.path("values").path("reader.fontFamily").asText().length() > 0,
                "设置快照应写入生效值而不是空串");

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
    void mergesRemoteShelfAndSettingsOnlyWhenNewer() throws Exception {
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
        assertTrue(result.message().contains("应用远端设置"), result.message());

        assertEquals(4, ctx.bookService.progress(book.id).chapterIndex);
        assertEquals(2500, ctx.bookService.progress(book.id).charOffset);
        assertEquals(newerRead, ctx.bookService.find(book.id).lastReadAt);
        assertEquals(12, ctx.bookService.find(book.id).chapterCount);
        assertEquals(26, ctx.settings.fontSize());
        assertEquals("night", ctx.settings.theme());
        assertFalse(ctx.settings.punctEnabled());

        ctx.settings.put(SettingsService.FONT_SIZE, "20");
        ObjectNode stale = mapper.createObjectNode();
        stale.put("updatedAt", iso(System.currentTimeMillis() - 10_000));
        stale.putObject("values").put(SettingsService.FONT_SIZE, "30");
        remote.put("/dav/yuewei/settings.json", mapper.writeValueAsBytes(stale));
        ctx.syncService.pull();
        assertEquals(20, ctx.settings.fontSize(), "本地刚改过的设置不应被旧远端覆盖");
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
