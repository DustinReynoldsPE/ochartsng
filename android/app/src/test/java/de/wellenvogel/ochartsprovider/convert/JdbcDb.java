package de.wellenvogel.ochartsprovider.convert;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

//host-test Db implementation via sqlite-jdbc
public class JdbcDb implements Db {
    private Connection c;

    public JdbcDb(String path) throws Exception {
        c = DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    public List<LinkedHashMap<String, Object>> query(String sql) throws Exception {
        List<LinkedHashMap<String, Object>> rt = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) {
                    row.put(md.getColumnName(i), rs.getObject(i));
                }
                rt.add(row);
            }
        }
        return rt;
    }

    public void close() {
        try {
            if (c != null) c.close();
        } catch (Exception e) {
        }
        c = null;
    }
}
