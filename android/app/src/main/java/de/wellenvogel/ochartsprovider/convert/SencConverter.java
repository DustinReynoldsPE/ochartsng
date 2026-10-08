package de.wellenvogel.ochartsprovider.convert;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.wellenvogel.ochartsprovider.BuildConfig;
import de.wellenvogel.ochartsprovider.Constants;
import de.wellenvogel.ochartsprovider.ProcessHandler;
import de.wellenvogel.ochartsprovider.ProcessState;
import de.wellenvogel.ochartsprovider.Settings;

//orchestration equivalent of tools/tosenc.py:
//zip/dir/single file of s57 cells -> ogr2ogr -> sqlite -> senc chart set
//runs blocking on the calling thread, use a background Thread to drive it
public class SencConverter {
    public static final String TMP_SUB="__tmp";
    public static final String IN_SUB="__in";
    public static final String OUT_SUB="out";
    public static final String SQL_EXT="sqlite";
    public static final String OUT_EXT="senc";
    public static final String[] CNV_EXT={"000","geojson"};
    public static final String CHARTINFO="Chartinfo.txt";
    public static final String CHARTS_DIR="charts"; //below workDir, scanned by the provider
    public static final String TMP_DIR="_TMP";      //below workDir, skipped by the provider
    public static final String CNV_SUB="cnv";       //conversion scratch dir below TMP_DIR
    public static final String OGR_LOG="ogr2ogr.log";

    public interface ProgressListener {
        void onProgress(String status,float fraction);
    }
    public interface DbFactory {
        Db open(String path) throws Exception;
    }
    public interface OgrRunner {
        void convert(String iname,String oname) throws Exception;
        void cancel();
    }
    public static class ConvertError{
        public final String name;
        public final String reason;
        public ConvertError(String name,String reason){
            this.name=name;
            this.reason=reason;
        }
    }
    public static class Result {
        public File setDir=null;
        public String setName=null;
        public int numFiles=0;
        public boolean cancelled=false;
        public final List<ConvertError> errors=new ArrayList<>();
        public boolean hasErrors(){
            return ! errors.isEmpty();
        }
    }
    public static class Options {
        public boolean ignoreErrors=false;
        public Long scale=null;
        public int em=SencWriter.EM_ALL;
        public String s57dir=null;
        public DbFactory dbFactory=null; //default: AndroidDb
        public OgrRunner ogrRunner=null; //default: androidOgrRunner needs a ctx, must be set
    }
    public static class CancelledException extends Exception{
        public CancelledException(){
            super("cancelled");
        }
    }

    private final File workDir;
    private final Options options;
    private volatile boolean cancelled=false;

    public SencConverter(File workDir,Options options){
        this.workDir=workDir;
        this.options=options != null ? options : new Options();
    }
    public void cancel(){
        cancelled=true;
        if (options.ogrRunner != null) options.ogrRunner.cancel();
    }
    public boolean isCancelled(){
        return cancelled;
    }
    private void checkCancel() throws CancelledException {
        if (cancelled) throw new CancelledException();
    }
    private static void progress(ProgressListener l,String status,float fraction){
        if (l != null) l.onProgress(status,fraction);
    }

    //runner for the real ogr2ogr executable via ProcessHandler
    public static OgrRunner androidOgrRunner(final Context ctx, final File logFile){
        return new OgrRunner() {
            ProcessHandler handler=null;
            @Override
            public void convert(String iname, String oname) throws Exception {
                handler=new ProcessHandler(ctx, Constants.OGR2OGR,logFile);
                handler.start(Arrays.asList("-f","SQLite",oname,iname,"-oo","LIST_AS_STRING=ON"));
                while (handler.isAlive()){
                    Thread.sleep(50);
                }
                ProcessState st=handler.getState();
                if (st.error != null) throw new Exception("ogr2ogr "+iname+": "+st.error);
                if (st.exitValue != 0) throw new Exception("converting "+iname+" to "+oname+" returned "+st.exitValue);
            }
            @Override
            public void cancel() {
                if (handler != null) handler.stop();
            }
        };
    }

    //factory for the app entry point (.5 UI):
    //SencConverter c=SencConverter.create(ctx); new Thread(() -> c.convert(ctx,uri,name,listener)).start();
    public static SencConverter create(Context ctx) throws Exception {
        Settings settings=Settings.getSettings(ctx,false);
        File workDir=settings.getWorkDir();
        Options o=new Options();
        o.s57dir=new File(ctx.getFilesDir(),Constants.ASSET_ROOT+"/"+BuildConfig.ASSETS_S57).getAbsolutePath();
        o.dbFactory=AndroidDb::new;
        File logDir=new File(workDir,Constants.LOGDIR);
        logDir.mkdirs();
        o.ogrRunner=androidOgrRunner(ctx,new File(logDir,OGR_LOG));
        return new SencConverter(workDir,o);
    }

