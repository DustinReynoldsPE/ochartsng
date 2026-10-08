package de.wellenvogel.ochartsprovider.convert;

import java.util.ArrayList;
import java.util.List;

public class SencGeometry {
    public static class Point {
        public double lon;
        public double lat;
        public double val;
        private boolean hasZ = false;

        public Point(double lon, double lat) {
            this.lon = lon;
            this.lat = lat;
        }

        public Point(double lon, double lat, double val) {
            this(lon, lat);
            this.val = val;
            this.hasZ = true;
        }

        public boolean hasZ() {
            return hasZ;
        }

        public Point copy() {
            Point rt = new Point(lon, lat);
            rt.val = val;
            rt.hasZ = hasZ;
            return rt;
        }
    }

    public static class Extent {
        public Point nw;
        public Point se;

        public Extent() {
        }

        public Extent(Point nw, Point se) {
            this.nw = nw;
            this.se = se;
        }

        public String toString() {
            return String.format("Extent: nwlon=%f,nwlat=%f,selon=%f,selat=%f",
                    nw.lon, nw.lat, se.lon, se.lat);
        }

        public void add(Point point) {
            if (nw == null || se == null) {
                nw = point.copy();
                se = point.copy();
            } else {
                if (nw.lat < point.lat) nw.lat = point.lat;
                if (nw.lon > point.lon) nw.lon = point.lon;
                if (se.lon < point.lon) se.lon = point.lon;
                if (se.lat > point.lat) se.lat = point.lat;
            }
        }
    }

    public static class FAttr {
        public String name;
        public Object val;

        public FAttr(String name, Object val) {
            this.name = name;
            this.val = val;
        }
    }

    public static class GeometryBase {
        public void extend(Extent ext) {
        }

        public boolean hasZ() {
            return false;
        }
    }

    public static class PointGeometry extends GeometryBase {
        public Point point;

        public PointGeometry(Point point) {
            this.point = point;
        }

        public void extend(Extent ext) {
            ext.add(point);
        }

        public boolean hasZ() {
            return point.hasZ();
        }
    }

    public static class MultiPointGeometry extends GeometryBase {
        public List<Point> points;

        public MultiPointGeometry(List<Point> points) {
            this.points = points;
        }

        public void extend(Extent ext) {
            for (Point p : points) ext.add(p);
        }

        public boolean hasZ() {
            if (points == null || points.size() < 1) return false;
            return points.get(0).hasZ();
        }
    }

    public static class PolygonGeometry extends GeometryBase {
        //the first ring is the outer ring
        //others are the holes
        public List<List<Point>> rings = new ArrayList<>();

        public PolygonGeometry() {
        }

        public PolygonGeometry(List<List<Point>> rings) {
            this.rings = rings;
        }

        public void extend(Extent ext) {
            for (List<Point> ring : rings)
                for (Point p : ring) ext.add(p);
        }
    }

    public static class MultiPolygonGeometry extends GeometryBase {
        public List<List<List<Point>>> polygons = new ArrayList<>();

        public void append(PolygonGeometry polygon) {
            polygons.add(polygon.rings);
        }

        public void append(List<List<Point>> polygon) {
            polygons.add(polygon);
        }

        public void extend(Extent ext) {
            for (List<List<Point>> polygon : polygons)
                for (List<Point> ring : polygon)
                    for (Point p : ring) ext.add(p);
        }
    }

    public static class LineGeometry extends GeometryBase {
        public List<Point> points;

        public LineGeometry(List<Point> points) {
            this.points = points;
        }

        public void extend(Extent ext) {
            for (Point p : points) ext.add(p);
        }
    }

    public static class EastNorth {
        public double east;
        public double north;

        public EastNorth(double e, double n) {
            this.east = e;
            this.north = n;
        }
    }

    public static class Sounding extends EastNorth {
        public double val;

        public Sounding(double e, double n, double val) {
            super(e, n);
            this.val = val;
        }
    }
}
