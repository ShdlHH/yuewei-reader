package com.yuewei.reader.infrastructure.db;

import com.yuewei.reader.domain.BookSource;
import com.yuewei.reader.domain.SourceStatus;
import com.yuewei.reader.domain.SupportLevel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class BookSourceDao {

    private final Db db;

    public BookSourceDao(Db db) {
        this.db = db;
    }

    public List<BookSource> findAll() {
        String sql = "SELECT * FROM book_source ORDER BY id";
        try (Connection c = db.open(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<BookSource> list = new ArrayList<>();
            while (rs.next()) {
                list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public BookSource findById(long id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM book_source WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public BookSource findByUrl(String url) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM book_source WHERE url=?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public long insert(BookSource src) {
        String sql = """
                INSERT INTO book_source(name,url,grp,type,enabled,status,support,last_check,rule_json,last_error,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, src.name);
            ps.setString(2, src.url);
            ps.setString(3, src.group);
            ps.setInt(4, src.type);
            ps.setInt(5, src.enabled ? 1 : 0);
            ps.setString(6, src.status.name());
            ps.setString(7, src.support.name());
            ps.setString(8, src.lastCheck);
            ps.setString(9, src.ruleJson);
            ps.setString(10, src.lastError);
            ps.setString(11, LocalDateTime.now().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    src.id = keys.getLong(1);
                }
            }
            return src.id;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void update(BookSource src) {
        String sql = """
                UPDATE book_source SET name=?, url=?, grp=?, type=?, enabled=?, status=?, support=?,
                  last_check=?, rule_json=?, last_error=? WHERE id=?
                """;
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, src.name);
            ps.setString(2, src.url);
            ps.setString(3, src.group);
            ps.setInt(4, src.type);
            ps.setInt(5, src.enabled ? 1 : 0);
            ps.setString(6, src.status.name());
            ps.setString(7, src.support.name());
            ps.setString(8, src.lastCheck);
            ps.setString(9, src.ruleJson);
            ps.setString(10, src.lastError);
            ps.setLong(11, src.id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void delete(long id) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM book_source WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void setEnabled(long id, boolean enabled) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE book_source SET enabled=? WHERE id=?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void updateCheck(long id, SourceStatus status, SupportLevel support, String error) {
        String sql = "UPDATE book_source SET status=?, support=?, last_check=?, last_error=? WHERE id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, support.name());
            ps.setString(3, LocalDateTime.now().toString());
            ps.setString(4, error);
            ps.setLong(5, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<BookSource> findSearchable() {
        String sql = "SELECT * FROM book_source WHERE enabled=1 AND type=0 "
                + "AND status IN ('OK','PARTIAL') ORDER BY id";
        try (Connection c = db.open(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<BookSource> list = new ArrayList<>();
            while (rs.next()) {
                list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public int countBooks(long sourceId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM book WHERE source_id=?")) {
            ps.setLong(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private BookSource map(ResultSet rs) throws SQLException {
        BookSource s = new BookSource();
        s.id = rs.getLong("id");
        s.name = rs.getString("name");
        s.url = rs.getString("url");
        s.group = rs.getString("grp");
        s.type = rs.getInt("type");
        s.enabled = rs.getInt("enabled") == 1;
        s.status = parse(SourceStatus.class, rs.getString("status"), SourceStatus.UNCHECKED);
        s.support = parse(SupportLevel.class, rs.getString("support"), SupportLevel.FULL);
        s.lastCheck = rs.getString("last_check");
        s.ruleJson = rs.getString("rule_json");
        s.lastError = rs.getString("last_error");
        return s;
    }

    private <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
