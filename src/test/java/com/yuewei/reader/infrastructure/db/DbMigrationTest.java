package com.yuewei.reader.infrastructure.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 老库升级：缺少更新标记列时应自动补齐，且重复启动幂等。 */
class DbMigrationTest {

    @TempDir
    Path temp;

    @Test
    void addsUpdateColumnsToOldDatabase() throws Exception {
        Path file = temp.resolve("old.db");
        // 模拟 0.7.19 的老库：book 表没有 new_chapters / toc_checked_at
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE book(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      title TEXT NOT NULL,
                      author TEXT,
                      cover_url TEXT,
                      intro TEXT,
                      source_type TEXT NOT NULL,
                      source_id INTEGER,
                      book_url TEXT,
                      chapter_count INTEGER NOT NULL DEFAULT 0,
                      file_hash TEXT,
                      offline INTEGER NOT NULL DEFAULT 0,
                      added_at TEXT NOT NULL,
                      last_read_at TEXT,
                      deleted INTEGER NOT NULL DEFAULT 0
                    )
                    """);
        }

        new Db(file);
        new Db(file);

        List<String> columns = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA table_info(book)")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        assertTrue(columns.contains("new_chapters"), "缺少 new_chapters 列");
        assertTrue(columns.contains("toc_checked_at"), "缺少 toc_checked_at 列");
    }
}