    public Result convert(Context ctx,Uri in,String setName,ProgressListener listener) throws Exception {
        if (in == null) throw new Exception("no input uri");
        if ("file".equals(in.getScheme())){
            return convert(new File(in.getPath()),setName,listener);
        }
        if (setName == null || setName.isEmpty()){
            String seg=in.getLastPathSegment();
            if (seg != null) setName=baseName(new File(seg).getName());
        }
        File tmpBase=new File(workDir,TMP_DIR);
        if (! tmpBase.isDirectory() && ! tmpBase.mkdirs())
            throw new Exception("unable to create "+tmpBase.getAbsolutePath());
        File up=new File(tmpBase,"upload.zip");
        try {
            InputStream is=ctx.getContentResolver().openInputStream(in);
            if (is == null) throw new Exception("unable to open "+in);
            copyStream(is,up);
            if (! isZip(up)) throw new Exception(in+" is not a zip file");
            return convert(up,setName,listener);
        } finally {
            up.delete();
        }
    }

    public Result convert(File input,String setName,ProgressListener listener) throws Exception {
        Result rt=new Result();
        if (input == null || ! input.exists()) throw new Exception("input "+input+" not found");
        if (setName == null || setName.isEmpty()) setName=baseName(input.getName());
        checkSetName(setName);
        rt.setName=setName;
        File chartDir=new File(workDir,CHARTS_DIR);
        File target=new File(chartDir,setName);
        if (target.exists()) throw new Exception("chart set "+setName+" already exists at "+target.getAbsolutePath());
        File tmpBase=new File(workDir,TMP_DIR);
        cleanupTmp(tmpBase);
        File tmp=new File(tmpBase,CNV_SUB);
        if (! tmp.mkdirs()) throw new Exception("unable to create "+tmp.getAbsolutePath());
        try {
            doConvert(input,setName,tmp,chartDir,target,rt,listener);
        }
        catch (CancelledException c){
            rt.cancelled=true;
        }
        finally {
            deleteRecursive(tmp);
        }
        return rt;
    }

    private void doConvert(File input,String setName,File tmp,File chartDir,File target,
                           Result rt,ProgressListener listener) throws Exception {
        progress(listener,"reading input",0);
        File inDir;
        if (input.isDirectory()){
            inDir=input;
        }
        else {
            inDir=new File(tmp,IN_SUB);
            if (extOf(input.getName()).equalsIgnoreCase("zip")){
                SqliteToSenc.log("extracting %s to %s",input,inDir);
                unzip(input,inDir);
            }
            else{
                if (! isKnownExt(input.getName())){
                    rt.errors.add(new ConvertError(input.getName(),"unknown file type"));
                    return;
                }
                if (! inDir.mkdirs()) throw new Exception("unable to create "+inDir.getAbsolutePath());
                copyFile(input,new File(inDir,input.getName()));
            }
        }
        checkCancel();
        List<File> cells=new ArrayList<>();
        collectFiles(inDir,cells);
        Collections.sort(cells);
        File outDir=new File(tmp,OUT_SUB);
        if (! outDir.mkdirs()) throw new Exception("unable to create "+outDir.getAbsolutePath());
        int done=0;
        for (File cell:cells){
            checkCancel();
            float fraction=cells.isEmpty() ? 0 : (float)done/cells.size();
            progress(listener,"convert "+cell.getName(),fraction);
            try {
                if (handleCell(cell,new File(outDir,baseName(cell.getName())+"."+OUT_EXT),tmp,rt))
                    rt.numFiles++;
            }
            catch (CancelledException c){
                throw c;
            }
            catch (Exception e){
                if (cancelled) throw new CancelledException();
                if (! options.ignoreErrors) throw e;
                rt.errors.add(new ConvertError(cell.getName(),e.getMessage()));
            }
            done++;
        }
        if (rt.numFiles < 1){
            rt.errors.add(new ConvertError(input.getName(),"no convertible input files"));
            return;
        }
        progress(listener,"writing "+CHARTINFO,0.95f);
        writeChartInfo(outDir,setName);
        checkCancel();
        if (! chartDir.isDirectory() && ! chartDir.mkdirs())
            throw new Exception("unable to create "+chartDir.getAbsolutePath());
        moveDir(outDir,target);
        rt.setDir=target;
        SqliteToSenc.log("created %s with %d files",target,rt.numFiles);
        progress(listener,"done",1.0f);
    }

