package com.excel.estvlauncher.custom;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * Auto-start app at boot (docs/box-protocol.md, "autostart"). At BOOT_COMPLETED the boot flag is set and the
 * home screen is brought up - it may not be the default HOME app - where HomeActivity shows the countdown and
 * opens the app. The relaunch after an idle home screen is handled by HomeActivity itself.
 */
public final class AutoStart {

    private static final String TAG = "CL.AutoStart";

    /** Wait for the home screen to appear by itself before starting it */
    private static final long HOME_WAIT_MS = 3000;
    /** Wait after a normal start before trying again as root */
    private static final long ROOT_WAIT_MS = 2000;

    private static final Handler MAIN = new Handler( Looper.getMainLooper() );

    /** The home screen while it is resumed (null otherwise) */
    private static volatile WeakReference<HomeActivity> resumedHome = new WeakReference<>( null );

    /** Not instantiated */
    private AutoStart() {
    }

    /**
     * Called by HomeActivity on resume / pause
     *
     * @param home The resumed home screen, or null when it is paused
     */
    static void setResumedHome( HomeActivity home ) {
        resumedHome = new WeakReference<>( home );
    }

    /**
     * @return true while the home screen is in front
     */
    static boolean isHomeResumed() {
        return resumedHome.get() != null;
    }

    /**
     * BOOT_COMPLETED: marks the boot auto-start as pending and makes sure the home screen comes up
     *
     * @param context Context
     */
    static void onBoot( Context context ) {
        final Context app   = context.getApplicationContext();
        Prefs         prefs = new Prefs( app );
        prefs.setBootPending( true );
        if( prefs.getAutostartPackage().isEmpty() ) {
            return;                                        // Nothing to start: leave the screen as it is
        }
        MAIN.post( new Runnable() {
            /**
             * The home screen may already be in front (it is HOME and came up before BOOT_COMPLETED)
             */
            @Override
            public void run() {
                HomeActivity home = resumedHome.get();
                if( home != null ) {
                    home.checkBootAutostart();
                }
            }
        } );
        MAIN.postDelayed( new Runnable() {
            /**
             * Brings the home screen up when it did not appear by itself
             */
            @Override
            public void run() {
                showHome( app );
            }
        }, HOME_WAIT_MS );
    }

    /**
     * Starts the home screen: normally first, and as root when Android blocked the background start
     *
     * @param app Application context
     */
    private static void showHome( final Context app ) {
        if( isHomeResumed() ) {
            return;
        }
        try {
            app.startActivity( new Intent( app, HomeActivity.class ).addFlags( Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT ) );
        }
        catch( Exception e ) {
            Log.w( TAG, "Home screen start refused: " + e.getMessage() );
        }
        MAIN.postDelayed( new Runnable() {
            /**
             * Still not in front: start it as root
             */
            @Override
            public void run() {
                if( isHomeResumed() ) {
                    return;
                }
                new Thread( new Runnable() {
                    /**
                     * am start through su (blocking, so off the main thread)
                     */
                    @Override
                    public void run() {
                        Shell.Result r = Shell.su( "am start -n " + app.getPackageName() + "/" + HomeActivity.class.getName() );
                        Log.i( TAG, "Home screen started as root: " + ( r.ok() ? "ok" : r.output ) );
                    }
                } ).start();
            }
        }, ROOT_WAIT_MS );
    }

    /**
     * Name of an app as Android shows it
     *
     * @param context     Context
     * @param packageName Package
     * @return Label, or the package name when the app is not installed
     */
    static String label( Context context, String packageName ) {
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo( packageName, 0 );
            return pm.getApplicationLabel( info ).toString();
        }
        catch( PackageManager.NameNotFoundException e ) {
            return packageName;
        }
    }

    /**
     * @param context     Context
     * @param packageName Package
     * @return true when the app is installed
     */
    static boolean isInstalled( Context context, String packageName ) {
        try {
            context.getPackageManager().getPackageInfo( packageName, 0 );
            return true;
        }
        catch( PackageManager.NameNotFoundException e ) {
            return false;
        }
    }
}
