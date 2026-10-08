package de.wellenvogel.ochartsprovider.convert;

import java.util.LinkedHashMap;
import java.util.List;

//minimal sqlite access, implemented by android.database.sqlite in the app
//and by fakes/JDBC in host tests; rows are column-name -> value in column order
public interface Db extends AutoCloseable {
    List<LinkedHashMap<String, Object>> query(String sql) throws Exception;

    void close();
}
