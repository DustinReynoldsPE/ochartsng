package de.wellenvogel.ochartsprovider.convert;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class S57Mappings {
    public static class S57OCL {
        public String Code;
        public String Acronym;

        public S57OCL(Map<String, String> row) {
            this.Code = row.get("Code");
            this.Acronym = row.get("Acronym");
        }

        public int id() {
            return Integer.parseInt(Code.trim());
        }
    }

    public static class S57Att {
        public String Code;
        public String Acronym;
        public String type;
        public String clazz;

        public S57Att(Map<String, String> row) {
            this.Code = row.get("Code");
            this.Acronym = row.get("Acronym");
            this.type = row.get("Attributetype");
            this.clazz = row.get("Class");
        }

        public int id() {
            return Integer.parseInt(Code.trim());
        }
    }

    public static final String OCL = "s57objectclasses.csv";
    public static final String ATT = "s57attributes.csv";
    public final String s57dir;
    public Map<Integer, S57OCL> objectClassesId = new HashMap<>();
    public Map<Integer, S57Att> attributesId = new HashMap<>();
    public Map<String, S57OCL> objectClasses = new HashMap<>();
    public Map<String, S57Att> attributes = new HashMap<>();
    private InputStream oclInput;
    private InputStream attInput;

    public S57Mappings(String s57dir) {
        this.s57dir = s57dir;
    }

    public S57Mappings(InputStream oclInput, InputStream attInput) {
        this.s57dir = null;
        this.oclInput = oclInput;
        this.attInput = attInput;
    }

    static List<String> parseCsvLine(String line) throws IOException {
        List<String> rt = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    inQuote = true;
                } else if (c == ',') {
                    rt.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        rt.add(cur.toString());
        return rt;
    }

    static List<Map<String, String>> readCsv(Reader rd) throws IOException {
        BufferedReader br = new BufferedReader(rd);
        List<Map<String, String>> rt = new ArrayList<>();
        String line;
        List<String> header = null;
        StringBuilder pending = new StringBuilder();
        while (true) {
            line = br.readLine();
            if (line == null) {
                if (pending.length() == 0) break;
            } else {
                if (pending.length() > 0) pending.append('\n');
                pending.append(line);
            }
            String cur = pending.toString();
            //a field with an embedded newline continues inside quotes
            int quotes = 0;
            for (int i = 0; i < cur.length(); i++) if (cur.charAt(i) == '"') quotes++;
            if (quotes % 2 != 0 && line != null) continue;
            pending.setLength(0);
            if (cur.isEmpty()) continue;
            List<String> fields = parseCsvLine(cur);
            if (header == null) {
                header = fields;
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), i < fields.size() ? fields.get(i) : null);
            }
            rt.add(row);
            if (line == null) break;
        }
        return rt;
    }

    public void parseObjectClasses() throws IOException {
        InputStream is = oclInput != null ? oclInput : new FileInputStream(new File(s57dir, OCL));
        try {
            for (Map<String, String> row : readCsv(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                S57OCL oclz = new S57OCL(row);
                objectClassesId.put(oclz.id(), oclz);
                objectClasses.put(oclz.Acronym, oclz);
            }
        } finally {
            is.close();
        }
    }

    public void parseAttributes() throws IOException {
        InputStream is = attInput != null ? attInput : new FileInputStream(new File(s57dir, ATT));
        try {
            for (Map<String, String> row : readCsv(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                S57Att attr = new S57Att(row);
                attributesId.put(attr.id(), attr);
                attributes.put(attr.Acronym, attr);
            }
        } finally {
            is.close();
        }
    }

    public void parse() throws IOException {
        parseObjectClasses();
        parseAttributes();
    }

    public S57OCL objByName(String name) {
        return objectClasses.get(name.toUpperCase(Locale.ROOT));
    }

    public S57OCL objById(Object id) {
        return objectClassesId.get(PyStr.toInt(id));
    }

    public S57Att attrByName(String name) {
        return attributes.get(name.toUpperCase(Locale.ROOT));
    }

    public S57Att attrById(Object id) {
        return attributesId.get(PyStr.toInt(id));
    }
}
