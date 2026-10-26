package com.yuewei.reader.infrastructure.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 数据库入口。每次操作独立取连接，避免 UI 与抓取线程共享同一连接。
 */
public class Db {

    private final String url;

    public Db(Path file) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("缺少 sqlite-jdbc 依赖", e);
        }
        this.url = "jdbc:sqlite:" + file.toAbsolutePath();
        init();
    }

    public Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA foreign_keys=ON");
        }
        return c;
    }

    private void init() {
        String[] ddl = {
                """
                CREATE TABLE IF NOT EXISTS book_source(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL,
                  url TEXT NOT NULL UNIQUE,
                  grp TEXT,
                  type INTEGER NOT NULL DEFAULT 0,
                  enabled INTEGER NOT NULL DEFAULT 1,
                  status TEXT NOT NULL DEFAULT 'UNCHECKED',
                  support TEXT NOT NULL DEFAULT 'FULL',
                  last_check TEXT,
                  rule_json TEXT NOT NULL,
                  last_error TEXT,
                  created_at TEXT NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS book(
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
                  last_read_at TEXT
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_book_hash ON book(file_hash)",
                """
                CREATE UNIQUE INDEX IF NOT EXISTS idx_book_online
                ON book(source_id, book_url) WHERE source_type='ONLINE'
                """,
                """
                CREATE TABLE IF NOT EXISTS chapter(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  book_id INTEGER NOT NULL,
                  idx INTEGER NOT NULL,
                  title TEXT NOT NULL,
                  url TEXT,
                  state TEXT NOT NULL DEFAULT 'NOT_CACHED',
                  word_count INTEGER NOT NULL DEFAULT 0,
                  UNIQUE(book_id, idx)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS reading_progress(
                  book_id INTEGER PRIMARY KEY,
                  chapter_index INTEGER NOT NULL DEFAULT 0,
                  char_offset INTEGER NOT NULL DEFAULT 0,
                  updated_at TEXT
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS download_task(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  book_id INTEGER NOT NULL,
                  book_title TEXT,
                  range_type TEXT NOT NULL,
                  from_index INTEGER,
                  to_index INTEGER,
                  state TEXT NOT NULL,
                  success_count INTEGER NOT NULL DEFAULT 0,
                  fail_count INTEGER NOT NULL DEFAULT 0,
                  failed_indexes TEXT,
                  created_at TEXT NOT NULL,
                  updated_at TEXT
                )
                """,
                "CREATE TABLE IF NOT EXISTS setting(key TEXT PRIMARY KEY, value TEXT NOT NULL)"
        };
        try (Connection c = open(); Statement s = c.createStatement()) {
            for (String sql : ddl) {
                s.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("初始化数据库失败: " + e.getMessage(), e);
        }
    }
}
