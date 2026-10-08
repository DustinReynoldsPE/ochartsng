package de.wellenvogel.ochartsprovider.convert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import de.wellenvogel.ochartsprovider.convert.SencGeometry.EastNorth;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.Extent;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.Point;
import de.wellenvogel.ochartsprovider.convert.SencRecords.CellNameRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.ExtendRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.FeatureAttributeRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.FeatureIdRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.NativeScaleRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.SencHeader;
import de.wellenvogel.ochartsprovider.convert.SencRecords.TXTRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.VersionRecord;

public class SencWriterTest {
    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    //expected values generated with upstream tools/senc/senc.py (python struct.pack)
    @Test
    public void versionRecord() throws Exception {
        assertEquals("010008000000c900", hex(new VersionRecord(201).toBytes()));
    }

    @Test
    public void cellNameRecord() throws Exception {
        assertEquals("02000a00000061626300", hex(new CellNameRecord("abc").toBytes()));
    }

    @Test
    public void nativeScaleRecord() throws Exception {
        assertEquals("07000a000000c8af0000", hex(new NativeScaleRecord(45000).toBytes()));
    }

    @Test
    public void featureIdRecord() throws Exception {
        assertEquals("40000b00000007002a0000", hex(new FeatureIdRecord(7, 42, 0).toBytes()));
    }

    @Test
    public void featureAttributeRecords() throws Exception {
        assertEquals("41000d0000000d000007000000",
                hex(new FeatureAttributeRecord(13, FeatureAttributeRecord.T_INT, 7).toBytes()));
        assertEquals("4100110000000d0002000000000000f83f",
                hex(new FeatureAttributeRecord(13, FeatureAttributeRecord.T_DOUBLE, 1.5).toBytes()));
        assertEquals("41000c0000000d0004787900",
                hex(new FeatureAttributeRecord(13, FeatureAttributeRecord.T_STR, "xy").toBytes()));
    }

    @Test
    public void txtRecord() throws Exception {
        assertEquals("65001a0000000600000006000000612e7478740068656c6c6f00",
                hex(new TXTRecord("a.txt", "hello").toBytes()));
    }

    @Test
    public void extendRecord() throws Exception {
        assertEquals("64004600000000000000000010c0000000000000f0bf0000000000000040000000000000f0bf0000000000000040000000000000084000000000000010c00000000000000840",
                hex(new ExtendRecord(new Point(-1.0, 2.0), new Point(3.0, -4.0)).toBytes()));
    }

    static List<int[]> parseRecords(byte[] data) {
        List<int[]> rt = new ArrayList<>();
        int pos = 0;
        while (pos < data.length) {
            int type = (data[pos] & 0xff) | ((data[pos + 1] & 0xff) << 8);
            int len = (data[pos + 2] & 0xff) | ((data[pos + 3] & 0xff) << 8)
                    | ((data[pos + 4] & 0xff) << 16) | ((data[pos + 5] & 0xff) << 24);
            rt.add(new int[]{type, len, pos});
            pos += len;
        }
        assertEquals(data.length, pos);
        return rt;
    }

    static SencWriter writer(ByteArrayOutputStream out) throws Exception {
        return new SencWriter(new S57Mappings((String) null), "t", out,
                new SencHeader(new Point(-1.0, 2.0), new Point(3.0, -4.0), 45000, "TEST", 1),
                SencWriter.EM_ALL);
    }

    @Test
    public void headerOrder() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SencWriter w = writer(out);
        List<int[]> recs = parseRecords(out.toByteArray());
        int[] types = {1, 2, 4, 100, 7};
        assertEquals(types.length, recs.size());
        for (int i = 0; i < types.length; i++) assertEquals(types[i], recs.get(i)[0]);
    }

    //_toSM reference values computed with upstream senc.py
    @Test
    public void toSM() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SencWriter w = writer(out);
        EastNorth en = w.toSM(new Point(1.0, 1.0), new Point(0.0, 0.0));
        assertEquals(111274.96299695627, en.east, 1e-6);
        assertEquals(111280.61280923811, en.north, 1e-6);
        en = w.toSM(new Point(-5.5, 52.3), new Point(0.0, 0.0));
        assertEquals(-612012.2964832594, en.east, 1e-6);
        assertEquals(6851810.311875737, en.north, 1e-4);
        en = w.toSM(new Point(11.5, 51.5), new Point(10.0, 50.0));
        assertEquals(166912.4444954344, en.east, 1e-6);
        assertEquals(263837.66490670043, en.north, 1e-6);
    }

    @Test
    public void extentAdd() {
        Extent e = new Extent();
        e.add(new Point(1.0, 2.0));
        e.add(new Point(-3.0, 5.0));
        e.add(new Point(7.0, -1.0));
        assertEquals(-3.0, e.nw.lon, 0);
        assertEquals(5.0, e.nw.lat, 0);
        assertEquals(7.0, e.se.lon, 0);
        assertEquals(-1.0, e.se.lat, 0);
    }
}
