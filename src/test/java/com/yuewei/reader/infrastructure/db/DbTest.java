package com.yuewei.reader.infrastructure.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证数据库以 WAL 模式打开：下载线程写章节时不阻塞 UI 读。 */
class DbTest {

    @TempDir
    Path temp;

    @Test
    void opensInWalMode() throws Exception {
        Db db = new Db(temp.resolve("reader.db"));
        try (Connection c = db.open();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
            String mode = rs.next() ? rs.getString(1) : "";
            assertEquals("wal", mode == null ? "" : mode.toLowerCase());
        }
    }
}
