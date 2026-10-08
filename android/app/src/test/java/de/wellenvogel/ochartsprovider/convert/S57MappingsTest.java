package de.wellenvogel.ochartsprovider.convert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class S57MappingsTest {
    static File s57dir() {
        return new File("../../provider/s57static");
    }

    @Test
    public void parse() throws Exception {
        S57Mappings m = new S57Mappings(s57dir().getPath());
        m.parse();
        assertTrue(m.objectClasses.size() > 100);
        assertTrue(m.attributes.size() > 100);
        assertEquals(7, m.objByName("bcnlat").id());
        assertEquals(129, m.objByName("SOUNDG").id());
        assertNull(m.objByName("nosuchclass"));
        assertEquals(133, m.attrByName("scamin").id());
        assertEquals("I", m.attrByName("SCAMIN").type);
        assertEquals("E", m.attrByName("CATCOA").type);
        assertNull(m.attrByName("nosuchattr"));
        assertNotNull(m.objById(7));
        assertNotNull(m.attrById(133));
    }
}
