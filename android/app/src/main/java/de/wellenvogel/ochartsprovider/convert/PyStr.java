package de.wellenvogel.ochartsprovider.convert;

import java.util.List;
import java.util.Map;

//python str()/repr() equivalents for values coming from sqlite/json
public class PyStr {
    public static String str(Object v) {
        if (v == null) return "None";
        if (v instanceof String) return (String) v;
        if (v instanceof Boolean) return ((Boolean) v) ? "True" : "False";
        if (v instanceof Double || v instanceof Float) return floatRepr(((Number) v).doubleValue());
        if (v instanceof Number) return v.toString();
        if (v instanceof byte[]) return bytesRepr((byte[]) v);
        if (v instanceof List || v instanceof Map) return repr(v);
        return String.valueOf(v);
    }

    public static String repr(Object v) {
        if (v instanceof String) {
            String s = (String) v;
            if (s.indexOf('\'') < 0 || s.indexOf('"') >= 0) {
                return "'" + escape(s, '\'') + "'";
            }
            return "\"" + escape(s, '"') + "\"";
        }
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(repr(o));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(repr(e.getKey())).append(": ").append(repr(e.getValue()));
            }
            return sb.append('}').toString();
        }
        return str(v);
    }

    private static String escape(String s, char quote) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == quote) sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String bytesRepr(byte[] b) {
        StringBuilder sb = new StringBuilder("b'");
        for (byte x : b) {
            int c = x & 0xff;
            if (c == '\'' || c == '\\') sb.append('\\').append((char) c);
            else if (c >= 32 && c < 127) sb.append((char) c);
            else sb.append(String.format("\\x%02x", c));
        }
        return sb.append('\'').toString();
    }

    //CPython float repr: shortest digits, fixed if -4 < decpt <= 16
    public static String floatRepr(double v) {
        if (Double.isNaN(v)) return "nan";
        if (Double.isInfinite(v)) return v > 0 ? "inf" : "-inf";
        if (v == 0.0) {
            return Double.doubleToRawLongBits(v) < 0 ? "-0.0" : "0.0";
        }
        String s = Double.toString(v);
        String sign = "";
        if (s.startsWith("-")) {
            sign = "-";
            s = s.substring(1);
        }
        int epos = s.indexOf('E');
        String digits;
        long decpt;
        if (epos >= 0) {
            String mant = s.substring(0, epos);
            int e = Integer.parseInt(s.substring(epos + 1));
            int dot = mant.indexOf('.');
            String intp = dot < 0 ? mant : mant.substring(0, dot);
            String frac = dot < 0 ? "" : mant.substring(dot + 1);
            digits = intp + frac;
            decpt = e - frac.length() + digits.length();
        } else {
            int dot = s.indexOf('.');
            String intp = dot < 0 ? s : s.substring(0, dot);
            String frac = dot < 0 ? "" : s.substring(dot + 1);
            digits = intp + frac;
            decpt = digits.length() - frac.length();
        }
        //value = 0.digits * 10^decpt; normalize digits: strip leading zeros
        int lead = 0;
        while (lead < digits.length() && digits.charAt(lead) == '0') lead++;
        digits = digits.substring(lead);
        decpt -= lead;
        while (digits.length() > 1 && digits.endsWith("0")) digits = digits.substring(0, digits.length() - 1);
        if (decpt <= -4 || decpt > 16) {
            StringBuilder m = new StringBuilder();
            m.append(digits.charAt(0));
            if (digits.length() > 1) m.append('.').append(digits.substring(1));
            long e = decpt - 1;
            String es = e < 0 ? "-" : "+";
            long ea = Math.abs(e);
            String estr = ea < 10 ? "0" + ea : String.valueOf(ea);
            return sign + m + "e" + es + estr;
        }
        StringBuilder rt = new StringBuilder(sign);
        if (decpt <= 0) {
            rt.append("0.");
            for (long i = 0; i < -decpt; i++) rt.append('0');
            rt.append(digits);
        } else if (decpt >= digits.length()) {
            rt.append(digits);
            for (long i = digits.length(); i < decpt; i++) rt.append('0');
            rt.append(".0");
        } else {
            rt.append(digits, 0, (int) decpt).append('.').append(digits.substring((int) decpt));
        }
        return rt.toString();
    }

    //python int(): truncates floats, parses decimal strings
    public static int toInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) return Integer.parseInt(((String) v).trim());
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        throw new IllegalArgumentException("cannot convert " + v + " to int");
    }

    public static double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) return Double.parseDouble(((String) v).trim());
        throw new IllegalArgumentException("cannot convert " + v + " to double");
    }
}
