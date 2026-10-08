/******************************************************************************
 *
 * Project:  AvNav ocharts-provider
 * Purpose:  Service
 * Author:   Andreas Vogel
 *
 ***************************************************************************
 *   Copyright (C) 2022 by Andreas Vogel   *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU General Public License     *
 *   along with this program; if not, write to the                         *
 *   Free Software Foundation, Inc.,                                       *
 *   51 Franklin Street, Fifth Floor, Boston, MA 02110-1301,  USA.             *
 ***************************************************************************
 *
 */
package de.wellenvogel.ochartsprovider;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.core.app.NotificationCompat;

import de.wellenvogel.ochartsprovider.convert.SencConverter;

//foreground service running one S-57 (zip) import to a senc chart set
//start with EXTRA_URI (SAF uri) and EXTRA_NAME (chart set name)
public class ImportService extends Service {
    public static final int NOTIFY_ID = 2;
    private static final long NOTIFY_MIN_MS=300;
    private static volatile boolean isRunning=false;
    private volatile SencConverter converter=null;
    private BroadcastReceiver cancelReceiver;
    private final Handler handler = new Handler();
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private String lastStatus=null;
    private long lastNotify=0;
    private File logFile=null;
    private int port=0;

    public static boolean importRunning(){
        return isRunning;
    }

    private void log(String fmt,Object...args){
        String line=String.format(Locale.ROOT,fmt,args);
        Log.i(Constants.PRFX,line);
        if (logFile == null) return;
        String ts=new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss",Locale.ROOT).format(new Date());
        try {
            FileWriter w=new FileWriter(logFile,true);
            w.write(ts+" convert: "+line+"\n");
            w.close();
        }catch (Throwable t){
            Log.e(Constants.PRFX,"unable to write "+logFile,t);
        }
    }

