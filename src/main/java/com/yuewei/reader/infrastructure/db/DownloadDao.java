package com.yuewei.reader.infrastructure.db;

import com.yuewei.reader.domain.DownloadRangeType;
import com.yuewei.reader.domain.DownloadState;
import com.yuewei.reader.domain.DownloadTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class DownloadDao {

    private final Db db;

    public DownloadDao(Db db) {
        this.db = db;
    }

    public long insert(DownloadTask t) {
        String sql = """
                INSERT INTO download_task(book_id,book_title,range_type,from_index,to_index,state,
                  success_count,fail_count,failed_indexes,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, t.bookId);
            ps.setString(2, t.bookTitle);
            ps.setString(3, t.rangeType.name());
            if (t.fromIndex == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setInt(4, t.fromIndex);
            }
            if (t.toIndex == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, t.toIndex);
            }
            ps.setString(6, t.state.name());
            ps.setInt(7, t.successCount);
            ps.setInt(8, t.failCount);
            ps.setString(9, join(t.failedIndexes));
            t.createdAt = LocalDateTime.now().toString();
            ps.setString(10, t.createdAt);
            ps.setString(11, t.createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    t.id = keys.getLong(1);
                }
            }
            return t.id;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void update(DownloadTask t) {
        String sql = """
                UPDATE download_task SET state=?, success_count=?, fail_count=?, failed_indexes=?, updated_at=?
                WHERE id=?
                """;
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, t.state.name());
            ps.setInt(2, t.successCount);
            ps.setInt(3, t.failCount);
            ps.setString(4, join(t.failedIndexes));
            ps.setString(5, LocalDateTime.now().toString());
            ps.setLong(6, t.id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<DownloadTask> findAll() {
        try (Connection c = db.open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM download_task ORDER BY id DESC")) {
            List<DownloadTask> list = new ArrayList<>();
            while (rs.next()) {
                list.add(map(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 启动时把上次未完成的任务置为暂停。 */
    public void pauseUnfinishedOnStartup() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE download_task SET state='PAUSED' WHERE state IN ('QUEUED','RUNNING')")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private DownloadTask map(ResultSet rs) throws SQLException {
        DownloadTask t = new DownloadTask();
        t.id = rs.getLong("id");
        t.bookId = rs.getLong("book_id");
        t.bookTitle = rs.getString("book_title");
        t.rangeType = DownloadRangeType.valueOf(rs.getString("range_type"));
        int from = rs.getInt("from_index");
        t.fromIndex = rs.wasNull() ? null : from;
        int to = rs.getInt("to_index");
        t.toIndex = rs.wasNull() ? null : to;
        t.state = DownloadState.valueOf(rs.getString("state"));
        t.successCount = rs.getInt("success_count");
        t.failCount = rs.getInt("fail_count");
        t.failedIndexes = split(rs.getString("failed_indexes"));
        t.createdAt = rs.getString("created_at");
        t.updatedAt = rs.getString("updated_at");
        return t;
    }

    private String join(List<Integer> indexes) {
        if (indexes == null || indexes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indexes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(indexes.get(i));
        }
        return sb.toString();
    }

    private List<Integer> split(String value) {
        List<Integer> list = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return list;
        }
        for (String part : value.split(",")) {
            try {
                list.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // 损坏的历史数据直接跳过
            }
        }
        return list;
    }
}
