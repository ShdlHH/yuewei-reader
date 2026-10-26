package com.yuewei.reader.infrastructure.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.SearchResult;
import com.yuewei.reader.domain.TocChapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    private static final String BASE = "https://demo.test";

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode rules(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void parsesSearchResultsAndAbsolutizesUrls() throws Exception {
        String html = """
                <html><body>
                <div class="book-list">
                  <div class="item">
                    <h3 class="name"><a href="/book/9">斗破苍穹</a></h3>
                    <span class="author">天蚕土豆</span>
                    <span class="last">第1623章 大结局</span>
                  </div>
                  <div class="item">
                    <h3 class="name"><a href="/book/10">武动乾坤</a></h3>
                    <span class="author">天蚕土豆</span>
                    <span class="last">第100章</span>
                  </div>
                </div>
                </body></html>
                """;
        JsonNode rules = rules("""
                {"ruleSearch": {
                  "bookList": "class.book-list@tag.div",
                  "name": "class.name@text",
                  "author": "class.author@text",
                  "bookUrl": "class.name@tag.a@href",
                  "lastChapter": "class.last@text"
                }}
                """);

        RuleEngine.Stats stats = new RuleEngine.Stats();
        List<SearchResult> results = RuleEngine.parseSearch(
                RuleEngine.parseHtml(html, BASE + "/search"), BASE + "/search", rules, stats);

        assertEquals(2, results.size());
        assertEquals("斗破苍穹", results.get(0).title);
        assertEquals("天蚕土豆", results.get(0).author);
        assertEquals(BASE + "/book/9", results.get(0).bookUrl);
        assertEquals(2, stats.matched);
        assertEquals("斗破苍穹", stats.fields.get("书名"));
    }

    @Test
    void appliesRegexReplacementInRules() throws Exception {
        String html = "<div class=\"name\">斗破苍穹（精校版）</div>";
        JsonNode rules = rules("""
                {"ruleBookInfo": {"name": "class.name@text##（.*?）##"}}
                """);
        BookDetail detail = RuleEngine.parseDetail(
                RuleEngine.parseHtml(html, BASE + "/book/9"), BASE + "/book/9", rules, null);
        assertNotNull(detail);
        assertEquals("斗破苍穹", detail.title);
    }

    @Test
    void parsesBookDetailAndTocUrl() throws Exception {
        String html = """
                <html><body>
                <h1 class="name">斗破苍穹</h1>
                <p class="author">天蚕土豆</p>
                <div class="intro">这里是简介</div>
                <a class="toc" href="toc.html">目录</a>
                </body></html>
                """;
        JsonNode rules = rules("""
                {"ruleBookInfo": {
                  "name": "class.name@text",
                  "author": "class.author@text",
                  "intro": "class.intro@text",
                  "tocUrl": "class.toc@href"
                }}
                """);
        BookDetail detail = RuleEngine.parseDetail(
                RuleEngine.parseHtml(html, BASE + "/book/9"), BASE + "/book/9", rules, null);

        assertEquals("斗破苍穹", detail.title);
        assertEquals("天蚕土豆", detail.author);
        assertEquals("这里是简介", detail.intro);
        assertEquals(BASE + "/book/toc.html", detail.tocUrl);
        assertEquals(BASE + "/book/9", detail.bookUrl);
    }

    @Test
    void parsesTocChapters() throws Exception {
        String html = """
                <html><body>
                <ul id="list">
                  <li><a href="c1.html">第一章 开始</a></li>
                  <li><a href="c2.html">第二章 继续</a></li>
                </ul>
                </body></html>
                """;
        JsonNode rules = rules("""
                {"ruleToc": {
                  "chapterList": "id.list@tag.li",
                  "chapterName": "tag.a@text",
                  "chapterUrl": "tag.a@href"
                }}
                """);
        List<TocChapter> chapters = RuleEngine.parseToc(
                RuleEngine.parseHtml(html, BASE + "/book/9/toc"), BASE + "/book/9/toc", rules, null);

        assertEquals(2, chapters.size());
        assertEquals("第一章 开始", chapters.get(0).title);
        assertEquals(BASE + "/book/9/c1.html", chapters.get(0).url);
        assertEquals(1, chapters.get(1).index);
    }

    @Test
    void parsesContentWithParagraphs() throws Exception {
        String html = """
                <html><body>
                <div id="content">第一段<br><br>第二段 &amp; 符号</div>
                </body></html>
                """;
        JsonNode rules = rules("""
                {"ruleContent": {"content": "id.content@html"}}
                """);
        RuleEngine.Stats stats = new RuleEngine.Stats();
        String text = RuleEngine.parseContent(
                RuleEngine.parseHtml(html, BASE + "/c1.html"), BASE + "/c1.html", rules, stats);

        assertTrue(text.contains("第一段"));
        assertTrue(text.contains("第二段 & 符号"));
        assertEquals(1, stats.matched);
    }

    @Test
    void findsNextContentUrl() throws Exception {
        String html = """
                <html><body>
                <div id="content">正文</div>
                <a class="next" href="c2.html">下一页</a>
                </body></html>
                """;
        JsonNode rules = rules("""
                {"ruleContent": {"content": "id.content@html", "nextContentUrl": "class.next@href"}}
                """);
        String next = RuleEngine.nextContentUrl(
                RuleEngine.parseHtml(html, BASE + "/c1.html"), BASE + "/c1.html", rules);
        assertEquals(BASE + "/c2.html", next);

        JsonNode noNext = rules("""
                {"ruleContent": {"content": "id.content@html"}}
                """);
        assertNull(RuleEngine.nextContentUrl(
                RuleEngine.parseHtml(html, BASE + "/c1.html"), BASE + "/c1.html", noNext));
    }
}
