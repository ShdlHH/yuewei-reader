package com.yuewei.reader.infrastructure.db;

import com.yuewei.reader.domain.ReadingProgress;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

public class ProgressDao {

    private final Db db;

    public ProgressDao(Db db) {
        this.db = db;
    }

    public void save(long bookId, int chapterIndex, int charOffset) {
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
            ps.setString(4, LocalDateTime.now().toString());
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
                if (!rs.next()) {
                    return null;
                }
                ReadingProgress p = new ReadingProgress();
                p.bookId = rs.getLong("book_id");
                p.chapterIndex = rs.getInt("chapter_index");
                p.charOffset = rs.getInt("char_offset");
                p.updatedAt = rs.getString("updated_at");
                return p;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
