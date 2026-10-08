package de.wellenvogel.ochartsprovider.convert;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.wellenvogel.ochartsprovider.convert.SencGeometry.Extent;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.FAttr;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.GeometryBase;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.MultiPointGeometry;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.Point;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.PointGeometry;
import de.wellenvogel.ochartsprovider.convert.SencRecords.SencHeader;

public class SqliteToSenc {
    static String ts(String sfx) {
        return new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss", Locale.ROOT).format(new Date()) + "-" + sfx;
    }

    static void warn(String txt, Object... args) {
        System.out.println(ts("WARNING: ") + String.format(Locale.ROOT, txt, args));
    }

    static void err(String txt, Object... args) throws Exception {
        String msg = String.format(Locale.ROOT, txt, args);
        System.out.println(ts("ERROR: ") + msg);
        throw new Exception(msg);
    }

    static void log(String txt, Object... args) {
        System.out.println(ts("LOG: ") + String.format(Locale.ROOT, txt, args));
    }

    private static class JsonParser {
        String s;
        int pos;

        JsonParser(String s) {
            this.s = s;
        }

        void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object parse() throws Exception {
            ws();
            Object v = value();
            ws();
            if (pos < s.length()) throw new Exception("extra data in json at " + pos);
            return v;
        }

        char next() throws Exception {
            if (pos >= s.length()) throw new Exception("unexpected end of json");
            return s.charAt(pos++);
        }

        Object value() throws Exception {
            ws();
            if (pos >= s.length()) throw new Exception("unexpected end of json");
            char c = s.charAt(pos);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') {
                expect("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                expect("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                expect("null");
                return null;
            }
            if (c == 'N') {
                expect("NaN");
                return Double.NaN;
            }
            if (c == 'I') {
                expect("Infinity");
                return Double.POSITIVE_INFINITY;
            }
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw new Exception("unexpected char " + c + " in json at " + pos);
        }

        void expect(String lit) throws Exception {
            if (!s.startsWith(lit, pos)) throw new Exception("invalid literal in json at " + pos);
            pos += lit.length();
        }

        Object number() throws Exception {
            int st = pos;
            if (pos < s.length() && s.charAt(pos) == '-') pos++;
            if (pos < s.length() && s.startsWith("Infinity", pos)) {
                pos += 8;
                return Double.NEGATIVE_INFINITY;
            }
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
            boolean isFloat = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
            }
            String num = s.substring(st, pos);
            if (num.isEmpty() || num.equals("-")) throw new Exception("invalid number in json at " + st);
            if (!isFloat) {
                try {
                    return Long.parseLong(num);
                } catch (NumberFormatException e) {
                    return Double.parseDouble(num);
                }
            }
            return Double.parseDouble(num);
        }

