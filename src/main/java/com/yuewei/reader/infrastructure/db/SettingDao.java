package com.yuewei.reader.infrastructure.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class SettingDao {

    private final Db db;

    public SettingDao(Db db) {
        this.db = db;
    }

    public Map<String, String> all() {
        Map<String, String> map = new HashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT key,value FROM setting");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getString("key"), rs.getString("value"));
            }
            return map;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void put(String key, String value) {
        String sql = "INSERT INTO setting(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value == null ? "" : value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
