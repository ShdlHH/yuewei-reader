package com.yuewei.reader.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.BookDetail;
import com.yuewei.reader.domain.BookSource;
import com.yuewei.reader.domain.Chapter;
import com.yuewei.reader.domain.ContentState;
import com.yuewei.reader.domain.SearchResult;
import com.yuewei.reader.domain.SourceType;
import com.yuewei.reader.domain.TocChapter;
import com.yuewei.reader.infrastructure.cache.ChapterCache;
import com.yuewei.reader.infrastructure.db.BookDao;
import com.yuewei.reader.infrastructure.db.BookSourceDao;
import com.yuewei.reader.infrastructure.db.ChapterDao;
import com.yuewei.reader.infrastructure.network.HttpGateway;
import com.yuewei.reader.infrastructure.rule.RuleEngine;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 在线书：详情、目录、正文与入架。 */
public class OnlineBookService {

    private final HttpGateway http;
    private final BookDao bookDao;
    private final ChapterDao chapterDao;
    private final BookSourceDao sourceDao;
    private final ChapterCache cache;
    private final SettingsService settings;

    public OnlineBookService(HttpGateway http, BookDao bookDao, ChapterDao chapterDao,
                             BookSourceDao sourceDao, ChapterCache cache, SettingsService settings) {
        this.http = http;
        this.bookDao = bookDao;
        this.chapterDao = chapterDao;
        this.sourceDao = sourceDao;
        this.cache = cache;
        this.settings = settings;
    }

    public BookDetail fetchDetail(BookSource source, SearchResult result) {
        if (result.bookUrl == null || result.bookUrl.isBlank()) {
            throw new AppException("搜索结果缺少书籍地址");
        }
        JsonNode rules = RuleJson.parse(source.ruleJson);
        HttpGateway.Response detailResp = get(result.bookUrl, "详情页");
        Document detailDoc = RuleEngine.parseHtml(detailResp.body, detailResp.finalUrl);
        BookDetail detail = RuleEngine.parseDetail(detailDoc, detailResp.finalUrl, rules, null);
        if (detail == null) {
            throw new AppException("该书源未配置 ruleBookInfo");
        }
        if (detail.title == null || detail.title.isBlank()) {
            detail.title = result.title;
        }
        if (detail.author == null || detail.author.isBlank()) {
            detail.author = result.author;
        }
        if (detail.coverUrl == null || detail.coverUrl.isBlank()) {
            detail.coverUrl = result.coverUrl;
        }
        if (detail.intro == null || detail.intro.isBlank()) {
            detail.intro = result.intro;
        }
        if (detail.lastChapter == null || detail.lastChapter.isBlank()) {
            detail.lastChapter = result.lastChapter;
        }
        detail.bookUrl = result.bookUrl;
        detail.sourceId = source.id;
        detail.sourceName = source.name;

        String tocUrl = detail.tocUrl == null || detail.tocUrl.isBlank() ? result.bookUrl : detail.tocUrl;
        HttpGateway.Response tocResp = get(tocUrl, "目录页");
        Document tocDoc = RuleEngine.parseHtml(tocResp.body, tocResp.finalUrl);
        List<TocChapter> chapters = RuleEngine.parseToc(tocDoc, tocResp.finalUrl, rules, null);
        if (chapters.isEmpty()) {
            throw new AppException("目录为空，可能该书源目录规则已失效；可切换其他书源或检查规则");
        }
        detail.tocUrl = tocUrl;
        detail.chapters = chapters;
        return detail;
    }

    public Book addToShelf(BookSource source, BookDetail detail) {
        Book existing = bookDao.findOnline(source.id, detail.bookUrl);
        if (existing != null) {
            throw new AppException("这本书已在书架");
        }
        Book book = new Book();
        book.title = detail.title;
        book.author = detail.author;
        book.coverUrl = detail.coverUrl;
        book.intro = detail.intro;
        book.sourceType = SourceType.ONLINE;
        book.sourceId = source.id;
        book.bookUrl = detail.bookUrl;
        book.chapterCount = detail.chapters.size();
        book.sourceName = source.name;
        bookDao.insert(book);
        chapterDao.insertBatch(book.id, detail.chapters, ContentState.NOT_CACHED);
        return book;
    }

