package de.wellenvogel.ochartsprovider.convert;

import org.maplibre.earcut4j.Earcut;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import de.wellenvogel.ochartsprovider.convert.SencGeometry.EastNorth;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.Extent;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.FAttr;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.GeometryBase;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.LineGeometry;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.MultiPolygonGeometry;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.Point;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.PointGeometry;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.PolygonGeometry;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.Sounding;
import de.wellenvogel.ochartsprovider.convert.SencRecords.AreaGeometryRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.CellEditionRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.CellNameRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.EdgeVectorRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.ExtendRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.FeatureAttributeRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.FeatureIdRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.LineGeometryRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.NativeScaleRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.PointGeometryRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.RecordBase;
import de.wellenvogel.ochartsprovider.convert.SencRecords.SencHeader;
import de.wellenvogel.ochartsprovider.convert.SencRecords.SoundingRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.TXTRecord;
import de.wellenvogel.ochartsprovider.convert.SencRecords.VersionRecord;

public class SencWriter {
    public static final int EM_NONE = 0;
    public static final int EM_EXT = 1;
    public static final int EM_ALL = 2;

    public final String name;
    public final SencHeader header;
    public final S57Mappings s57mappings;
    public final int em;
    public Point refPoint;
    public int featureId = 1;
    //should we throw an exception if feature/attribute not found
    public boolean errNotFound = false;
    public int edgeVectorIndex = 1;
    private OutputStream wh;
    private List<String> addedTxt = new ArrayList<>();

    public SencWriter(S57Mappings s57mappings, String name, SencHeader header, int em) throws IOException {
        this(s57mappings, name, new FileOutputStream(name), header, em);
    }

    public SencWriter(S57Mappings s57mappings, String name, OutputStream out, SencHeader header, int em) throws IOException {
        this.name = name;
        this.header = header;
        this.s57mappings = s57mappings;
        this.em = em;
        this.refPoint = new Point(
                (header.nw.lon + header.se.lon) / 2,
                (header.nw.lat + header.se.lat) / 2
        );
        this.wh = out;
        new VersionRecord(header.version).write(wh);
        new CellNameRecord(header.name).write(wh);
        new CellEditionRecord(header.edition).write(wh);
        new ExtendRecord(header.nw, header.se).write(wh);
        new NativeScaleRecord(header.scale).write(wh);
    }

    private void isOpen() throws Exception {
        if (wh == null) throw new Exception("senc file " + name + " not open");
    }

    public static final double WGS84_semimajor_axis_meters = 6378137.0; // WGS84 semimajor axis
    public static final double mercator_k0 = 0.9996;
    public static final double DEGREE = Math.PI / 180.0;

    //compute easting and northing
    //see georef.cpp
    public EastNorth toSM(Point point) {
        return toSM(point, refPoint);
    }

    public EastNorth toSM(Point point, Point ref) {
        if (ref == null) ref = refPoint;
        double xlon = point.lon;

        //  Make sure lon and lon0 are same phase

        if ((point.lon * ref.lon < 0.) && (Math.abs(point.lon - ref.lon) > 180.)) {
            xlon += point.lon < 0 ? 360.0 : -360.0;
        }

        double z = WGS84_semimajor_axis_meters * mercator_k0;
        double east = (xlon - ref.lon) * DEGREE * z;

        // y =.5 ln( (1 + sin t) / (1 - sin t) )
        double s = Math.sin(point.lat * DEGREE);
        double y3 = (.5 * Math.log((1 + s) / (1 - s)));
        double s0 = Math.sin(ref.lat * DEGREE);
        double y30 = (.5 * Math.log((1 + s0) / (1 - s0)));
        double north = (y3 - y30) * z;
        return new EastNorth(east, north);
    }

    private List<RecordBase> polyRingsToRecords(List<List<Point>> rings, String txt) throws Exception {
        List<RecordBase> records = new ArrayList<>();
        List<EastNorth> enpoints = new ArrayList<>();
        Extent extent = new Extent();
        List<Integer> hidx = new ArrayList<>();
        int idx = 0;
        List<Integer> evids = new ArrayList<>();
        boolean hasPoints = false;
        for (List<Point> ring : rings) {
            Point first = ring.get(0);
            Point last = ring.get(ring.size() - 1);
            if (first.lon != last.lon || first.lat != last.lat) {
                throw new Exception("ring " + idx + " in " + txt + " not closed");
            }
            List<EastNorth> ringpoints = new ArrayList<>();
            for (Point p : ring) {
                EastNorth en = toSM(p, refPoint);
                enpoints.add(en);
                ringpoints.add(en);
                extent.add(p);
                hasPoints = true;
            }
            hidx.add(enpoints.size());
            if ((idx == 0 && em == EM_EXT) || em == EM_ALL) {
                int evid = edgeVectorIndex;
                edgeVectorIndex += 1;
                records.add(new EdgeVectorRecord(evid, ringpoints));
                evids.add(evid);
            }
            idx += 1;
        }
        //earcut triangulation
        double[] ecverts = new double[enpoints.size() * 2];
        for (int i = 0; i < enpoints.size(); i++) {
            ecverts[2 * i] = enpoints.get(i).east;
            ecverts[2 * i + 1] = enpoints.get(i).north;
        }
        int[] holes = new int[hidx.size() > 0 ? hidx.size() - 1 : 0];
        for (int i = 0; i < holes.length; i++) holes[i] = hidx.get(i);
        List<Integer> triangles = Earcut.earcut(ecverts, holes.length > 0 ? holes : null, 2);
        if (triangles.size() % 3 != 0) {
            throw new Exception("invalid triangulation result, len must be multiple of 3, is " + triangles.size());
        }
        List<EastNorth> vertexlist = new ArrayList<>();
        for (int i = 0; i < triangles.size(); i++) {
            vertexlist.add(enpoints.get(triangles.get(i)));
        }
        if (hasPoints) {
            records.add(new AreaGeometryRecord(extent, vertexlist, evids));
        }
        return records;
    }

