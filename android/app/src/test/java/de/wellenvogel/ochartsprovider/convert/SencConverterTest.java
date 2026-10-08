package de.wellenvogel.ochartsprovider.convert;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SencConverterTest {

    //ogr2ogr double: produces the fixture sqlite for every input cell
    static SencConverter.OgrRunner fakeOgr() throws Exception {
        final byte[] fixture=Files.readAllBytes(SqliteToSencTest.resource("fixture.sqlite").toPath());
        return new SencConverter.OgrRunner() {
            @Override
            public void convert(String iname, String oname) throws Exception {
                Files.write(new File(oname).toPath(),fixture);
            }
            @Override
            public void cancel() {
            }
        };
    }

    static SencConverter.Options testOptions() throws Exception {
        SencConverter.Options o=new SencConverter.Options();
        o.s57dir=SqliteToSencTest.s57dir().getPath();
        o.dbFactory=new SencConverter.DbFactory() {
            @Override
            public Db open(String path) throws Exception {
                return new JdbcDb(path);
            }
        };
        o.ogrRunner=fakeOgr();
        return o;
    }

    static File zipOf(Path dir,String... names) throws Exception {
        File zip=new File(dir.toFile(),"cells.zip");
        try (ZipOutputStream zos=new ZipOutputStream(new FileOutputStream(zip))){
            for (String n:names){
                zos.putNextEntry(new ZipEntry(n));
                if (n.equals("note.txt")){
                    Files.copy(SqliteToSencTest.resource("note.txt").toPath(),zos);
                }
                else{
                    zos.write(("cell:"+n).getBytes(StandardCharsets.UTF_8));
                }
                zos.closeEntry();
            }
        }
        return zip;
    }

    @Test
    public void zipToChartSet() throws Exception {
        Path dir=Files.createTempDirectory("cnv");
        File workDir=dir.toFile();
        File zip=zipOf(dir,"US5TEST.000","note.txt");
        final List<String> prog=new ArrayList<>();
        SencConverter cnv=new SencConverter(workDir,testOptions());
        SencConverter.Result rt=cnv.convert(zip,"testset",new SencConverter.ProgressListener() {
            @Override
            public void onProgress(String status, float fraction) {
                prog.add(status+":"+fraction);
            }
        });
        assertFalse(rt.cancelled);
        assertFalse(rt.hasErrors());
        assertEquals(1,rt.numFiles);
        assertEquals(new File(workDir,"charts/testset"),rt.setDir);
        File senc=new File(rt.setDir,"US5TEST.senc");
        assertTrue(senc.isFile());
        byte[] expected=Files.readAllBytes(SqliteToSencTest.resource("fixture.senc").toPath());
        assertArrayEquals(expected,Files.readAllBytes(senc.toPath()));
        File ci=new File(rt.setDir,SencConverter.CHARTINFO);
        assertTrue(ci.isFile());
        assertEquals("ChartInfo:testset\n",new String(Files.readAllBytes(ci.toPath()),StandardCharsets.UTF_8));
        assertFalse(prog.isEmpty());
        assertEquals("done",prog.get(prog.size()-1).split(":")[0]);
        //scratch dir cleaned up
        assertFalse(new File(workDir,"_TMP/cnv").exists());
    }

    @Test
    public void sqliteDirInput() throws Exception {
        Path dir=Files.createTempDirectory("cnv");
        File workDir=new File(dir.toFile(),"work");
        File inDir=new File(dir.toFile(),"in");
        inDir.mkdirs();
        Files.copy(SqliteToSencTest.resource("fixture.sqlite").toPath(),
                new File(inDir,"cell.sqlite").toPath(),StandardCopyOption.REPLACE_EXISTING);
        Files.copy(SqliteToSencTest.resource("note.txt").toPath(),
                new File(inDir,"note.txt").toPath(),StandardCopyOption.REPLACE_EXISTING);
        SencConverter cnv=new SencConverter(workDir,testOptions());
        SencConverter.Result rt=cnv.convert(inDir,null,null);
        assertFalse(rt.hasErrors());
        assertEquals(1,rt.numFiles);
        assertEquals("in",rt.setName);
        byte[] expected=Files.readAllBytes(SqliteToSencTest.resource("fixture.senc").toPath());
        assertArrayEquals(expected,Files.readAllBytes(new File(rt.setDir,"cell.senc").toPath()));
    }

    @Test
    public void noConvertibleFiles() throws Exception {
        Path dir=Files.createTempDirectory("cnv");
        File workDir=new File(dir.toFile(),"work");
        File zip=zipOf(dir,"readme.txt");
        SencConverter cnv=new SencConverter(workDir,testOptions());
        SencConverter.Result rt=cnv.convert(zip,"empty",null);
        assertTrue(rt.hasErrors());
        assertEquals(0,rt.numFiles);
        assertNull(rt.setDir);
        assertFalse(new File(workDir,"charts/empty").exists());
    }

    @Test
    public void existingSetConflict() throws Exception {
        Path dir=Files.createTempDirectory("cnv");
        File workDir=dir.toFile();
        assertTrue(new File(workDir,"charts/testset").mkdirs());
        File zip=zipOf(dir,"US5TEST.000");
        SencConverter cnv=new SencConverter(workDir,testOptions());
        try {
            cnv.convert(zip,"testset",null);
            fail("expected exception for existing set");
        } catch (Exception e){
            assertTrue(e.getMessage().contains("already exists"));
        }
    }

    @Test
    public void ignoreErrors() throws Exception {
        Path dir=Files.createTempDirectory("cnv");
        File workDir=dir.toFile();
        File zip=zipOf(dir,"good.000","bad.000");
        SencConverter.Options o=testOptions();
        o.ignoreErrors=true;
        final byte[] fixture=Files.readAllBytes(SqliteToSencTest.resource("fixture.sqlite").toPath());
        o.ogrRunner=new SencConverter.OgrRunner() {
            @Override
            public void convert(String iname, String oname) throws Exception {
                if (iname.contains("bad")) throw new Exception("ogr2ogr failed");
                Files.write(new File(oname).toPath(),fixture);
            }
            @Override
            public void cancel() {
            }
        };
        SencConverter cnv=new SencConverter(workDir,o);
        SencConverter.Result rt=cnv.convert(zip,"mixed",null);
        assertTrue(rt.hasErrors());
        assertEquals(1,rt.numFiles);
        assertTrue(new File(rt.setDir,"good.senc").isFile());
        //without -i the same failure must abort
        Path dir2=Files.createTempDirectory("cnv");
        SencConverter.Options o2=testOptions();
        o2.ogrRunner=o.ogrRunner;
        SencConverter cnv2=new SencConverter(dir2.toFile(),o2);
        try {
            cnv2.convert(zip,"mixed",null);
            fail("expected abort without ignoreErrors");
        } catch (Exception e){
        }
        assertFalse(new File(dir2.toFile(),"charts/mixed").exists());
    }

    @Test
    public void cancel() throws Exception {
        Path dir=Files.createTempDirectory("cnv");
        File workDir=dir.toFile();
        File zip=zipOf(dir,"a.000","b.000");
        SencConverter.Options o=testOptions();
        final SencConverter[] self=new SencConverter[1];
        o.ogrRunner=new SencConverter.OgrRunner() {
            @Override
            public void convert(String iname, String oname) throws Exception {
                self[0].cancel();
                throw new Exception("killed");
            }
            @Override
            public void cancel() {
            }
        };
        SencConverter cnv=new SencConverter(workDir,o);
        self[0]=cnv;
        SencConverter.Result rt=cnv.convert(zip,"canc",null);
        assertTrue(rt.cancelled);
        assertNull(rt.setDir);
        assertFalse(new File(workDir,"charts/canc").exists());
        assertFalse(new File(workDir,"_TMP/cnv").exists());
    }
}