    public String loadContent(Book book, Chapter chapter) {
        String cached = cache.read(book.id, chapter.idx);
        if (cached != null) {
            if (chapter.state != ContentState.CACHED) {
                chapterDao.updateState(book.id, chapter.idx, ContentState.CACHED, cached.length());
            }
            return cached;
        }
        if (book.sourceType != SourceType.ONLINE) {
            throw new AppException("本章内容缺失，请重新导入该文件");
        }
        BookSource source = book.sourceId == null ? null : sourceDao.findById(book.sourceId);
        if (source == null) {
            throw new AppException("所属书源已被删除，只能阅读已下载章节");
        }
        return fetchContent(book, source, chapter);
    }

    public String fetchContent(Book book, BookSource source, Chapter chapter) {
        if (chapter.url == null || chapter.url.isBlank()) {
            throw new AppException("该章节缺少正文地址");
        }
        JsonNode rules = RuleJson.parse(source.ruleJson);
        StringBuilder text = new StringBuilder();
        String url = chapter.url;
        Set<String> visited = new HashSet<>();
        for (int page = 0; page < 5; page++) {
            if (!visited.add(url)) {
                break;
            }
            HttpGateway.Response resp = get(url, "正文页");
            Document doc = RuleEngine.parseHtml(resp.body, resp.finalUrl);
            String part = RuleEngine.parseContent(doc, resp.finalUrl, rules, null);
            if (part.isBlank()) {
                if (text.length() == 0) {
                    throw new AppException("正文解析为空，该书源正文规则可能已失效");
                }
                break;
            }
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(part);
            String next = RuleEngine.nextContentUrl(doc, resp.finalUrl, rules);
            if (next == null || next.isBlank()) {
                break;
            }
            url = next;
        }
        String content = text.toString().trim();
        if (content.isEmpty()) {
            throw new AppException("正文解析为空，该书源正文规则可能已失效");
        }
        cache.write(book.id, chapter.idx, content);
        chapterDao.updateState(book.id, chapter.idx, ContentState.CACHED, content.length());
        int interval = settings.requestInterval();
        if (interval > 0) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return content;
    }

    /** 打开书时更新目录，只补新增章节，返回新增数量。 */
    public int refreshToc(Book book) {
        if (book.sourceId == null) {
            throw new AppException("所属书源已被删除");
        }
        BookSource source = sourceDao.findById(book.sourceId);
        if (source == null) {
            throw new AppException("所属书源已被删除");
        }
        JsonNode rules = RuleJson.parse(source.ruleJson);
        HttpGateway.Response bookResp = get(book.bookUrl, "详情页");
        Document bookDoc = RuleEngine.parseHtml(bookResp.body, bookResp.finalUrl);
        BookDetail detail = RuleEngine.parseDetail(bookDoc, bookResp.finalUrl, rules, null);
        String tocUrl = detail == null || detail.tocUrl == null || detail.tocUrl.isBlank()
                ? book.bookUrl
                : detail.tocUrl;
        HttpGateway.Response tocResp = get(tocUrl, "目录页");
        Document tocDoc = RuleEngine.parseHtml(tocResp.body, tocResp.finalUrl);
        List<TocChapter> chapters = RuleEngine.parseToc(tocDoc, tocResp.finalUrl, rules, null);
        if (chapters.isEmpty()) {
            throw new AppException("更新目录失败：未解析到章节");
        }
        int before = chapterDao.countByBook(book.id);
        bookDao.updateChapterCount(book.id, chapters.size());
        chapterDao.insertIfAbsent(book.id, chapters);
        int after = chapterDao.countByBook(book.id);
        return Math.max(0, after - before);
    }

    public BookDetail fetchDetailForBook(Book book) {
        SearchResult stub = new SearchResult();
        stub.title = book.title;
        stub.author = book.author;
        stub.coverUrl = book.coverUrl;
        stub.intro = book.intro;
        stub.bookUrl = book.bookUrl;
        stub.sourceId = book.sourceId == null ? 0 : book.sourceId;
        BookSource source = book.sourceId == null ? null : sourceDao.findById(book.sourceId);
        if (source == null) {
            throw new AppException("所属书源已被删除");
        }
        return fetchDetail(source, stub);
    }

    private HttpGateway.Response get(String url, String label) {
        try {
            return http.get(url);
        } catch (IOException e) {
            throw new AppException(label + "请求失败：" + e.getMessage(), e);
        }
    }
}
