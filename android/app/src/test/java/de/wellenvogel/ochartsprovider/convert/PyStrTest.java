package de.wellenvogel.ochartsprovider.convert;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class PyStrTest {
    //expected values are CPython repr()/str() output
    @Test
    public void floatReprFixed() {
        assertEquals("1000000000000000.0", PyStr.floatRepr(1e15));
        assertEquals("123456789012345.6", PyStr.floatRepr(123456789012345.6));
        assertEquals("9007199254740992.0", PyStr.floatRepr(9007199254740992.0));
        assertEquals("0.0001", PyStr.floatRepr(0.0001));
        assertEquals("0.0002", PyStr.floatRepr(2e-4));
        assertEquals("0.000999999999999", PyStr.floatRepr(0.000999999999999));
        assertEquals("-0.000123", PyStr.floatRepr(-0.000123));
        assertEquals("123.456", PyStr.floatRepr(123.456));
        assertEquals("100.0", PyStr.floatRepr(100.0));
        assertEquals("1.0", PyStr.floatRepr(1.0));
        assertEquals("0.1", PyStr.floatRepr(0.1));
        assertEquals("2.5", PyStr.floatRepr(2.5));
        assertEquals("0.0", PyStr.floatRepr(0.0));
        assertEquals("-0.0", PyStr.floatRepr(-0.0));
    }

    //CPython repr switches to scientific at decpt <= -4 or decpt > 16
    @Test
    public void floatReprScientificBoundaries() {
        //decpt == 16 stays fixed, 17 goes scientific
        assertEquals("1e+16", PyStr.floatRepr(1e16));
        assertEquals("1e+17", PyStr.floatRepr(1e17));
        assertEquals("1.2345678901234568e+16", PyStr.floatRepr(12345678901234567.0));
        //decpt == -3 stays fixed (0.0001), -4 goes scientific
        assertEquals("1e-05", PyStr.floatRepr(0.00001));
        assertEquals("3e-05", PyStr.floatRepr(3e-5));
        //note: Double.MIN_VALUE diverges: CPython repr gives "5e-324" while
        //Double.toString gives "4.9E-324" (same double, different shortest digits)
        assertEquals("1.7976931348623157e+308", PyStr.floatRepr(1.7976931348623157e308));
        assertEquals("-1.5e-300", PyStr.floatRepr(-1.5e-300));
        assertEquals("1.5e+300", PyStr.floatRepr(1.5e300));
        //single digit mantissa keeps python style: 1e-05 not 1.0e-05
        assertEquals("1234567890123456.8", PyStr.floatRepr(1234567890123456.7));
    }

    @Test
    public void floatReprSpecial() {
        assertEquals("nan", PyStr.floatRepr(Double.NaN));
        assertEquals("inf", PyStr.floatRepr(Double.POSITIVE_INFINITY));
        assertEquals("-inf", PyStr.floatRepr(Double.NEGATIVE_INFINITY));
    }

    @Test
    public void strValues() {
        assertEquals("None", PyStr.str(null));
        assertEquals("abc", PyStr.str("abc"));
        assertEquals("42", PyStr.str(42));
        assertEquals("42", PyStr.str(42L));
        assertEquals("1.5", PyStr.str(1.5));
        assertEquals("True", PyStr.str(Boolean.TRUE));
        assertEquals("False", PyStr.str(Boolean.FALSE));
    }

    @Test
    public void reprValues() {
        assertEquals("['a', 'b']", PyStr.repr(Arrays.asList("a", "b")));
        assertEquals("[1, 2.5]", PyStr.repr(Arrays.asList(1, 2.5)));
        //CPython prefers double quotes when the string contains a single quote
        assertEquals("\"it's\"", PyStr.repr("it's"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", 1);
        assertEquals("{'a': 1}", PyStr.repr(m));
    }

    @Test
    public void toInt() {
        assertEquals(3, PyStr.toInt(3.9));
        assertEquals(-3, PyStr.toInt(-3.9));
        assertEquals(42, PyStr.toInt(" 42 "));
        assertEquals(1, PyStr.toInt(Boolean.TRUE));
    }

    @Test
    public void toDouble() {
        assertEquals(3.9, PyStr.toDouble("3.9"), 0);
        assertEquals(3.9, PyStr.toDouble(3.9f), 1e-6);
    }
}
