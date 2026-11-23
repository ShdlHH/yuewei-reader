package com.yuewei.reader.mobile;

import com.yuewei.reader.domain.BookDetail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 在线书源全链路：用本地最小 HTTP 服务模拟站点，
 * 验证搜索、详情、目录、正文与正文分页拼接（复用桌面 RuleEngine）。
 */
@RunWith(RobolectricTestRunner.class)
public class OnlineServiceTest {

    private static final String RULES = "{"
            + "\"searchUrl\":\"/search?q={{key}}\","
            + "\"ruleSearch\":{"
            + "\"bookList\":\"class.item\","
            + "\"name\":\"class.name@text\","
            + "\"author\":\"class.author@text\","
            + "\"bookUrl\":\"class.name@tag.a@href\"},"
            + "\"ruleBookInfo\":{"
            + "\"name\":\"class.name@text\","
            + "\"author\":\"class.author@text\","
            + "\"tocUrl\":\"class.toc@href\"},"
            + "\"ruleToc\":{"
            + "\"chapterList\":\"id.list@tag.li\","
            + "\"chapterName\":\"tag.a@text\","
            + "\"chapterUrl\":\"tag.a@href\"},"
            + "\"ruleContent\":{"
            + "\"content\":\"id.content@html\","
            + "\"nextContentUrl\":\"class.next@href\"}"
            + "}";

    private ServerSocket server;
    private final Map<String, String> pages = new HashMap<>();
    private String base;
    private SourceStore.Source source;

    @Before
    public void setUp() throws Exception {
        pages.put("/search", "<html><body><div class=\"item\">"
                + "<h3 class=\"name\"><a href=\"/book/1\">测试书</a></h3>"
                + "<span class=\"author\">作者甲</span>"
                + "</div></body></html>");
        pages.put("/book/1", "<html><body><h1 class=\"name\">测试书</h1>"
                + "<p class=\"author\">作者甲</p>"
                + "<a class=\"toc\" href=\"/toc\">目录</a>"
                + "</body></html>");
        pages.put("/toc", "<html><body><ul id=\"list\">"
                + "<li><a href=\"/c1\">第一章 开始</a></li>"
                + "<li><a href=\"/c2\">第二章 继续</a></li>"
                + "</ul></body></html>");
        pages.put("/c1", "<html><body><div id=\"content\">第一段<br><br>第二段</div>"
                + "<a class=\"next\" href=\"/c1b\">下一页</a>"
                + "</body></html>");
        pages.put("/c1b", "<html><body><div id=\"content\">第三段</div></body></html>");
        pages.put("/c2", "<html><body><div id=\"content\">第二章正文</div></body></html>");

        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(this::serve, "fake-site");
        thread.setDaemon(true);
        thread.start();
        base = "http://127.0.0.1:" + server.getLocalPort();

        source = new SourceStore.Source();
        source.name = "本地测试源";
        source.url = base;
        source.ruleJson = RULES;
    }

    @After
    public void tearDown() throws IOException {
        server.close();
    }

    @Test
    public void searchesDetailsChaptersAndContent() throws Exception {
        OnlineService online = new OnlineService();
        List<OnlineService.Hit> hits = online.search(List.of(source), "测试");

        assertEquals(1, hits.size());
        assertEquals("测试书", hits.get(0).result.title);
        assertEquals("作者甲", hits.get(0).result.author);
        assertEquals(base + "/book/1", hits.get(0).result.bookUrl);
        assertEquals("本地测试源", hits.get(0).result.sourceName);

        BookDetail detail = online.detail(source, hits.get(0).result);
        assertEquals("测试书", detail.title);
        assertEquals(2, detail.chapters.size());
        assertEquals("第一章 开始", detail.chapters.get(0).title);
        assertEquals(base + "/c1", detail.chapters.get(0).url);

        String content = online.content(source, detail.chapters.get(0).url);
        assertTrue(content.contains("第一段"));
        assertTrue(content.contains("第二段"));
        assertTrue(content.contains("第三段"));
    }

    @Test
    public void failedSourceReportsError() {
        SourceStore.Source broken = new SourceStore.Source();
        broken.name = "坏书源";
        broken.url = base;
        broken.ruleJson = "{\"searchUrl\":\"/missing?q={{key}}\","
                + "\"ruleSearch\":{\"bookList\":\"class.item\"}}";

        try {
            new OnlineService().search(List.of(broken), "测试");
            throw new AssertionError("搜索应当失败");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("坏书源"));
        }
    }

    /** 最小 HTTP/1.1 服务：每次请求返回一个页面后关闭连接。 */
    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                handle(socket);
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = reader.readLine();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // 跳过请求头
        }
        String path = "/";
        if (requestLine != null) {
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2) {
                path = parts[1];
            }
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        String html = pages.get(path);
        String status = html == null ? "404 Not Found" : "200 OK";
        byte[] body = (html == null ? "not found" : html).getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        try (OutputStream out = socket.getOutputStream()) {
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        }
    }

    @Test
    public void reusesKeepAliveConnectionAcrossRequests() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        ServerSocket ka = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!ka.isClosed()) {
                try (Socket socket = ka.accept()) {
                    accepted.incrementAndGet();
                    handleKeepAlive(socket);
                } catch (IOException e) {
                    return;
                }
            }
        }, "fake-keepalive");
        t.setDaemon(true);
        t.start();
        String kaBase = "http://127.0.0.1:" + ka.getLocalPort();
        SourceStore.Source kaSource = new SourceStore.Source();
        kaSource.name = "ka源";
        kaSource.url = kaBase;
        kaSource.ruleJson = RULES;

        try {
            OnlineService online = new OnlineService();
            // content(/c1) 会跟随后续页 /c1b，再加 /c2，共 3 次请求
            online.content(kaSource, kaBase + "/c1");
            online.content(kaSource, kaBase + "/c2");
            assertEquals("多次请求应复用同一 keep-alive 连接", 1, accepted.get());
        } finally {
            ka.close();
        }
    }

    /** 支持 keep-alive 的最小服务：同一连接上顺序处理多个请求。 */
    private void handleKeepAlive(Socket socket) throws IOException {
        socket.setSoTimeout(500);
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        try {
            while (!socket.isClosed()) {
                String requestLine = reader.readLine();
                if (requestLine == null) {
                    return;
                }
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // 跳过请求头
                }
                String path = "/";
                String[] parts = requestLine.split(" ");
                if (parts.length >= 2) {
                    path = parts[1];
                }
                int query = path.indexOf('?');
                if (query >= 0) {
                    path = path.substring(0, query);
                }
                String html = pages.get(path);
                byte[] body = (html == null ? "not found" : html).getBytes(StandardCharsets.UTF_8);
                String status = html == null ? "404 Not Found" : "200 OK";
                String head = "HTTP/1.1 " + status + "\r\n"
                        + "Content-Type: text/html; charset=utf-8\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: keep-alive\r\n\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(head.getBytes(StandardCharsets.UTF_8));
                out.write(body);
                out.flush();
            }
        } catch (IOException ignored) {
            // 客户端关闭连接即结束
        }
    }
}
