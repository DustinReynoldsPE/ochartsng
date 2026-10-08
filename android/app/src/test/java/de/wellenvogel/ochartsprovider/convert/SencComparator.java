package de.wellenvogel.ochartsprovider.convert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

//semantic comparison of two senc files.
//compares the record sequence pairwise. Record types:
//  - FEATURE_GEOMETRY_RECORD_AREA (82): compared in up to 3 strictness levels:
//      a) byte-equal head/tail + canonical triangle set (vertex order normalized)
//         -> counted in canonCompared
//      b) if the triangle sets differ (earcut4j and mapbox_earcut legitimately
//         produce different triangulations of the same polygon): head fields
//         except vertexCount byte-equal, evid tail byte-equal, every actual
//         vertex must be a vertex of the referenced edge vectors, and the
//         covered area must match the ring area -> counted in geoCompared
//      c) if the edge vectors cannot be resolved (em=EXT, holes have no evid):
//         total triangle area must match -> counted in geoCompared
//    anything else -> diff
//  - all other record types: byte-equal -> counted in byteCompared
public class SencComparator {
    public int byteCompared = 0;
    public int canonCompared = 0;
    public int geoCompared = 0;
    public final List<String> diffs = new ArrayList<>();
    //informational: area records that needed the geometric fallback
    public final List<String> geoNotes = new ArrayList<>();

    public boolean compare(byte[] expected, byte[] actual) {
        diffs.clear();
        geoNotes.clear();
        byteCompared = 0;
        canonCompared = 0;
        geoCompared = 0;
        try {
            List<SencParser.Rec> er = SencParser.parse(expected);
            List<SencParser.Rec> ar = SencParser.parse(actual);
            Map<Integer, float[][]> evMap = SencParser.edgeVectorMap(er);
            if (er.size() != ar.size()) {
                diffs.add("record count " + ar.size() + " != expected " + er.size());
                int min = Math.min(er.size(), ar.size());
                er = er.subList(0, min);
                ar = ar.subList(0, min);
            }
            for (int i = 0; i < er.size(); i++) {
                SencParser.Rec e = er.get(i);
                SencParser.Rec a = ar.get(i);
                if (e.type != a.type) {
                    diffs.add("rec " + i + ": type " + a.type + " != expected " + e.type
                            + " (offsets " + e.offset + "/" + a.offset + ")");
                    continue;
                }
                if (e.type == SencRecords.RecordTypes.FEATURE_GEOMETRY_RECORD_AREA) {
                    compareArea(e, a, i, evMap);
                } else {
                    byteCompared++;
                    if (!Arrays.equals(e.data, a.data)) {
                        diffs.add("rec " + i + " type " + e.type + " differs (len "
                                + e.data.length + "/" + a.data.length + ", file offsets "
                                + e.offset + "/" + a.offset + ")" + firstDiff(e.data, a.data));
                    }
                }
            }
        } catch (Exception ex) {
            diffs.add("parse error: " + ex.getMessage());
        }
        return diffs.isEmpty();
    }

    private void compareArea(SencParser.Rec e, SencParser.Rec a, int idx,
                             Map<Integer, float[][]> evMap) {
        try {
            SencParser.AreaGeom ea = SencParser.parseArea(e);
            SencParser.AreaGeom aa = SencParser.parseArea(a);
            if (!Arrays.equals(ea.headPre, aa.headPre)) {
                diffs.add("area rec " + idx + ": head differs" + firstDiff(ea.headPre, aa.headPre));
                return;
            }
            if (!Arrays.equals(ea.headPost, aa.headPost)) {
                diffs.add("area rec " + idx + ": extent copy differs"
                        + firstDiff(ea.headPost, aa.headPost));
                return;
            }
            if (!Arrays.equals(ea.tail, aa.tail)) {
                diffs.add("area rec " + idx + ": edge-vector tail differs"
                        + firstDiff(ea.tail, aa.tail));
                return;
            }
            if (ea.vertexCount == aa.vertexCount
                    && sameTriangles(ea.canonicalTriangles(), aa.canonicalTriangles())) {
                canonCompared++;
                return;
            }
            //different triangulation of the same polygon - verify equivalence
            if (geometricEqual(ea, aa, evMap, idx)) {
                geoCompared++;
            }
        } catch (Exception ex) {
            diffs.add("area rec " + idx + " parse error: " + ex.getMessage());
        }
    }

