package de.wellenvogel.ochartsprovider;

public class Constants {
    public static final String LOGFILE="logname";
    public static final String BC_STOPAPPL = BuildConfig.APPLICATION_ID+".STOPAPPL";
    public static final String BC_HEARTBEAT = BuildConfig.APPLICATION_ID+".HEARTBEAT";
    public static final String BC_SEND="de.wellenvogel.avnav.PLUGIN";
    public static final String BC_CANCELIMPORT = BuildConfig.APPLICATION_ID+".CANCELIMPORT";

    static final String POUT="providerStdout.log";
    static final String PLOG="provider.log";
    static final String EXE="libAvnavOchartsProvider.so";
    public static final String OGR2OGR="libOgr2Ogr.so";
    public static final String ASSET_ROOT="assets"; //unpacked data root
    public static final String LOGDIR="log"; //below workDir
    static final String PRFX="Ocharts";
    static final String STARTPAGE="/static/index.html";
    static final String PLUGINCSS="/static/plugin.css";
    static final String PREF_UNIQUE_ID = "uniqueId" ;
    static final String PREF_LICENSE_ACCEPTED="licenseAccepted";
    static final int LICENSE_VERSION=2;

    static final String EXTRA_TXT="permissionText";
    static final String EXTRA_PERMISSIONS="permissions";
    static final String EXTRA_URI="importUri";
    static final String EXTRA_NAME="importName";
    static final int SAVE_KEY_REQ=1000;
    static final String KEY_FILE_NAME="ochartsng.key";
    static final int LICENSE_ACCEPT_REQUEST=1001;
}
