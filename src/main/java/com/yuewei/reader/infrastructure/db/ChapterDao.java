package com.yuewei.reader.infrastructure.db;

import com.yuewei.reader.domain.Chapter;
import com.yuewei.reader.domain.ContentState;
import com.yuewei.reader.domain.TocChapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ChapterDao {

    private final Db db;

    public ChapterDao(Db db) {
        this.db = db;
    }

    public void insertBatch(long bookId, List<TocChapter> chapters, ContentState state) {
        String sql = "INSERT INTO chapter(book_id,idx,title,url,state,word_count) VALUES(?,?,?,?,?,0)";
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (TocChapter ch : chapters) {
                    ps.setLong(1, bookId);
                    ps.setInt(2, ch.index);
                    ps.setString(3, ch.title);
                    ps.setString(4, ch.url);
                    ps.setString(5, state.name());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Chapter> findByBook(long bookId) {
        String sql = "SELECT * FROM chapter WHERE book_id=? ORDER BY idx";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Chapter> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(map(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Chapter findByIndex(long bookId, int idx) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM chapter WHERE book_id=? AND idx=?")) {
            ps.setLong(1, bookId);
            ps.setInt(2, idx);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void updateState(long bookId, int idx, ContentState state, int wordCount) {
        String sql = "UPDATE chapter SET state=?, word_count=? WHERE book_id=? AND idx=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setInt(2, wordCount);
            ps.setLong(3, bookId);
            ps.setInt(4, idx);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public int countByBook(long bookId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM chapter WHERE book_id=?")) {
            ps.setLong(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public int countCached(long bookId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM chapter WHERE book_id=? AND state='CACHED'")) {
            ps.setLong(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void deleteByBook(long bookId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM chapter WHERE book_id=?")) {
            ps.setLong(1, bookId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 本地导入：直接以已缓存状态批量写入。 */
    public void insertCachedBatch(long bookId, List<TocChapter> chapters, List<Integer> wordCounts) {
        String sql = "INSERT INTO chapter(book_id,idx,title,url,state,word_count) VALUES(?,?,?,?,'CACHED',?)";
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < chapters.size(); i++) {
                    TocChapter ch = chapters.get(i);
                    ps.setLong(1, bookId);
                    ps.setInt(2, ch.index);
                    ps.setString(3, ch.title);
                    ps.setString(4, ch.url);
                    ps.setInt(5, i < wordCounts.size() ? wordCounts.get(i) : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 更新目录时只补新增章节，保留已有章节状态。 */
    public int insertIfAbsent(long bookId, List<TocChapter> chapters) {
        String sql = "INSERT OR IGNORE INTO chapter(book_id,idx,title,url,state,word_count) "
                + "VALUES(?,?,?,?,'NOT_CACHED',0)";
        int added = 0;
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (TocChapter ch : chapters) {
                    ps.setLong(1, bookId);
                    ps.setInt(2, ch.index);
                    ps.setString(3, ch.title);
                    ps.setString(4, ch.url);
                    added += ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return added;
    }

    public void resetCacheState(long bookId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE chapter SET state='NOT_CACHED', word_count=0 WHERE book_id=?")) {
            ps.setLong(1, bookId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Chapter map(ResultSet rs) throws SQLException {
        Chapter ch = new Chapter();
        ch.id = rs.getLong("id");
        ch.bookId = rs.getLong("book_id");
        ch.idx = rs.getInt("idx");
        ch.title = rs.getString("title");
        ch.url = rs.getString("url");
        ch.state = ContentState.valueOf(rs.getString("state"));
        ch.wordCount = rs.getInt("word_count");
        return ch;
    }
}
