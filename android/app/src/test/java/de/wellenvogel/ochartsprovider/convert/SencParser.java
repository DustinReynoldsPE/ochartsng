package de.wellenvogel.ochartsprovider.convert;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

//walks a .senc file into its records.
//record layout (little endian): u16 type, u32 length (including the 6 byte header), body.
//mirrors the writers in SencRecords.java / tools/senc/senc.py.
public class SencParser {
    public static class Rec {
        public final int type;
        public final int offset; //offset of the record in the file
        public final byte[] data; //the full record incl. 6 byte header
        public final int index; //position in the record sequence

        Rec(int type, int offset, byte[] data, int index) {
            this.type = type;
            this.offset = offset;
            this.data = data;
            this.index = index;
        }

        public int length() {
            return data.length;
        }

        //body = record bytes after the 6 byte header
        public ByteBuffer body() {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            b.position(6);
            return b.slice().order(ByteOrder.LITTLE_ENDIAN);
        }

        public String toString() {
            return "rec[" + index + "] type=" + type + " len=" + data.length + " @" + offset;
        }
    }

    public static List<Rec> parse(byte[] file) throws Exception {
        List<Rec> rt = new ArrayList<>();
        int pos = 0;
        int idx = 0;
        while (pos < file.length) {
            if (pos + 6 > file.length) throw new Exception("truncated record header at " + pos);
            ByteBuffer h = ByteBuffer.wrap(file, pos, 6).order(ByteOrder.LITTLE_ENDIAN);
            int type = h.getShort() & 0xffff;
            long len = h.getInt() & 0xffffffffL;
            if (len < 6 || pos + len > file.length) {
                throw new Exception("invalid record length " + len + " at " + pos);
            }
            rt.add(new Rec(type, pos, Arrays.copyOfRange(file, pos, pos + (int) len), idx));
            pos += len;
            idx++;
        }
        return rt;
    }

    //decoded FEATURE_GEOMETRY_RECORD_AREA (type 82) record:
    //  body: 32B extent | u32 contourCount | u32 numVertexLists | u32 numEdgeVectors
    //        | u8 vertexType | i32 vertexCount | 32B extent
    //        | vertexCount * (f32 east,f32 north) | numEdgeVectors * 4*i32
    public static class AreaGeom {
        public final byte[] headPre; //body[0..45): extent, contour count, numVertexLists,
                                     //numEdgeVectors, vertexType
        public final byte[] headPost; //body[49..77): second extent copy
        public final byte[] tail; //everything after the vertex array (edge vector table)
        public final int vertexCount;
        public final float[][] vertices; //[n][0]=east [n][1]=north

        AreaGeom(byte[] headPre, byte[] headPost, byte[] tail, float[][] vertices) {
            this.headPre = headPre;
            this.headPost = headPost;
            this.tail = tail;
            this.vertices = vertices;
            this.vertexCount = vertices.length;
        }

        //the edge vector ids referenced by the tail (each: 4*i32, id at +4)
        public int[] evids() {
            int[] rt = new int[tail.length / 16];
            ByteBuffer b = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < rt.length; i++) {
                b.position(i * 16 + 4);
                rt[i] = b.getInt();
            }
            return rt;
        }

        //canonical triangle set: each triangle normalized to sorted vertex order,
        //then all triangles sorted - vertex order differences between
        //mapbox_earcut and earcut4j produce identical canonical sets
        public List<long[]> canonicalTriangles() {
            List<long[]> tris = new ArrayList<>();
            for (int i = 0; i + 2 < vertices.length; i += 3) {
                long[] tri = new long[]{vkey(vertices[i]), vkey(vertices[i + 1]), vkey(vertices[i + 2])};
                Arrays.sort(tri);
                tris.add(tri);
            }
            tris.sort((a, b) -> {
                for (int i = 0; i < 3; i++) {
                    if (a[i] != b[i]) return Long.compare(a[i], b[i]);
                }
                return 0;
            });
            return tris;
        }

