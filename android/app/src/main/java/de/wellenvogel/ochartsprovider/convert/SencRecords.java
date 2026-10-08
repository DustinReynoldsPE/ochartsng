package de.wellenvogel.ochartsprovider.convert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.wellenvogel.ochartsprovider.convert.SencGeometry.EastNorth;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.Extent;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.Point;
import de.wellenvogel.ochartsprovider.convert.SencGeometry.Sounding;

public class SencRecords {
    public static class RecordTypes {
        public static final int HEADER_SENC_VERSION = 1;
        public static final int HEADER_CELL_NAME = 2;
        public static final int HEADER_CELL_PUBLISHDATE = 3;
        public static final int HEADER_CELL_EDITION = 4;
        public static final int HEADER_CELL_UPDATEDATE = 5;
        public static final int HEADER_CELL_UPDATE = 6;
        public static final int HEADER_CELL_NATIVESCALE = 7;
        public static final int HEADER_CELL_SENCCREATEDATE = 8;
        public static final int HEADER_CELL_SOUNDINGDATUM = 9;

        public static final int FEATURE_ID_RECORD = 64;
        public static final int FEATURE_ATTRIBUTE_RECORD = 65;

        public static final int FEATURE_GEOMETRY_RECORD_POINT = 80;
        public static final int FEATURE_GEOMETRY_RECORD_LINE = 81;
        public static final int FEATURE_GEOMETRY_RECORD_AREA = 82;
        public static final int FEATURE_GEOMETRY_RECORD_MULTIPOINT = 83;
        public static final int FEATURE_GEOMETRY_RECORD_AREA_EXT = 84;

        public static final int VECTOR_EDGE_NODE_TABLE_EXT_RECORD = 85;
        public static final int VECTOR_CONNECTED_NODE_TABLE_EXT_RECORD = 86;

        public static final int VECTOR_EDGE_NODE_TABLE_RECORD = 96;
        public static final int VECTOR_CONNECTED_NODE_TABLE_RECORD = 97;

        public static final int CELL_COVR_RECORD = 98;
        public static final int CELL_NOCOVR_RECORD = 99;
        public static final int CELL_EXTENT_RECORD = 100;
        public static final int CELL_TXTDSC_INFO_FILE_RECORD = 101;

        public static final int SERVER_STATUS_RECORD = 200;
    }

    public static class RecordBase {
        byte[] buffer;
        int len = 0;
        public int rtype;

        public RecordBase(int rtype) {
            this.rtype = rtype;
            buffer = new byte[64];
            appendUint16(rtype);
            appendUint32(0);
        }

        private void ensure(int n) {
            if (len + n > buffer.length) {
                int nl = buffer.length * 2;
                while (nl < len + n) nl *= 2;
                buffer = Arrays.copyOf(buffer, nl);
            }
        }

        public int getLength() {
            return len;
        }

        public void updateLength() {
            int l = len;
            buffer[2] = (byte) (l & 0xff);
            buffer[3] = (byte) ((l >> 8) & 0xff);
            buffer[4] = (byte) ((l >> 16) & 0xff);
            buffer[5] = (byte) ((l >> 24) & 0xff);
        }

        public void append(byte[] bytes) {
            append(bytes, 0, bytes.length);
        }

        public void append(byte[] bytes, int off, int l) {
            ensure(l);
            System.arraycopy(bytes, off, buffer, len, l);
            len += l;
        }

        public void appendUint8(int val) {
            ensure(1);
            buffer[len++] = (byte) (val & 0xff);
        }

        public void appendUint16(int val) {
            ensure(2);
            buffer[len++] = (byte) (val & 0xff);
            buffer[len++] = (byte) ((val >> 8) & 0xff);
        }

        public void appendUint32(long val) {
            ensure(4);
            buffer[len++] = (byte) (val & 0xff);
            buffer[len++] = (byte) ((val >> 8) & 0xff);
            buffer[len++] = (byte) ((val >> 16) & 0xff);
            buffer[len++] = (byte) ((val >> 24) & 0xff);
        }

        public void appendInt32(int val) {
            appendUint32(val & 0xffffffffL);
        }

        public void appendStr(Object val) {
            byte[] bytes;
            if (val instanceof byte[]) {
                bytes = (byte[]) val;
            } else if (val instanceof String) {
                bytes = utf8Ignore((String) val);
            } else {
                bytes = utf8Ignore(PyStr.str(val));
            }
            append(bytes);
            ensure(1);
            buffer[len++] = 0;
        }