        String string() throws Exception {
            StringBuilder sb = new StringBuilder();
            next(); //opening quote
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > s.length()) throw new Exception("invalid \\u escape in json");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw new Exception("invalid escape \\" + e + " in json");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        List<Object> array() throws Exception {
            List<Object> rt = new ArrayList<>();
            next(); //[
            ws();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return rt;
            }
            while (true) {
                rt.add(value());
                ws();
                char c = next();
                if (c == ']') return rt;
                if (c != ',') throw new Exception("expected , or ] in json at " + pos);
            }
        }

        Map<String, Object> object() throws Exception {
            Map<String, Object> rt = new LinkedHashMap<>();
            next(); //{
            ws();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return rt;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                if (next() != ':') throw new Exception("expected : in json at " + pos);
                rt.put(k, value());
                ws();
                char c = next();
                if (c == '}') return rt;
                if (c != ',') throw new Exception("expected , or } in json at " + pos);
            }
        }
    }

    static Object jsonLoads(String s) throws Exception {
        return new JsonParser(s).parse();
    }

    static String[] splitext(String p) {
        int sep = Math.max(p.lastIndexOf('/'), p.lastIndexOf(File.separatorChar));
        int ld = sep + 1;
        while (ld < p.length() && p.charAt(ld) == '.') ld++;
        int dot = p.lastIndexOf('.');
        if (dot > sep && dot >= ld) {
            return new String[]{p.substring(0, dot), p.substring(dot)};
        }
        return new String[]{p, ""};
    }

    static String readTxtFile(File f) throws Exception {
        byte[] raw = Files.readAllBytes(f.toPath());
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE)
                .decode(ByteBuffer.wrap(raw)).toString();
    }

    static boolean objectToSenc(SencWriter wh, String name, Map<String, Object> dbrow, String gcol, List<String> jsonColumns, String basedir) throws Exception {
        List<FAttr> attrs = new ArrayList<>();
        GeometryBase geometry = null;
        String txtdsc = null;
        for (Map.Entry<String, Object> e : dbrow.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null) continue;
            if (k.equals(gcol)) {
                geometry = WkbReader.parseGeometry((byte[]) v, name);
            } else {
                if (jsonColumns.contains(k)) {
                    Object jv = jsonLoads((String) v);
                    if (jv instanceof List) {
                        StringBuilder sb = new StringBuilder();
                        boolean first = true;
                        for (Object el : (List<?>) jv) {
                            if (!first) sb.append(',');
                            first = false;
                            sb.append(PyStr.str(el));
                        }
                        v = sb.toString();
                    } else {
                        v = PyStr.str(jv);
                    }
                }
                attrs.add(new FAttr(k, v));
                if (k.toUpperCase(Locale.ROOT).equals("TXTDSC")) {
                    txtdsc = v.toString();
                }
            }
        }
        wh.addFeature(name, attrs, geometry);
        if (txtdsc != null && basedir != null) {
            File fn = new File(basedir, txtdsc);
            if (fn.exists()) {
                String txt = readTxtFile(fn);
                wh.addTxt(txtdsc, txt);
                log("added txt %s", txtdsc);
            } else {
                warn("txt file %s not found", txtdsc);
            }
        }
        return true;
    }

    static List<String> getJsonColumns(String table, Db db) throws Exception {
        List<String> rt = new ArrayList<>();
        for (Map<String, Object> dbrow : db.query("PRAGMA table_info('" + table + "')")) {
            Object t = dbrow.get("type");
            if (t != null && t.toString().toUpperCase(Locale.ROOT).startsWith("JSON")) {
                rt.add((String) dbrow.get("name"));
            }
        }
        return rt;
    }

    static void writeTableToSenc(SencWriter wh, Db db, String name, String gcol, String basedir, boolean ignoreErrors) throws Exception {
        List<String> jsonColumns = getJsonColumns(name, db);
        for (Map<String, Object> dbrow : db.query("select * from " + name)) {
            try {
                objectToSenc(wh, name, dbrow, gcol, jsonColumns, basedir);
            } catch (Exception e) {
                if (!ignoreErrors) {
                    throw e;
                } else {
                    warn("Error when reading %s: %s", name, e.getMessage());
                }
            }
        }
    }

    //we only handle some sounding attrs to be able to group them nicely
    static final String[] SOUNGD_ATTRS = {"SCAMIN", "SCAMAX"};

    static void writeSoundings(SencWriter wh, Db db, String name, String gcol) throws Exception {
        //find our attr combinations
        List<String> anames = new ArrayList<>();
        for (Map<String, Object> dbrow : db.query("PRAGMA table_info('" + name + "')")) {
            Object cn = dbrow.get("name");
            if (cn == null) continue;
            for (String a : SOUNGD_ATTRS) {
                if (cn.toString().toUpperCase(Locale.ROOT).equals(a)) {
                    anames.add(cn.toString());
                    break;
                }
            }
        }
        List<LinkedHashMap<String, Object>> chunks = new ArrayList<>();
        if (anames.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < anames.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(anames.get(i));
            }
            chunks.addAll(db.query("select distinct " + sb + " from " + name));
        }
        if (chunks.size() < 1) {
            chunks.add(new LinkedHashMap<>());
        }
        for (LinkedHashMap<String, Object> chunk : chunks) {
            StringBuilder q = new StringBuilder();
            for (Map.Entry<String, Object> e : chunk.entrySet()) {
                if (q.length() > 0) q.append(" and ");
                if (e.getValue() == null) {
                    q.append(e.getKey()).append(" is null");
                } else {
                    q.append(e.getKey()).append("='").append(PyStr.str(e.getValue())).append("'");
                }
            }
            String query = q.length() > 0 ? " where " + q : "";
            List<Point> soundings = new ArrayList<>();
            for (Map<String, Object> row : db.query("select * from " + name + " " + query)) {
                GeometryBase geometry = WkbReader.parseGeometry((byte[]) row.get(gcol), "soundings");
                if (!geometry.hasZ()) {
                    throw new Exception("no 3rd dimension in sounding geometry");
                }
                if (geometry instanceof MultiPointGeometry) {
                    //take this "as is" and write to the senc
                    List<FAttr> attrs = new ArrayList<>();
                    for (Map.Entry<String, Object> e : row.entrySet()) {
                        if (e.getKey().equals(gcol)) continue;
                        if (e.getValue() == null) continue;
                        attrs.add(new FAttr(e.getKey(), e.getValue()));
                    }
                    wh.addSoundings(((MultiPointGeometry) geometry).points, attrs);
                    continue;
                } else if (geometry instanceof PointGeometry) {
                    //fall through
                } else {
                    throw new Exception("unknown geometry " + geometry.getClass().getName() + " for sounding");
                }
                soundings.add(((PointGeometry) geometry).point);
                if (soundings.size() >= 100) {
                    wh.addSoundings(soundings, a2l(chunk));
                    soundings = new ArrayList<>();
                }
            }
            if (soundings.size() > 0) {
                wh.addSoundings(soundings, a2l(chunk));
            }
        }
    }

    static List<FAttr> a2l(Map<String, Object> lastAttrs) {
        List<FAttr> rt = new ArrayList<>();
        for (Map.Entry<String, Object> e : lastAttrs.entrySet()) {
            rt.add(new FAttr(e.getKey(), e.getValue()));
        }
        return rt;
    }

    public static class Options {
        public String basedir = null;
        public String s57dir = null;
        public int em = SencWriter.EM_ALL;
        public Long scale = null;
        public boolean ignoreErrors = false;
    }

    public static void convert(String iname, String oname, Options options) throws Exception {
        if (options == null) options = new Options();
        Db db = new AndroidDb(iname);
        try {
            convert(db, iname, oname, options);
        } finally {
            db.close();
        }
    }

    public static void convert(Db db, String iname, String oname, Options options) throws Exception {
        if (options == null) options = new Options();
        String s57dir = options.s57dir;
        String basedir = options.basedir;
        Long scale = options.scale;
        if (s57dir == null || !new File(s57dir).isDirectory()) {
            err("s57dir %s not found", s57dir);
        }
        S57Mappings s57mappings = new S57Mappings(s57dir);
        s57mappings.parse();
        String ibase = splitext(new File(iname).getName())[0];
        if (basedir == null) {
            basedir = new File(iname).getParent();
        }
        SencHeader header = new SencHeader(
                new Point(-179, 80),
                new Point(179, -80),
                scale == null ? 1000 : scale,
                ibase,
                1); //similar to OpenCPN default
        log("opening %s", iname);
        log("parsing geometries...");
        Extent extent = new Extent();
        for (Map<String, Object> drow : db.query("select * from geometry_columns")) {
            String table = (String) drow.get("f_table_name");
            String col = (String) drow.get("f_geometry_column");
            Object gf = drow.get("geometry_format");
            if (!"WKB".equals(gf)) {
                throw new Exception("invalid geometry format " + gf + " in " + table + ", expected WKB");
            }
            log("parsing %s...", table);
            int id = 1;
            for (Map<String, Object> vrow : db.query("select " + col + " from " + table)) {
                Object gv = vrow.get(col);
                if (gv == null) continue;
                GeometryBase geometry = WkbReader.parseGeometry((byte[]) gv, table + ":" + id);
                geometry.extend(extent);
                id += 1;
            }
        }
        log("parsed extent: %s", extent);
        header.nw = extent.nw;
        header.se = extent.se;
        boolean hasDsid = false;
        try {
            for (Map<String, Object> dsid : db.query("select * from dsid")) {
                hasDsid = true;
                Object scalev = dsid.get("dspm_cscl");
                if (scalev != null) {
                    if (!(scalev instanceof Number)) {
                        throw new Exception("invalid scale " + scalev);
                    }
                    header.scale = ((Number) scalev).longValue();
                    log("scale %d found in dsid", header.scale);
                } else {
                    warn("no scale found in dsid, using default %d", header.scale);
                }
                Object edition = dsid.get("dsid_edtn");
                if (edition != null) {
                    log("found edition %s in dsid", PyStr.str(edition));
                    int ed = PyStr.toInt(edition);
                    if (ed < 0) {
                        warn("cell edition %d < 0, setting to 0", ed);
                        ed = 0;
                    }
                    if (ed > 65535) {
                        warn("cell edition %d > 65535, setting to65535", ed);
                        ed = 65535;
                    }
                    header.edition = ed;
                } else {
                    warn("no edition found in dsid, using default %d", header.edition);
                }
                Object update = dsid.get("dsid_isdt");
                if (update != null) {
                    log("found update %s in dsid", PyStr.str(update));
                    header.update = PyStr.str(update);
                } else {
                    warn("no update date found in dsid");
                }
                Object name = dsid.get("dsid_dsnm");
                if (name != null) {
                    String cn = splitext(PyStr.str(name))[0];
                    log("found chart name %s in dsid", cn);
                    header.name = cn;
                } else {
                    warn("no chart name in dsid, using default %s", header.name);
                }
            }
        } catch (Exception e) {
            warn("Exception parsing dsid: %s", e.getMessage());
        }
        if (!hasDsid) {
            warn("no dsid record found, using defaults");
        }
        SencWriter wh = new SencWriter(s57mappings, oname, header, options.em);
        try {
            for (Map<String, Object> tablerow : db.query("SELECT f_table_name,f_geometry_column FROM geometry_columns")) {
                String table = (String) tablerow.get("f_table_name");
                log("reading table %s", table);
                if (table.equals("soundg")) {
                    writeSoundings(wh, db, table, (String) tablerow.get("f_geometry_column"));
                    continue;
                }
                S57Mappings.S57OCL objd = s57mappings.objectClasses.get(table.toUpperCase(Locale.ROOT));
                if (objd == null) {
                    warn("ignoring unknown table %s", table);
                    continue;
                }
                writeTableToSenc(wh, db, table, (String) tablerow.get("f_geometry_column"), basedir, options.ignoreErrors);
            }
        } finally {
            wh.close();
        }
        log("wrote %d features to %s", wh.featureId, oname);
    }
}