        static long vkey(float[] v) {
            long e = Float.floatToRawIntBits(v[0]) & 0xffffffffL;
            long n = Float.floatToRawIntBits(v[1]) & 0xffffffffL;
            return (e << 32) | n;
        }
    }

    public static AreaGeom parseArea(Rec r) throws Exception {
        if (r.type != SencRecords.RecordTypes.FEATURE_GEOMETRY_RECORD_AREA) {
            throw new Exception("not an area record: " + r);
        }
        ByteBuffer b = r.body();
        int headLen = 32 + 4 + 4 + 4 + 1 + 4 + 32;
        byte[] head = new byte[headLen];
        b.get(head);
        //vertex count sits at offset 32+4+4+4+1 = 45 within head;
        //it may differ between triangulation implementations -> kept out of headPre/headPost
        ByteBuffer hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int vertexCount = hb.getInt(45);
        int numEdgeVectors = (int) (hb.getInt(40) & 0xffffffffL);
        if (vertexCount < 0 || vertexCount % 3 != 0) {
            throw new Exception("invalid vertex count " + vertexCount + " in " + r);
        }
        float[][] verts = new float[vertexCount][2];
        for (int i = 0; i < vertexCount; i++) {
            verts[i][0] = b.getFloat();
            verts[i][1] = b.getFloat();
        }
        byte[] tail = new byte[b.remaining()];
        b.get(tail);
        if (tail.length != numEdgeVectors * 16) {
            throw new Exception("area tail length " + tail.length + " != evids*16=" + (numEdgeVectors * 16) + " in " + r);
        }
        return new AreaGeom(Arrays.copyOfRange(head, 0, 45),
                Arrays.copyOfRange(head, 49, headLen), tail, verts);
    }

    //VECTOR_EDGE_NODE_TABLE_RECORD (type 96):
    //  body: u32 count | u32 tableIndex | u32 numEntries | numEntries * (f32 east,f32 north)
    public static class EdgeVector {
        public final int index;
        public final float[][] points;

        EdgeVector(int index, float[][] points) {
            this.index = index;
            this.points = points;
        }
    }

    public static EdgeVector parseEdgeVector(Rec r) throws Exception {
        if (r.type != SencRecords.RecordTypes.VECTOR_EDGE_NODE_TABLE_RECORD) {
            throw new Exception("not an edge vector record: " + r);
        }
        ByteBuffer b = r.body();
        b.getInt(); //count, always 1
        int idx = b.getInt();
        int n = b.getInt();
        float[][] pts = new float[n][2];
        for (int i = 0; i < n; i++) {
            pts[i][0] = b.getFloat();
            pts[i][1] = b.getFloat();
        }
        return new EdgeVector(idx, pts);
    }

    public static java.util.Map<Integer, float[][]> edgeVectorMap(List<Rec> recs) throws Exception {
        java.util.Map<Integer, float[][]> rt = new java.util.HashMap<>();
        for (Rec r : recs) {
            if (r.type == SencRecords.RecordTypes.VECTOR_EDGE_NODE_TABLE_RECORD) {
                EdgeVector ev = parseEdgeVector(r);
                rt.put(ev.index, ev.points);
            }
        }
        return rt;
    }

    //decoded FEATURE_GEOMETRY_RECORD_MULTIPOINT (type 83) record:
    //  body: 32B extent | u32 count | count * (f32 east,f32 north,f32 depth)
    public static int soundingCount(Rec r) throws Exception {
        if (r.type != SencRecords.RecordTypes.FEATURE_GEOMETRY_RECORD_MULTIPOINT) {
            throw new Exception("not a multipoint record: " + r);
        }
        ByteBuffer b = r.body();
        b.position(32);
        long n = b.getInt() & 0xffffffffL;
        if (b.remaining() != n * 12) {
            throw new Exception("multipoint body mismatch: count " + n + " remaining " + b.remaining());
        }
        return (int) n;
    }
}