        public void appendDouble(double val) {
            append(longBytes(Double.doubleToRawLongBits(val)));
        }

        public void appendFloat(double val) {
            append(intBytes(Float.floatToRawIntBits((float) val)));
        }

        public void appendExt(Extent extent) {
            appendDouble(extent.se.lat);
            appendDouble(extent.nw.lat);
            appendDouble(extent.nw.lon);
            appendDouble(extent.se.lon);
        }

        public void write(OutputStream stream) throws IOException {
            updateLength();
            stream.write(buffer, 0, len);
        }

        public byte[] toBytes() {
            updateLength();
            return Arrays.copyOf(buffer, len);
        }

        static byte[] utf8Ignore(String s) {
            ByteBuffer bb;
            try {
                bb = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.IGNORE)
                        .onUnmappableCharacter(CodingErrorAction.IGNORE)
                        .encode(java.nio.CharBuffer.wrap(s));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            byte[] rt = new byte[bb.remaining()];
            bb.get(rt);
            return rt;
        }

        static byte[] longBytes(long val) {
            byte[] rt = new byte[8];
            ByteBuffer.wrap(rt).order(ByteOrder.LITTLE_ENDIAN).putLong(val);
            return rt;
        }

        static byte[] intBytes(int val) {
            byte[] rt = new byte[4];
            ByteBuffer.wrap(rt).order(ByteOrder.LITTLE_ENDIAN).putInt(val);
            return rt;
        }
    }

    public static class VersionRecord extends RecordBase {
        public VersionRecord(int version) {
            super(RecordTypes.HEADER_SENC_VERSION);
            appendUint16(version);
        }

        public VersionRecord() {
            this(201);
        }
    }

    public static class CellEditionRecord extends RecordBase {
        public CellEditionRecord(int val) {
            super(RecordTypes.HEADER_CELL_EDITION);
            appendUint16(val);
        }
    }

    public static class NativeScaleRecord extends RecordBase {
        public NativeScaleRecord(long scale) {
            super(RecordTypes.HEADER_CELL_NATIVESCALE);
            appendUint32(scale);
        }
    }

    public static class StringRecord extends RecordBase {
        public StringRecord(int rtype, Object val) {
            super(rtype);
            appendStr(val);
        }
    }

    public static class CellNameRecord extends StringRecord {
        public CellNameRecord(String val) {
            super(RecordTypes.HEADER_CELL_NAME, val);
        }
    }

    public static class ExtendRecord extends RecordBase {
        public ExtendRecord(Point nw, Point se, Point ne, Point sw) {
            super(RecordTypes.CELL_EXTENT_RECORD);
            if (ne == null) ne = new Point(se.lon, nw.lat); //ignored any way
            if (sw == null) sw = new Point(nw.lon, se.lat);
            appendDouble(sw.lat);
            appendDouble(sw.lon);
            appendDouble(nw.lat);
            appendDouble(nw.lon);
            appendDouble(ne.lat);
            appendDouble(ne.lon);
            appendDouble(se.lat);
            appendDouble(se.lon);
        }

        public ExtendRecord(Point nw, Point se) {
            this(nw, se, null, null);
        }
    }

    public static class FeatureIdRecord extends RecordBase {
        public FeatureIdRecord(int typeCode, int id, int primitive) {
            super(RecordTypes.FEATURE_ID_RECORD);
            appendUint16(typeCode);
            appendUint16(id);
            appendUint8(primitive);
        }
    }

    //always ensure correct order: 1. FeatureIdRecord, 2.Attr/Geom
    public static class PointGeometryRecord extends RecordBase {
        public PointGeometryRecord(Point point) {
            super(RecordTypes.FEATURE_GEOMETRY_RECORD_POINT);
            appendDouble(point.lat);
            appendDouble(point.lon);
        }
    }

    public static class LineGeometryRecord extends RecordBase {
        public LineGeometryRecord(Extent extent, long evid) {
            super(RecordTypes.FEATURE_GEOMETRY_RECORD_LINE);
            appendExt(extent);
            appendUint32(1); //for now only one edge vector
            appendInt32(-1); //first connected node
            appendInt32((int) evid); //edge vector index
            appendInt32(-1); //end node
            appendInt32(0); //always forward
        }
    }

