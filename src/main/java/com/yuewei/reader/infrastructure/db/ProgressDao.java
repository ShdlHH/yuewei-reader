package com.yuewei.reader.infrastructure.db;

import com.yuewei.reader.domain.ReadingProgress;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class ProgressDao {

    private final Db db;

    public ProgressDao(Db db) {
        this.db = db;
    }

    /** 同步用：一次性取出全部进度，避免按书逐条查询（N+1）。 */
    public Map<Long, ReadingProgress> findAll() {
        Map<Long, ReadingProgress> map = new HashMap<>();
        try (Connection c = db.open();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM reading_progress")) {
            while (rs.next()) {
                ReadingProgress p = mapRow(rs);
                map.put(p.bookId, p);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return map;
    }

    public void save(long bookId, int chapterIndex, int charOffset) {
        saveRemote(bookId, chapterIndex, charOffset, LocalDateTime.now().toString());
    }

    /** 来自同步服务的进度：保留远端时间，便于下次比较新旧。 */
    public void saveRemote(long bookId, int chapterIndex, int charOffset, String updatedAt) {
        String sql = """
                INSERT INTO reading_progress(book_id,chapter_index,char_offset,updated_at) VALUES(?,?,?,?)
                ON CONFLICT(book_id) DO UPDATE SET
                  chapter_index=excluded.chapter_index,
                  char_offset=excluded.char_offset,
                  updated_at=excluded.updated_at
                """;
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, bookId);
            ps.setInt(2, chapterIndex);
            ps.setInt(3, Math.max(0, charOffset));
            ps.setString(4, updatedAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void deleteByBook(long bookId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM reading_progress WHERE book_id=?")) {
            ps.setLong(1, bookId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public ReadingProgress findByBook(long bookId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM reading_progress WHERE book_id=?")) {
            ps.setLong(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ReadingProgress mapRow(ResultSet rs) throws SQLException {
        ReadingProgress p = new ReadingProgress();
        p.bookId = rs.getLong("book_id");
        p.chapterIndex = rs.getInt("chapter_index");
        p.charOffset = rs.getInt("char_offset");
        p.updatedAt = rs.getString("updated_at");
        return p;
    }
}