    //List<long[]>.equals would compare array identity - compare contents
    static boolean sameTriangles(List<long[]> a, List<long[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    static double signedArea(float[][] ring) {
        double s = 0;
        for (int i = 0; i < ring.length - 1; i++) {
            s += (double) ring[i][0] * ring[i + 1][1] - (double) ring[i + 1][0] * ring[i][1];
        }
        return s / 2;
    }

    static double triArea(SencParser.AreaGeom g) {
        double s = 0;
        for (int i = 0; i + 2 < g.vertices.length; i += 3) {
            double x1 = g.vertices[i][0], y1 = g.vertices[i][1];
            double x2 = g.vertices[i + 1][0], y2 = g.vertices[i + 1][1];
            double x3 = g.vertices[i + 2][0], y3 = g.vertices[i + 2][1];
            s += Math.abs((x2 - x1) * (y3 - y1) - (x3 - x1) * (y2 - y1)) / 2;
        }
        return s;
    }

    private boolean geometricEqual(SencParser.AreaGeom ea, SencParser.AreaGeom aa,
                                   Map<Integer, float[][]> evMap, int idx) {
        int[] evids = ea.evids();
        List<float[][]> rings = new ArrayList<>();
        boolean resolved = true;
        for (int evid : evids) {
            float[][] pts = evMap.get(evid);
            if (pts == null) {
                resolved = false;
                break;
            }
            rings.add(pts);
        }
        double eArea = triArea(ea);
        double aArea = triArea(aa);
        if (!resolved) {
            //em=EXT writes evids for the outer ring only - fall back to
            //comparing the covered area between the two triangulations
            if (close(eArea, aArea)) {
                geoNotes.add("area rec " + idx + ": different triangulation, "
                        + "same covered area " + eArea + " (evids unresolved)");
                return true;
            }
            diffs.add("area rec " + idx + ": different triangulation and different "
                    + "covered area " + aArea + " != " + eArea);
            return false;
        }
        //every actual vertex must be a ring vertex (earcut never adds points)
        Set<Long> pool = new HashSet<>();
        for (float[][] ring : rings) {
            for (float[] p : ring) pool.add(SencParser.AreaGeom.vkey(p));
        }
        for (float[] v : aa.vertices) {
            if (!pool.contains(SencParser.AreaGeom.vkey(v))) {
                diffs.add("area rec " + idx + ": vertex (" + v[0] + "," + v[1]
                        + ") not in ring pool");
                return false;
            }
        }
        //coverage: |outer| - sum|holes| must equal the triangulated area
        double poly = Math.abs(signedArea(rings.get(0)));
        for (int i = 1; i < rings.size(); i++) poly -= Math.abs(signedArea(rings.get(i)));
        if (!close(aArea, poly)) {
            diffs.add("area rec " + idx + ": covered area " + aArea
                    + " != polygon area " + poly);
            return false;
        }
        geoNotes.add("area rec " + idx + ": different triangulation, "
                + "same covered area " + aArea + " vs " + eArea);
        return true;
    }

    private static boolean close(double a, double b) {
        double tol = Math.max(1.0, Math.abs(b) * 1e-5);
        return Math.abs(a - b) <= tol;
    }

    private String firstDiff(byte[] e, byte[] a) {
        int min = Math.min(e.length, a.length);
        for (int i = 0; i < min; i++) {
            if (e[i] != a[i]) {
                return String.format(" first diff at +%d: %02x != %02x", i, e[i] & 0xff, a[i] & 0xff);
            }
        }
        if (e.length != a.length) return " (length " + e.length + " vs " + a.length + ")";
        return "";
    }

    public String summary() {
        return byteCompared + " records byte-identical, " + canonCompared
                + " area records triangle-set compared, " + geoCompared
                + " area records geometrically compared";
    }
}
