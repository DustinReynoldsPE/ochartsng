package de.wellenvogel.ochartsprovider;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Restore unattended state after reboot:
 *  - re-enable wireless debugging (adb_wifi_enabled) so remote adb returns;
 *    needs WRITE_SECURE_SETTINGS granted via: pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS
 *  - start the chart service so AvNav gets its tiles without a START-button press.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String LOGTAG = "OchartsBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) return;
        try {
            Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
            Log.i(LOGTAG, "adb_wifi_enabled restored");
        } catch (Throwable t) {
            Log.e(LOGTAG, "unable to set adb_wifi_enabled (grant WRITE_SECURE_SETTINGS?)", t);
        }
        try {
            Intent svc = new Intent(context, OchartsService.class);
            context.startForegroundService(svc);
            Log.i(LOGTAG, "OchartsService started");
        } catch (Throwable t) {
            Log.e(LOGTAG, "unable to start OchartsService", t);
        }
    }
}