    private void toast(final String msg){
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ImportService.this,OchartsService.tprfx+msg,Toast.LENGTH_LONG).show();
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(OchartsService.CHANNEL_ID,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(getString(R.string.channel_description));
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private PendingIntent contentIntent(){
        Intent notificationIntent = new Intent(this, MainActivity.class);
        return PendingIntent.getActivity(this, 0,
                notificationIntent, OchartsService.buildPiFlags(PendingIntent.FLAG_UPDATE_CURRENT,false));
    }

    private void startNotification() {
        createNotificationChannel();
        Intent cancelIntent = new Intent();
        cancelIntent.setAction(Constants.BC_CANCELIMPORT);
        PendingIntent cancelPi = PendingIntent.getBroadcast(this, 3, cancelIntent,
                OchartsService.buildPiFlags(PendingIntent.FLAG_CANCEL_CURRENT,true));
        notificationBuilder = new NotificationCompat.Builder(this, OchartsService.CHANNEL_ID);
        notificationBuilder.setSmallIcon(R.mipmap.ic_launcher);
        notificationBuilder.setContentTitle(getString(R.string.importTitle));
        notificationBuilder.setContentText(getString(R.string.importStarted));
        notificationBuilder.setContentIntent(contentIntent());
        notificationBuilder.setOngoing(true);
        notificationBuilder.setAutoCancel(false);
        notificationBuilder.setOnlyAlertOnce(true);
        notificationBuilder.setProgress(100,0,true);
        notificationBuilder.addAction(R.drawable.close_black48,getString(R.string.cancel),cancelPi);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            notificationBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE){
            startForeground(NOTIFY_ID,notificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
        else {
            startForeground(NOTIFY_ID, notificationBuilder.build());
        }
    }

    private void updateNotification(String status,float fraction){
        long now= SystemClock.uptimeMillis();
        if (status.equals(lastStatus) && (now-lastNotify) < NOTIFY_MIN_MS) return;
        lastStatus=status;
        lastNotify=now;
        notificationBuilder.setContentText(status);
        notificationBuilder.setProgress(100,(int)(fraction*100),fraction <= 0);
        notificationManager.notify(NOTIFY_ID,notificationBuilder.build());
    }

    private void doneNotification(String text){
        stopForeground(true);
        NotificationCompat.Builder b=new NotificationCompat.Builder(this, OchartsService.CHANNEL_ID);
        b.setSmallIcon(R.mipmap.ic_launcher);
        b.setContentTitle(getString(R.string.importTitle));
        b.setContentText(text);
        b.setContentIntent(contentIntent());
        b.setAutoCancel(true);
        notificationManager.notify(NOTIFY_ID,b.build());
    }

    //ask the running provider to pick up the new set (see UploadRequestHandler parseDir)
    //a failure is ok - the set will be found at the next provider start anyway
    private void triggerRescan(String setName){
        if (port <= 0) return;
        try{
            URL url=new URL("http://127.0.0.1:"+port+"/upload/parseDir?name="+
                    URLEncoder.encode(setName,"UTF-8"));
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(120000);
            int r=connection.getResponseCode();
            connection.disconnect();
            log("parseDir %s returned %d",setName,r);
        }catch (Throwable t){
            log("unable to trigger rescan of %s: %s",setName,t.getMessage());
        }
    }

    private void finishConvert(SencConverter.Result r, Throwable error){
        isRunning=false;
        converter=null;
        String summary;
        String rescan=null;
        if (error != null){
            summary=getString(R.string.importFailed,error.getMessage());
            log("import failed: %s",error.getMessage());
        }
        else if (r.cancelled){
            summary=getString(R.string.importCancelled);
            log("import of %s cancelled",r.setName);
        }
        else{
            for (SencConverter.ConvertError e:r.errors){
                log("error converting %s: %s",e.name,e.reason);
            }
            summary=getString(R.string.importDone,r.numFiles,r.errors.size());
            log("import of %s finished: %d files, %d errors",r.setName,r.numFiles,r.errors.size());
            if (r.setDir != null) rescan=r.setName;
        }
        toast(summary);
        if (rescan != null) triggerRescan(rescan);
        doneNotification(summary);
        stopSelf();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager=(NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        cancelReceiver =new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(Constants.PRFX,"received cancel import");
                SencConverter c=converter;
                if (c != null) c.cancel();
            }
        };
        IntentFilter filter=new IntentFilter(Constants.BC_CANCELIMPORT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelReceiver,filter, RECEIVER_EXPORTED);
        }
        else{
            registerReceiver(cancelReceiver,filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        //a second start hits this same instance - just reject it
        if (isRunning){
            toast(getString(R.string.importRunning));
            return Service.START_NOT_STICKY;
        }
        startNotification();
        String uriStr=intent.getStringExtra(Constants.EXTRA_URI);
        final String setName=intent.getStringExtra(Constants.EXTRA_NAME);
        if (uriStr == null || setName == null){
            toast(getString(R.string.importFailed,"missing parameters"));
            stopForeground(true);
            stopSelf();
            return Service.START_NOT_STICKY;
        }
        final Uri uri=Uri.parse(uriStr);
        isRunning=true;
        Thread convertThread=new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Settings settings=Settings.getSettings(ImportService.this,false);
                    port=settings.getPort();
                    File logDir=new File(settings.getWorkDir(),Constants.LOGDIR);
                    logDir.mkdirs();
                    logFile=new File(logDir,Constants.PLOG);
                    File s57dir=new File(getFilesDir(),Constants.ASSET_ROOT+"/"+BuildConfig.ASSETS_S57);
                    if (! s57dir.isDirectory()){
                        log("copying assets");
                        OchartsService.copyAssets(ImportService.this);
                    }
                    converter=SencConverter.create(ImportService.this);
                    SencConverter.Result result=converter.convert(ImportService.this, uri, setName,
                        new SencConverter.ProgressListener() {
                            @Override
                            public void onProgress(String status, float fraction) {
                                updateNotification(status,fraction);
                            }
                        });
                    finishConvert(result,null);
                } catch (Throwable t){
                    finishConvert(null,t);
                }
            }
        });
        convertThread.setName("SencImport");
        convertThread.start();
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning=false;
        SencConverter c=converter;
        if (c != null) c.cancel();
        unregisterReceiver(cancelReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