    //one cell: .000/.geojson -> ogr2ogr -> sqlite -> senc (like handleSingleFile)
    private boolean handleCell(File ifile,File ofile,File tmp,Result rt) throws Exception {
        String ext=extOf(ifile.getName());
        String base=baseName(ifile.getName());
        File sqliteFile=null;
        if (isCnvExt(ext)){
            File tmpSqlite=new File(new File(tmp,TMP_SUB),base+"."+SQL_EXT);
            if (! tmpSqlite.getParentFile().isDirectory() && ! tmpSqlite.getParentFile().mkdirs())
                throw new Exception("unable to create "+tmpSqlite.getParentFile().getAbsolutePath());
            if (options.ogrRunner == null) throw new Exception("no ogr2ogr runner configured");
            SqliteToSenc.log("converting %s to %s",ifile,tmpSqlite);
            options.ogrRunner.convert(ifile.getAbsolutePath(),tmpSqlite.getAbsolutePath());
            if (! tmpSqlite.exists()) throw new Exception(tmpSqlite+" not created from "+ifile);
            sqliteFile=tmpSqlite;
        }
        else if (ext.equalsIgnoreCase(SQL_EXT)){
            sqliteFile=ifile;
        }
        else{
            SqliteToSenc.warn("unknown file type %s",ifile.getName());
            rt.errors.add(new ConvertError(ifile.getName(),"unknown file type"));
            return false;
        }
        checkCancel();
        SqliteToSenc.Options o=new SqliteToSenc.Options();
        o.basedir=ifile.getParent();
        o.s57dir=options.s57dir;
        o.em=options.em;
        o.scale=options.scale;
        o.ignoreErrors=options.ignoreErrors;
        Db db=options.dbFactory != null ?
                options.dbFactory.open(sqliteFile.getAbsolutePath()) :
                new AndroidDb(sqliteFile.getAbsolutePath());
        try {
            SqliteToSenc.convert(db,sqliteFile.getAbsolutePath(),ofile.getAbsolutePath(),o);
        } finally {
            db.close();
        }
        return true;
    }

    private static void writeChartInfo(File dir,String setName) throws Exception {
        FileWriter w=new FileWriter(new File(dir,CHARTINFO));
        try {
            w.write("ChartInfo:"+setName+"\n");
        } finally {
            w.close();
        }
    }

    private void cleanupTmp(File tmpBase){
        File[] old=tmpBase.listFiles();
        if (old == null) return;
        for (File f:old){
            if (f.getName().startsWith(CNV_SUB)) deleteRecursive(f);
        }
    }

    static void deleteRecursive(File f){
        if (f == null || ! f.exists()) return;
        if (f.isDirectory()){
            File[] sub=f.listFiles();
            if (sub != null) for (File s:sub) deleteRecursive(s);
        }
        f.delete();
    }

    static void collectFiles(File dir,List<File> rt){
        File[] files=dir.listFiles();
        if (files == null) return;
        for (File f:files){
            if (f.isDirectory()) collectFiles(f,rt);
            else if (isKnownExt(f.getName())) rt.add(f);
        }
    }

    static boolean isCnvExt(String ext){
        for (String e:CNV_EXT) if (ext.equalsIgnoreCase(e)) return true;
        return false;
    }
    static boolean isKnownExt(String name){
        String ext=extOf(name);
        return isCnvExt(ext) || ext.equalsIgnoreCase(SQL_EXT);
    }
    static String extOf(String name){
        return SqliteToSenc.splitext(name)[1].replaceFirst("^\\.","");
    }
    static String baseName(String name){
        int sep=name.lastIndexOf('/');
        if (sep >= 0) name=name.substring(sep+1);
        return SqliteToSenc.splitext(name)[0];
    }
    private static void checkSetName(String setName) throws Exception {
        if (setName == null || setName.isEmpty()) throw new Exception("empty set name");
        if (setName.contains("/") || setName.contains("\\") || setName.contains(".."))
            throw new Exception("invalid set name "+setName);
    }

    static void unzip(File zip,File dest) throws Exception {
        if (! dest.isDirectory() && ! dest.mkdirs())
            throw new Exception("unable to create "+dest.getAbsolutePath());
        String canon=dest.getCanonicalPath();
        ZipFile zf=new ZipFile(zip);
        try {
            Enumeration<? extends ZipEntry> entries=zf.entries();
            while (entries.hasMoreElements()){
                ZipEntry ze=entries.nextElement();
                File of=new File(dest,ze.getName());
                if (! of.getCanonicalPath().startsWith(canon))
                    throw new Exception("illegal zip entry "+ze.getName());
                if (ze.isDirectory()){
                    of.mkdirs();
                    continue;
                }
                File parent=of.getParentFile();
                if (! parent.isDirectory() && ! parent.mkdirs())
                    throw new Exception("unable to create "+parent.getAbsolutePath());
                copyStream(zf.getInputStream(ze),of);
            }
        } finally {
            zf.close();
        }
    }

    static void copyStream(InputStream is,File out) throws IOException {
        try (InputStream in=is; OutputStream os=new FileOutputStream(out)){
            byte[] buf=new byte[64*1024];
            int rd;
            while ((rd=in.read(buf)) > 0) os.write(buf,0,rd);
        }
    }
    static void copyFile(File in,File out) throws IOException {
        copyStream(Files.newInputStream(in.toPath()),out);
    }
    static boolean isZip(File f) throws IOException {
        try (InputStream is=Files.newInputStream(f.toPath())){
            return is.read() == 'P' && is.read() == 'K';
        }
    }
    private static void moveDir(File src,File dst) throws Exception {
        try {
            Files.move(src.toPath(),dst.toPath(),StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e){
            if (! src.renameTo(dst))
                throw new Exception("unable to rename "+src.getAbsolutePath()+" to "+dst.getAbsolutePath());
        }
    }
}
