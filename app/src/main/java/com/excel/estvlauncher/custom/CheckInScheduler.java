package com.excel.estvlauncher.custom;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Keeps the check-in going: an alarm every few minutes (WorkManager cannot run more often than every 15),
 * set again after each check-in, at boot and when the network comes up.
 */
public final class CheckInScheduler extends BroadcastReceiver {

    /** Action of the check-in alarm */
    static final String ACTION_CHECK_IN = "com.excel.estvlauncher.custom.CHECK_IN";

    /**
     * Alarm, boot or package-replaced: check in now (at boot also the auto-start app, see AutoStart)
     *
     * @param context Context
     * @param intent  Broadcast
     */
    @Override
    public void onReceive( Context context, Intent intent ) {
        String action = intent.getAction();
        if( !ACTION_CHECK_IN.equals( action ) && !Intent.ACTION_BOOT_COMPLETED.equals( action ) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals( action ) ) {
            return;
        }
        if( Intent.ACTION_BOOT_COMPLETED.equals( action ) ) {
            AutoStart.onBoot( context );
        }
        CheckInManager.checkIn( context, false );
        // If a check-in is already running it is skipped; make sure the chain continues anyway
        scheduleNext( context, CheckInManager.getIntervalSec() );
    }

    /**
     * Sets the next check-in alarm (replacing any earlier one)
     *
     * @param context     Context
     * @param intervalSec Seconds from now
     */
    public static void scheduleNext( Context context, int intervalSec ) {
        AlarmManager alarms = (AlarmManager) context.getSystemService( Context.ALARM_SERVICE );
        if( alarms == null ) {
            return;
        }
        long at = SystemClock.elapsedRealtime() + intervalSec * 1000L;
        PendingIntent pi = pendingIntent( context );
        alarms.setAndAllowWhileIdle( AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi );
    }

    /**
     * The alarm's broadcast
     *
     * @param context Context
     * @return PendingIntent to this receiver
     */
    private static PendingIntent pendingIntent( Context context ) {
        Intent intent = new Intent( context, CheckInScheduler.class ).setAction( ACTION_CHECK_IN );
        return PendingIntent.getBroadcast( context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE );
    }
}