    public static class EdgeVectorRecord extends RecordBase {
        public EdgeVectorRecord(long idx, List<? extends EastNorth> enlist) {
            super(RecordTypes.VECTOR_EDGE_NODE_TABLE_RECORD);
            appendUint32(1); //only one entry included
            appendUint32(idx); //table index
            appendUint32(enlist.size()); //num entries
            for (EastNorth en : enlist) {
                appendFloat(en.east);
                appendFloat(en.north);
            }
        }
    }

    public static class SoundingRecord extends RecordBase {
        public SoundingRecord(Extent extent, List<Sounding> soundings) {
            super(RecordTypes.FEATURE_GEOMETRY_RECORD_MULTIPOINT);
            appendExt(extent);
            appendUint32(soundings.size());
            for (Sounding sndg : soundings) {
                appendFloat(sndg.east);
                appendFloat(sndg.north);
                appendFloat(sndg.val);
            }
        }
    }

    public static class AreaGeometryRecord extends RecordBase {
        public AreaGeometryRecord(Extent extent, List<? extends EastNorth> vertices, List<Integer> evids) throws Exception {
            super(RecordTypes.FEATURE_GEOMETRY_RECORD_AREA);
            if (vertices.size() % 3 != 0) {
                throw new Exception("invalid vertex list, len " + vertices.size());
            }
            appendExt(extent);
            appendUint32(0); //contour count that is skipped any way
            appendUint32(1); //number of vertex lists
            appendUint32(evids.size()); //number of edge vectors
            appendUint8(4); //vertex type PTG - see S57Object::RenderObject::RenderSingleRule
            appendInt32(vertices.size());
            appendExt(extent); //is not read any way...
            for (EastNorth vert : vertices) {
                appendFloat(vert.east);
                appendFloat(vert.north);
            }
            for (int evid : evids) {
                appendInt32(-1); //first connected node
                appendInt32(evid); //edge vector index
                appendInt32(-1); //end node
                appendInt32(0); //always forward
            }
        }
    }

    public static class FeatureAttributeRecord extends RecordBase {
        public static final int T_INT = 0;
        public static final int T_DOUBLE = 2;
        public static final int T_STR = 4;
        //some attrs that are expected to be int's in the code
        //even if they are "E" in the attribute type
        public static final Set<String> INT_ATTR = new HashSet<>(Arrays.asList(
                "QUALTY", "CATOBS", "WATLEV",
                "CATWRK", "QUAPOS", "LITCHR",
                "CONRAD", "CONDTN", "CATSLC",
                "TOPSHP"));

        public static int s57TypeToType(String name, String s57t) {
            if (INT_ATTR.contains(name.toUpperCase(java.util.Locale.ROOT))) return T_INT;
            if ("I".equals(s57t)) return T_INT;
            if ("F".equals(s57t)) return T_DOUBLE;
            return T_STR;
        }

        public FeatureAttributeRecord(int typeCode, int valueType, Object value) throws Exception {
            super(RecordTypes.FEATURE_ATTRIBUTE_RECORD);
            appendUint16(typeCode);
            appendUint8(valueType);
            if (valueType == T_INT) {
                appendUint32(PyStr.toInt(value) & 0xffffffffL);
            } else if (valueType == T_DOUBLE) {
                appendDouble(PyStr.toDouble(value));
            } else if (valueType == T_STR) {
                appendStr(value);
            } else {
                throw new Exception("unknown value type " + valueType + " for feature " + typeCode);
            }
        }
    }

    public static class TXTRecord extends RecordBase {
        public TXTRecord(String name, String txt) {
            super(RecordTypes.CELL_TXTDSC_INFO_FILE_RECORD);
            int nlen = name.codePointCount(0, name.length()) + 1;
            int tlen = txt.codePointCount(0, txt.length()) + 1;
            appendUint32(nlen);
            appendUint32(tlen);
            appendStr(name);
            appendStr(txt);
        }
    }

    public static class SencHeader {
        public Point nw;
        public Point se;
        public long scale;
        public String name;
        public int edition;
        public int version;
        public String update = null;

        public SencHeader(Point nw, Point se, long scale, String name, int edition, int version) {
            this.nw = nw;
            this.se = se;
            this.scale = scale;
            this.name = name;
            this.edition = edition;
            this.version = version;
        }

        public SencHeader(Point nw, Point se, long scale, String name, int edition) {
            this(nw, se, scale, name, edition, 201);
        }
    }
}
