package de.wellenvogel.ochartsprovider.convert;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import de.wellenvogel.ochartsprovider.convert.SencGeometry.GeometryBase;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.LineGeometry;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.MultiPointGeometry;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.MultiPolygonGeometry;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.Point;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.PointGeometry;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.PolygonGeometry;

public class WkbReader {
    public byte[] gv;
    public String txt;
    public int start;
    public int type;
    public int mode;
    public boolean hasZ;
    private ByteOrder order;

    public WkbReader(byte[] gv, String txt, int start) throws Exception {
        this.gv = gv;
        this.txt = txt;
        this.start = start;
        if (gv.length < 5) {
            throw new Exception("invalid geometry " + txt);
        }
        this.type = gv[0] & 0xff;
        this.start += 1;
        this.order = this.type == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        this.mode = uint();
        this.hasZ = (this.mode & 0x80000000) != 0;
        this.mode = this.mode & 0xffff;
    }

    public WkbReader(byte[] gv, String txt) throws Exception {
        this(gv, txt, 0);
    }

    public int uint() {
        int v = ByteBuffer.wrap(gv, start, 4).order(order).getInt();
        start += 4;
        return v;
    }

    public Point readGeometryPoint() {
        if (!hasZ) {
            ByteBuffer b = ByteBuffer.wrap(gv, start, 16).order(order);
            double lon = b.getDouble();
            double lat = b.getDouble();
            start += 16;
            return new Point(lon, lat);
        }
        ByteBuffer b = ByteBuffer.wrap(gv, start, 24).order(order);
        double lon = b.getDouble();
        double lat = b.getDouble();
        double z = b.getDouble();
        start += 24;
        return new Point(lon, lat, z);
    }

    public PolygonGeometry readPolygon() throws Exception {
        if (hasZ) {
            throw new Exception("3rd coordinate not supported for polygons in " + txt);
        }
        //polygons
        int numRings = uint();
        PolygonGeometry rt = new PolygonGeometry();
        if (numRings <= 0) {
            return rt;
        }
        for (int nr = 0; nr < numRings; nr++) {
            int numCoord = uint();
            List<Point> ring = new ArrayList<>();
            for (int i = 0; i < numCoord; i++) {
                ring.add(readGeometryPoint());
            }
            rt.rings.add(ring);
        }
        return rt;
    }

    public LineGeometry readLineGeometry() throws Exception {
        if (hasZ) {
            throw new Exception("3rd coordinate not supported for line strings in " + txt);
        }
        int num = uint();
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            points.add(readGeometryPoint());
        }
        return new LineGeometry(points);
    }

    public MultiPointGeometry readMultiPoint() throws Exception {
        int numPoints = uint();
        MultiPointGeometry rt = new MultiPointGeometry(new ArrayList<>());
        for (int i = 0; i < numPoints; i++) {
            WkbReader nestedReader = new WkbReader(gv, txt, start);
            if (nestedReader.mode != 1) {
                throw new Exception("invalid multi point geometry in " + txt);
            }
            rt.points.add(nestedReader.readGeometryPoint());
            start = nestedReader.start;
        }
        return rt;
    }

    public MultiPolygonGeometry readMultiPolygon() throws Exception {
        int numPoly = uint();
        MultiPolygonGeometry rt = new MultiPolygonGeometry();
        for (int i = 0; i < numPoly; i++) {
            WkbReader nestedReader = new WkbReader(gv, txt, start);
            if (nestedReader.mode != 3) {
                throw new Exception("invalid multi polygon geometry in " + txt);
            }
            rt.append(nestedReader.readPolygon());
            start = nestedReader.start;
        }
        return rt;
    }

    public GeometryBase readGeometry() throws Exception {
        if (mode == 1) {
            Point rt = readGeometryPoint();
            return new PointGeometry(rt);
        }
        if (mode == 2) {
            return readLineGeometry();
        }
        if (mode == 3) {
            return readPolygon();
        }
        if (mode == 4) {
            //multipoint
            return readMultiPoint();
        }
        if (mode == 6) {
            //multi-polygon
            return readMultiPolygon();
        }
        throw new Exception("unknown geometry " + mode + " in " + txt);
    }

    //see https://github.com/locationtech/jts/blob/master/modules/core/src/main/java/org/locationtech/jts/io/WKBReader.java
    public static GeometryBase parseGeometry(byte[] gv, String txt) throws Exception {
        WkbReader reader = new WkbReader(gv, txt);
        return reader.readGeometry();
    }
}
