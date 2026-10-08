package de.wellenvogel.ochartsprovider.convert;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class AndroidDb implements Db {
    private SQLiteDatabase db;

    public AndroidDb(String path) {
        db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY);
    }

    public List<LinkedHashMap<String, Object>> query(String sql) throws Exception {
        List<LinkedHashMap<String, Object>> rt = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, null)) {
            String[] cols = c.getColumnNames();
            while (c.moveToNext()) {
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < cols.length; i++) {
                    Object v;
                    switch (c.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL:
                            v = null;
                            break;
                        case Cursor.FIELD_TYPE_INTEGER:
                            v = c.getLong(i);
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            v = c.getDouble(i);
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            v = c.getBlob(i);
                            break;
                        default:
                            v = c.getString(i);
                            break;
                    }
                    row.put(cols[i], v);
                }
                rt.add(row);
            }
        }
        return rt;
    }

    public void close() {
        if (db != null) db.close();
        db = null;
    }
}