    private List<RecordBase> buildGeometryRecords(String name, List<GeometryBase> geometries) throws Exception {
        if (geometries == null) return new ArrayList<>();
        List<RecordBase> geometryRecords = new ArrayList<>();
        for (GeometryBase geometry : geometries) {
            if (geometry instanceof PointGeometry) {
                geometryRecords.add(new PointGeometryRecord(((PointGeometry) geometry).point));
            } else if (geometry instanceof LineGeometry) {
                //1 compute extent and store points
                int idx = edgeVectorIndex;
                edgeVectorIndex += 1;
                List<EastNorth> enPoints = new ArrayList<>();
                Extent extent = new Extent();
                geometry.extend(extent);
                for (Point point : ((LineGeometry) geometry).points) {
                    enPoints.add(toSM(point));
                }
                geometryRecords.add(new LineGeometryRecord(extent, idx));
                geometryRecords.add(new EdgeVectorRecord(idx, enPoints));
            } else if (geometry instanceof PolygonGeometry) {
                geometryRecords.addAll(polyRingsToRecords(((PolygonGeometry) geometry).rings, name));
            } else if (geometry instanceof MultiPolygonGeometry) {
                for (List<List<Point>> poly : ((MultiPolygonGeometry) geometry).polygons) {
                    geometryRecords.addAll(polyRingsToRecords(poly, name));
                }
            } else {
                System.out.println("unknown geometry " + geometry.getClass().getName() + " for " + name + ", ignoring");
                continue;
            }
        }
        return geometryRecords;
    }

    public boolean addFeature(String name, List<FAttr> attributes, List<GeometryBase> geometries) throws Exception {
        isOpen();
        S57Mappings.S57OCL od = s57mappings.objByName(name);
        if (od == null) {
            if (errNotFound) throw new Exception("unknown feature " + name);
            return false;
        }
        List<RecordBase> geometryRecords = buildGeometryRecords(name, geometries);
        int fid = featureId;
        featureId += 1;
        new FeatureIdRecord(od.id(), fid, 0).write(wh);
        for (RecordBase geometryRecord : geometryRecords) {
            geometryRecord.write(wh);
        }
        for (FAttr att : attributes) {
            if (att.val == null) continue;
            S57Mappings.S57Att ad = s57mappings.attrByName(att.name);
            if (ad == null) {
                if (errNotFound)
                    throw new Exception("unkown attribute " + att.name + " for feature " + name + " id=" + fid);
                continue;
            }
            int ft = FeatureAttributeRecord.s57TypeToType(att.name, ad.type);
            new FeatureAttributeRecord(ad.id(), ft, att.val).write(wh);
        }
        return true;
    }

    public boolean addFeature(String name, List<FAttr> attributes, GeometryBase geometry) throws Exception {
        List<GeometryBase> geometries = null;
        if (geometry != null) {
            geometries = new ArrayList<>();
            geometries.add(geometry);
        }
        return addFeature(name, attributes, geometries);
    }

    public void addSoundings(List<Point> soundings, List<FAttr> attrs) throws Exception {
        addFeature("SOUNDG", attrs, (List<GeometryBase>) null);
        List<Sounding> enlist = new ArrayList<>();
        Extent extent = new Extent();
        for (Point sndg : soundings) {
            EastNorth ensndg = toSM(sndg);
            extent.add(sndg);
            enlist.add(new Sounding(ensndg.east, ensndg.north, sndg.val));
        }
        new SoundingRecord(extent, enlist).write(wh);
    }

    public boolean addTxt(String name, String txt) throws IOException {
        if (addedTxt.contains(name)) return false;
        addedTxt.add(name);
        new TXTRecord(name, txt).write(wh);
        return true;
    }

    public void close() throws IOException {
        if (wh == null) return;
        wh.close();
        wh = null;
    }
}
