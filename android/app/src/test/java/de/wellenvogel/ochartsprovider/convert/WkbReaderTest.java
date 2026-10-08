package de.wellenvogel.ochartsprovider.convert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import de.wellenvogel.ochartsprovider.convert.SencGeometry.GeometryBase;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.LineGeometry;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.MultiPointGeometry;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.MultiPolygonGeometry;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.PointGeometry;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.PolygonGeometry;

public class WkbReaderTest {
    static byte[] u32(long v, boolean le) {
        return ByteBuffer.allocate(4).order(le ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).putInt((int) v).array();
    }

    static byte[] d(double v, boolean le) {
        return ByteBuffer.allocate(8).order(le ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).putDouble(v).array();
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static byte[] hdr(boolean le, int mode) {
        return cat(new byte[]{(byte) (le ? 1 : 0)}, u32(mode, le));
    }

    static byte[] pointBlob(double lon, double lat, boolean le) {
        return cat(hdr(le, 1), d(lon, le), d(lat, le));
    }

    static byte[] pointBlobZ(double lon, double lat, double z, boolean le) {
        return cat(hdr(le, 0x80000001), d(lon, le), d(lat, le), d(z, le));
    }

    @Test
    public void pointLE() throws Exception {
        GeometryBase g = WkbReader.parseGeometry(pointBlob(8.5, 54.1, true), "t");
        assertTrue(g instanceof PointGeometry);
        SencGeometry.Point p = ((PointGeometry) g).point;
        assertEquals(8.5, p.lon, 0);
        assertEquals(54.1, p.lat, 0);
        assertFalse(p.hasZ());
    }

    @Test
    public void pointBE() throws Exception {
        GeometryBase g = WkbReader.parseGeometry(pointBlob(-1.25, 30.5, false), "t");
        assertTrue(g instanceof PointGeometry);
        assertEquals(-1.25, ((PointGeometry) g).point.lon, 0);
        assertEquals(30.5, ((PointGeometry) g).point.lat, 0);
    }

    @Test
    public void pointZ() throws Exception {
        GeometryBase g = WkbReader.parseGeometry(pointBlobZ(8.5, 54.1, 12.3, true), "t");
        assertTrue(g instanceof PointGeometry);
        assertTrue(((PointGeometry) g).point.hasZ());
        assertEquals(12.3, ((PointGeometry) g).point.val, 0);
    }

    @Test
    public void lineString() throws Exception {
        byte[] blob = cat(hdr(true, 2), u32(3, true),
                d(1.0, true), d(2.0, true),
                d(3.0, true), d(4.0, true),
                d(5.0, true), d(6.0, true));
        GeometryBase g = WkbReader.parseGeometry(blob, "t");
        assertTrue(g instanceof LineGeometry);
        assertEquals(3, ((LineGeometry) g).points.size());
        assertEquals(5.0, ((LineGeometry) g).points.get(2).lon, 0);
    }

    @Test
    public void lineStringZRejected() {
        byte[] blob = cat(hdr(true, 0x80000002), u32(1, true), d(1.0, true), d(2.0, true), d(3.0, true));
        Exception e = assertThrows(Exception.class, () -> WkbReader.parseGeometry(blob, "t"));
        assertTrue(e.getMessage().contains("3rd coordinate not supported for line strings"));
    }

    @Test
    public void polygon() throws Exception {
        byte[] ring = cat(u32(4, true),
                d(0.0, true), d(0.0, true),
                d(1.0, true), d(0.0, true),
                d(1.0, true), d(1.0, true),
                d(0.0, true), d(0.0, true));
        byte[] blob = cat(hdr(true, 3), u32(1, true), ring);
        GeometryBase g = WkbReader.parseGeometry(blob, "t");
        assertTrue(g instanceof PolygonGeometry);
        assertEquals(1, ((PolygonGeometry) g).rings.size());
        assertEquals(4, ((PolygonGeometry) g).rings.get(0).size());
    }

    @Test
    public void polygonZRejected() {
        byte[] blob = cat(hdr(true, 0x80000003), u32(1, true), u32(1, true), d(0, true), d(0, true), d(0, true));
        Exception e = assertThrows(Exception.class, () -> WkbReader.parseGeometry(blob, "t"));
        assertTrue(e.getMessage().contains("3rd coordinate not supported for polygons"));
    }

    @Test
    public void multiPoint() throws Exception {
        byte[] blob = cat(hdr(true, 4), u32(2, true),
                pointBlobZ(1.0, 2.0, 3.0, true),
                pointBlobZ(4.0, 5.0, 6.0, true));
        GeometryBase g = WkbReader.parseGeometry(blob, "t");
        assertTrue(g instanceof MultiPointGeometry);
        assertEquals(2, ((MultiPointGeometry) g).points.size());
        assertEquals(6.0, ((MultiPointGeometry) g).points.get(1).val, 0);
    }

    @Test
    public void multiPointBadNested() {
        byte[] blob = cat(hdr(true, 4), u32(1, true), hdr(true, 2), u32(0, true));
        Exception e = assertThrows(Exception.class, () -> WkbReader.parseGeometry(blob, "t"));
        assertTrue(e.getMessage().contains("invalid multi point geometry"));
    }

    @Test
    public void multiPolygon() throws Exception {
        byte[] ring = cat(u32(4, true),
                d(0.0, true), d(0.0, true),
                d(1.0, true), d(0.0, true),
                d(1.0, true), d(1.0, true),
                d(0.0, true), d(0.0, true));
        byte[] poly = cat(hdr(true, 3), u32(1, true), ring);
        byte[] blob = cat(hdr(true, 6), u32(2, true), poly, poly);
        GeometryBase g = WkbReader.parseGeometry(blob, "t");
        assertTrue(g instanceof MultiPolygonGeometry);
        assertEquals(2, ((MultiPolygonGeometry) g).polygons.size());
        assertEquals(1, ((MultiPolygonGeometry) g).polygons.get(0).size());
    }

    @Test
    public void multiPolygonBadNested() {
        byte[] blob = cat(hdr(true, 6), u32(1, true), pointBlob(1.0, 2.0, true));
        Exception e = assertThrows(Exception.class, () -> WkbReader.parseGeometry(blob, "t"));
        assertTrue(e.getMessage().contains("invalid multi polygon geometry"));
    }

    @Test
    public void unknownMode() {
        byte[] blob = hdr(true, 7);
        Exception e = assertThrows(Exception.class, () -> WkbReader.parseGeometry(blob, "t"));
        assertTrue(e.getMessage().contains("unknown geometry 7"));
    }

    @Test
    public void tooShort() {
        Exception e = assertThrows(Exception.class, () -> WkbReader.parseGeometry(new byte[]{1, 0, 0}, "t"));
        assertTrue(e.getMessage().contains("invalid geometry"));
    }
}
