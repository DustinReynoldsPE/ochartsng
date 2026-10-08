package de.wellenvogel.ochartsprovider.convert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;

//golden-file equivalence vs the upstream pipeline on real S-57 cells.
//for each cell in resources/convert/corpus/<cell>/:
//  <cell>.000     - the original NOAA cell (kept for reference/re-generation)
//  <cell>.sqlite.gz - ogr2ogr -f SQLite output (see fixtures/make_fixture.py)
//  <cell>.senc.gz   - reference output of upstream tools/senc/sqlite2senc.py
//  *.TXT          - TXTDSC sidecar files
//the .sqlite is converted with the java SqliteToSenc and compared to the
//golden .senc with the SencComparator (area geometry = triangle sets,
//everything else byte-wise).
public class GoldenCorpusTest {
    static final String[] CELLS = {"US5TX12M", "US5TX51M"};

    static File corpusDir(String cell) throws Exception {
        URL u = GoldenCorpusTest.class.getResource("/convert/corpus/" + cell);
        if (u == null) throw new Exception("missing corpus dir " + cell);
        File f = new File(u.toURI());
        if (!f.isDirectory()) throw new Exception("corpus " + cell + " is not a file dir: " + u);
        return f;
    }

    static File gunzip(File gz, File out) throws Exception {
        try (InputStream in = new GZIPInputStream(new FileInputStream(gz))) {
            Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    void runCell(String cell) throws Exception {
        Path dir = Files.createTempDirectory("senc-" + cell);
        File src = corpusDir(cell);
        File[] entries = src.listFiles();
        File sqliteGz = null, sencGz = null;
        for (File f : entries) {
            String n = f.getName();
            if (n.endsWith(".sqlite.gz")) sqliteGz = f;
            else if (n.endsWith(".senc.gz")) sencGz = f;
            else if (n.endsWith(".TXT")) {
                Files.copy(f.toPath(), dir.resolve(n), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (sqliteGz == null || sencGz == null) {
            fail("corpus " + cell + " missing .sqlite.gz or .senc.gz");
        }
        File sqlite = gunzip(sqliteGz, new File(dir.toFile(), cell + ".sqlite"));
        File out = new File(dir.toFile(), cell + ".senc");
        SqliteToSenc.Options options = new SqliteToSenc.Options();
        options.s57dir = SqliteToSencTest.s57dir().getPath();
        options.basedir = dir.toString();
        try (JdbcDb jdb = new JdbcDb(sqlite.getAbsolutePath())) {
            SqliteToSenc.convert(jdb, sqlite.getAbsolutePath(), out.getAbsolutePath(), options);
        }
        File golden = File.createTempFile("golden-" + cell, ".senc");
        gunzip(sencGz, golden);
        SencComparator cmp = new SencComparator();
        byte[] expected = Files.readAllBytes(golden.toPath());
        byte[] actual = Files.readAllBytes(out.toPath());
        boolean ok = cmp.compare(expected, actual);
        System.out.println(cell + ": expected " + expected.length + " bytes, produced "
                + actual.length + " bytes; " + cmp.summary());
        if (!ok) {
            fail(cell + ": " + cmp.diffs.size() + " diffs\n  " + String.join("\n  ",
                    cmp.diffs.subList(0, Math.min(cmp.diffs.size(), 20))));
        }
        assertTrue(cell + " should have compared area records",
                cmp.canonCompared + cmp.geoCompared > 0);
    }

    @Test
    public void us5tx12m() throws Exception {
        runCell("US5TX12M");
    }

    @Test
    public void us5tx51m() throws Exception {
        runCell("US5TX51M");
    }
}
