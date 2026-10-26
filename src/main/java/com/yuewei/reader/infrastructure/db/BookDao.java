package com.yuewei.reader.infrastructure.db;

import com.yuewei.reader.domain.Book;
import com.yuewei.reader.domain.SourceType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class BookDao {

    private static final String SELECT = """
            SELECT b.*, s.name AS source_name,
                   CASE WHEN b.source_id IS NOT NULL AND s.id IS NULL THEN 1 ELSE 0 END AS source_missing
            FROM book b LEFT JOIN book_source s ON b.source_id = s.id
            """;

    private final Db db;

    public BookDao(Db db) {
        this.db = db;
    }

    public long insert(Book b) {
        String sql = """
                INSERT INTO book(title,author,cover_url,intro,source_type,source_id,book_url,
                  chapter_count,file_hash,offline,added_at,last_read_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, b.title);
            ps.setString(2, b.author);
            ps.setString(3, b.coverUrl);
            ps.setString(4, b.intro);
            ps.setString(5, b.sourceType.name());
            if (b.sourceId == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setLong(6, b.sourceId);
            }
            ps.setString(7, b.bookUrl);
            ps.setInt(8, b.chapterCount);
            ps.setString(9, b.fileHash);
            ps.setInt(10, b.offline ? 1 : 0);
            b.addedAt = LocalDateTime.now().toString();
            ps.setString(11, b.addedAt);
            ps.setString(12, b.lastReadAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    b.id = keys.getLong(1);
                }
            }
            return b.id;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Book findById(long id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(SELECT + " WHERE b.id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Book> findAll() {
        try (Connection c = db.open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(SELECT + " ORDER BY COALESCE(b.last_read_at, b.added_at) DESC")) {
            List<Book> list = new ArrayList<>();
            while (rs.next()) {
                list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Book findByHash(String hash) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(SELECT + " WHERE b.file_hash=? LIMIT 1")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Book findOnline(long sourceId, String bookUrl) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(SELECT + " WHERE b.source_id=? AND b.book_url=? LIMIT 1")) {
            ps.setLong(1, sourceId);
            ps.setString(2, bookUrl);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void updateMeta(Book b) {
        String sql = "UPDATE book SET title=?, author=?, cover_url=?, intro=?, chapter_count=?, offline=? WHERE id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, b.title);
            ps.setString(2, b.author);
            ps.setString(3, b.coverUrl);
            ps.setString(4, b.intro);
            ps.setInt(5, b.chapterCount);
            ps.setInt(6, b.offline ? 1 : 0);
            ps.setLong(7, b.id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void updateChapterCount(long id, int count) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE book SET chapter_count=? WHERE id=?")) {
            ps.setInt(1, count);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void markOffline(long id, boolean offline) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE book SET offline=? WHERE id=?")) {
            ps.setInt(1, offline ? 1 : 0);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void touchRead(long id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE book SET last_read_at=? WHERE id=?")) {
            ps.setString(1, LocalDateTime.now().toString());
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void delete(long id) {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement p1 = c.prepareStatement("DELETE FROM chapter WHERE book_id=?");
                 PreparedStatement p2 = c.prepareStatement("DELETE FROM reading_progress WHERE book_id=?");
                 PreparedStatement p3 = c.prepareStatement("DELETE FROM download_task WHERE book_id=?");
                 PreparedStatement p4 = c.prepareStatement("DELETE FROM book WHERE id=?")) {
                p1.setLong(1, id); p1.executeUpdate();
                p2.setLong(1, id); p2.executeUpdate();
                p3.setLong(1, id); p3.executeUpdate();
                p4.setLong(1, id); p4.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Book map(ResultSet rs) throws SQLException {
        Book b = new Book();
        b.id = rs.getLong("id");
        b.title = rs.getString("title");
        b.author = rs.getString("author");
        b.coverUrl = rs.getString("cover_url");
        b.intro = rs.getString("intro");
        b.sourceType = SourceType.valueOf(rs.getString("source_type"));
        long sid = rs.getLong("source_id");
        b.sourceId = rs.wasNull() ? null : sid;
        b.bookUrl = rs.getString("book_url");
        b.chapterCount = rs.getInt("chapter_count");
        b.fileHash = rs.getString("file_hash");
        b.offline = rs.getInt("offline") == 1;
        b.addedAt = rs.getString("added_at");
        b.lastReadAt = rs.getString("last_read_at");
        b.sourceName = rs.getString("source_name");
        b.sourceMissing = rs.getInt("source_missing") == 1;
        return b;
    }
}
