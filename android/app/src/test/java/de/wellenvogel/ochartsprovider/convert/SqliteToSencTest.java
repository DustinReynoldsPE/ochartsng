package de.wellenvogel.ochartsprovider.convert;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class SqliteToSencTest {
    static File s57dir() {
        return new File("../../provider/s57static");
    }

    static File resource(String name) throws Exception {
        InputStream is = SqliteToSencTest.class.getResourceAsStream("/convert/" + name);
        if (is == null) throw new Exception("missing test resource " + name);
        File f = File.createTempFile("conv", name);
        is.close();
        try (InputStream in = SqliteToSencTest.class.getResourceAsStream("/convert/" + name)) {
            Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return f;
    }

    //golden test: fixture.senc was created by upstream tools/senc/sqlite2senc.py
    //(see src/test/fixtures/make_fixture.py); must be byte identical
    @Test
    public void golden() throws Exception {
        Path dir = Files.createTempDirectory("senc");
        File db = resource("fixture.sqlite");
        File target = new File(dir.toFile(), "fixture.sqlite");
        Files.copy(db.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(resource("note.txt").toPath(), dir.resolve("note.txt"), StandardCopyOption.REPLACE_EXISTING);
        File out = new File(dir.toFile(), "out.senc");
        SqliteToSenc.Options options = new SqliteToSenc.Options();
        options.s57dir = s57dir().getPath();
        options.basedir = dir.toString();
        try (JdbcDb jdb = new JdbcDb(target.getAbsolutePath())) {
            SqliteToSenc.convert(jdb, target.getAbsolutePath(), out.getAbsolutePath(), options);
        }
        byte[] expected = Files.readAllBytes(resource("fixture.senc").toPath());
        byte[] actual = Files.readAllBytes(out.toPath());
        if (actual.length != expected.length) {
            throw new AssertionError("senc length " + actual.length + " != golden " + expected.length);
        }
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != expected[i]) {
                throw new AssertionError("senc differs at offset " + i + ": "
                        + String.format("%02x != %02x", actual[i], expected[i]));
            }
        }
    }
}
