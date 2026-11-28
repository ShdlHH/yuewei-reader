package com.yuewei.reader.infrastructure.db;

import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 更新标记读写：写入新增章节数并记录检查时间，写 0 即清除。 */
class UpdateMarkerTest {

    @TempDir
    Path temp;

    @Test
    void writesAndClearsUpdateMarker() {
        Db db = new Db(temp.resolve("reader.db"));
        BookDao dao = new BookDao(db);

        Book book = new Book();
        book.title = "测试书";
        book.author = "作者";
        book.sourceType = SourceType.ONLINE;
        book.sourceId = 1L;
        book.bookUrl = "https://example.com/book/1";
        book.chapterCount = 100;
        dao.insert(book);

        dao.updateNewChapters(book.id, 7);
        Book marked = dao.findById(book.id);
        assertEquals(7, marked.newChapters);
        assertNotNull(marked.tocCheckedAt);

        dao.updateNewChapters(book.id, 0);
        assertEquals(0, dao.findById(book.id).newChapters);
    }
}
