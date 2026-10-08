package de.wellenvogel.ochartsprovider.convert;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

//edge cases in SqliteToSenc flagged during the port review:
//  - sounding 100-point chunking boundary (101 soundings -> 2 multipoint records)
//  - multipoint soundings pass through unchunked
//  - TXTDSC sidecar file copying + dedup + missing file handling
//  - dsid edition clamp (<0 -> 0, >65535 -> 65535) and dspm_cscl scale
//  - JSON column list join + INT_ATTR type override
public class SqliteToSencEdgeTest {
    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static File makeDb(Path dir, String name, List<String> stmts) throws Exception {
        File dbf = new File(dir.toFile(), name);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbf.getAbsolutePath());
             Statement st = c.createStatement()) {
            for (String s : stmts) st.execute(s);
        }
        return dbf;
    }

    static List<String> schema(String... tables) {
        List<String> rt = new ArrayList<>();
        rt.add("CREATE TABLE geometry_columns (f_table_name VARCHAR, f_geometry_column VARCHAR, geometry_format VARCHAR)");
        rt.add("CREATE TABLE dsid (dsid_dsnm TEXT, dsid_edtn INTEGER, dspm_cscl INTEGER, dsid_isdt TEXT)");
        rt.add("INSERT INTO dsid VALUES ('EDGE.000',3,45000,'20240101')");
        for (String t : tables) rt.add(t);
        for (String t : tables) {
            String name = t.split("\\s+")[2];
            rt.add("INSERT INTO geometry_columns VALUES ('" + name + "','GEOMETRY','WKB')");
        }
        return rt;
    }

    static byte[] pointZ(double lon, double lat, double z) {
        return WkbReaderTest.cat(WkbReaderTest.hdr(true, 0x80000001),
                WkbReaderTest.d(lon, true), WkbReaderTest.d(lat, true), WkbReaderTest.d(z, true));
    }

    static byte[] multipointZ(double[][] pts) {
        byte[] rt = WkbReaderTest.cat(WkbReaderTest.hdr(true, 4), WkbReaderTest.u32(pts.length, true));
        for (double[] p : pts) rt = WkbReaderTest.cat(rt, pointZ(p[0], p[1], p[2]));
        return rt;
    }

    static List<SencParser.Rec> convert(Path dir, File db) throws Exception {
        File out = new File(dir.toFile(), "out.senc");
        SqliteToSenc.Options o = new SqliteToSenc.Options();
        o.s57dir = SqliteToSencTest.s57dir().getPath();
        o.basedir = dir.toString();
        try (JdbcDb jdb = new JdbcDb(db.getAbsolutePath())) {
            SqliteToSenc.convert(jdb, db.getAbsolutePath(), out.getAbsolutePath(), o);
        }
        return SencParser.parse(Files.readAllBytes(out.toPath()));
    }

    static List<SencParser.Rec> ofType(List<SencParser.Rec> recs, int type) {
        List<SencParser.Rec> rt = new ArrayList<>();
        for (SencParser.Rec r : recs) if (r.type == type) rt.add(r);
        return rt;
    }

    //upstream chunks single-point soundings into groups of 100
    @Test
    public void soundingChunkBoundary() throws Exception {
        for (int n : new int[]{100, 101, 200}) {
            Path dir = Files.createTempDirectory("sndg" + n);
            List<String> sql = schema("CREATE TABLE soundg (GEOMETRY BLOB)");
            for (int i = 0; i < n; i++) {
                sql.add("INSERT INTO soundg VALUES (X'" + hex(pointZ(8.5 + i * 0.001, 54.1, 10.5)) + "')");
            }
            File db = makeDb(dir, "sndg.sqlite", sql);
            List<SencParser.Rec> recs = convert(dir, db);
            List<SencParser.Rec> mp = ofType(recs,
                    SencRecords.RecordTypes.FEATURE_GEOMETRY_RECORD_MULTIPOINT);
            int expected = (n + 99) / 100;
            assertEquals("n=" + n, expected, mp.size());
            int total = 0;
            for (int i = 0; i < mp.size(); i++) {
                int cnt = SencParser.soundingCount(mp.get(i));
                total += cnt;
                if (i < mp.size() - 1) assertEquals(100, cnt);
            }
            assertEquals(n, total);
            //each chunk is its own SOUNDG feature
            assertEquals(expected,
                    ofType(recs, SencRecords.RecordTypes.FEATURE_ID_RECORD).size());
        }
    }

    //multipoint soundings go straight through without chunking
    @Test
    public void soundingMultipointPassthrough() throws Exception {
        Path dir = Files.createTempDirectory("sndgmp");
        double[][] pts = new double[150][3];
        for (int i = 0; i < 150; i++) pts[i] = new double[]{8.5 + i * 0.0001, 54.1, 5.0 + i * 0.01};
        List<String> sql = schema("CREATE TABLE soundg (GEOMETRY BLOB)");
        sql.add("INSERT INTO soundg VALUES (X'" + hex(multipointZ(pts)) + "')");
        File db = makeDb(dir, "sndgmp.sqlite", sql);
        List<SencParser.Rec> mp = ofType(convert(dir, db),
                SencRecords.RecordTypes.FEATURE_GEOMETRY_RECORD_MULTIPOINT);
        assertEquals(1, mp.size());
        assertEquals(150, SencParser.soundingCount(mp.get(0)));
    }

    //TXTDSC sidecar files get embedded as CELL_TXTDSC_INFO_FILE_RECORD
    @Test
    public void txtdscCopyAndDedup() throws Exception {
        Path dir = Files.createTempDirectory("txtdsc");
        Files.write(dir.resolve("edge.txt"), "edge note content".getBytes(StandardCharsets.UTF_8));
        List<String> sql = schema("CREATE TABLE bcnlat (GEOMETRY BLOB, BCNSHP INTEGER, TXTDSC TEXT)");
        sql.add("INSERT INTO bcnlat VALUES (X'" + hex(WkbReaderTest.pointBlob(8.5, 54.1, true))
                + "',1,'edge.txt')");
        sql.add("INSERT INTO bcnlat VALUES (X'" + hex(WkbReaderTest.pointBlob(8.6, 54.2, true))
                + "',2,'edge.txt')"); //same file again -> dedup
        sql.add("INSERT INTO bcnlat VALUES (X'" + hex(WkbReaderTest.pointBlob(8.7, 54.3, true))
                + "',3,'missing.txt')"); //missing file -> warn only
        File db = makeDb(dir, "txt.sqlite", sql);
        List<SencParser.Rec> txt = ofType(convert(dir, db),
                SencRecords.RecordTypes.CELL_TXTDSC_INFO_FILE_RECORD);
        assertEquals(1, txt.size());
        ByteBuffer b = txt.get(0).body();
        long nlen = b.getInt() & 0xffffffffL;
        long tlen = b.getInt() & 0xffffffffL;
        byte[] nbuf = new byte[(int) nlen];
        b.get(nbuf);
        byte[] tbuf = new byte[(int) tlen];
        b.get(tbuf);
        assertEquals("edge.txt", new String(nbuf, 0, nbuf.length - 1, StandardCharsets.UTF_8));
        assertEquals("edge note content", new String(tbuf, 0, tbuf.length - 1, StandardCharsets.UTF_8));
    }

    //dsid edition is clamped to [0,65535], scale taken from dspm_cscl
    @Test
    public void dsidEditionClamp() throws Exception {
        for (int[] tc : new int[][]{{70000, 65535}, {-5, 0}, {7, 7}}) {
            Path dir = Files.createTempDirectory("dsid" + tc[0]);
            List<String> sql = schema("CREATE TABLE coalne (GEOMETRY BLOB, CATCOA INTEGER)");
            sql.set(2, "INSERT INTO dsid VALUES ('CLAMP.000'," + tc[0] + ",45000,'20240101')");
            sql.add("INSERT INTO coalne VALUES (X'" + hex(WkbReaderTest.cat(
                    WkbReaderTest.hdr(true, 2), WkbReaderTest.u32(2, true),
                    WkbReaderTest.d(8.5, true), WkbReaderTest.d(54.0, true),
                    WkbReaderTest.d(8.7, true), WkbReaderTest.d(54.3, true))) + "',1)");
            File db = makeDb(dir, "clamp" + tc[0] + ".sqlite", sql);
            List<SencParser.Rec> recs = convert(dir, db);
            List<SencParser.Rec> ed = ofType(recs,
                    SencRecords.RecordTypes.HEADER_CELL_EDITION);
            assertEquals(1, ed.size());
            assertEquals(tc[1], ed.get(0).body().getShort() & 0xffff);
            List<SencParser.Rec> sc = ofType(recs,
                    SencRecords.RecordTypes.HEADER_CELL_NATIVESCALE);
            assertEquals(45000, sc.get(0).body().getInt() & 0xffffffffL);
        }
    }

    //JSON typed columns: lists join with ",", scalars str(), objects repr()
    @Test
    public void jsonColumnJoin() throws Exception {
        Path dir = Files.createTempDirectory("json");
        List<String> sql = schema("CREATE TABLE bcnlat (GEOMETRY BLOB, COLOUR JSONLIST, INFORM JSONLIST)");
        sql.add("INSERT INTO bcnlat VALUES (X'" + hex(WkbReaderTest.pointBlob(8.5, 54.1, true))
                + "','[1, 2, 3]','\"x\"')"); //list join, scalar stays scalar
        sql.add("INSERT INTO bcnlat VALUES (X'" + hex(WkbReaderTest.pointBlob(8.6, 54.2, true))
                + "','[1, \"a\", 1.5]',NULL)"); //mixed list
        File db = makeDb(dir, "json.sqlite", sql);
        List<SencParser.Rec> attrs = ofType(convert(dir, db),
                SencRecords.RecordTypes.FEATURE_ATTRIBUTE_RECORD);
        List<String> vals = new ArrayList<>();
        for (SencParser.Rec r : attrs) {
            ByteBuffer b = r.body();
            int code = b.getShort() & 0xffff;
            int vt = b.get() & 0xff;
            if (vt == SencRecords.FeatureAttributeRecord.T_STR) {
                byte[] s = new byte[b.remaining()];
                b.get(s);
                vals.add(code + "=" + new String(s, 0, s.length - 1, StandardCharsets.UTF_8));
            }
        }
        //COLOUR=75: "1,2,3" and "1,a,1.5"; INFORM=102: "x"
        assertTrue(vals.toString(), vals.contains("75=1,2,3"));
        assertTrue(vals.toString(), vals.contains("75=1,a,1.5"));
        assertTrue(vals.toString(), vals.contains("102=x"));
    }

    //attrs in INT_ATTR are written as T_INT even when the s57 type is enum 'E'
    @Test
    public void intAttrOverride() throws Exception {
        assertEquals(SencRecords.FeatureAttributeRecord.T_INT,
                SencRecords.FeatureAttributeRecord.s57TypeToType("WATLEV", "E"));
        assertEquals(SencRecords.FeatureAttributeRecord.T_INT,
                SencRecords.FeatureAttributeRecord.s57TypeToType("catobs", "E"));
        assertEquals(SencRecords.FeatureAttributeRecord.T_STR,
                SencRecords.FeatureAttributeRecord.s57TypeToType("COLOUR", "L"));
        Path dir = Files.createTempDirectory("intattr");
        List<String> sql = schema("CREATE TABLE depare (GEOMETRY BLOB, WATLEV INTEGER, DRVAL1 FLOAT)");
        sql.add("INSERT INTO depare VALUES (X'" + hex(WkbReaderTest.pointBlob(8.5, 54.1, true))
                + "',2,5.5)");
        File db = makeDb(dir, "intattr.sqlite", sql);
        List<SencParser.Rec> attrs = ofType(convert(dir, db),
                SencRecords.RecordTypes.FEATURE_ATTRIBUTE_RECORD);
        //WATLEV=187 -> T_INT, DRVAL1=87 -> T_DOUBLE
        boolean sawWatlev = false, sawDrval = false;
        for (SencParser.Rec r : attrs) {
            ByteBuffer b = r.body();
            int code = b.getShort() & 0xffff;
            int vt = b.get() & 0xff;
            if (code == 187) {
                sawWatlev = true;
                assertEquals(SencRecords.FeatureAttributeRecord.T_INT, vt);
                assertEquals(2, b.getInt());
            }
            if (code == 87) {
                sawDrval = true;
                assertEquals(SencRecords.FeatureAttributeRecord.T_DOUBLE, vt);
                assertEquals(5.5, b.getDouble(), 0);
            }
        }
        assertTrue("WATLEV attr record missing", sawWatlev);
        assertTrue("DRVAL1 attr record missing", sawDrval);
    }
}
