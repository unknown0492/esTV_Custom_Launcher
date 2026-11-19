package com.excel.estvlauncher.custom;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;

/**
 * Starts the check-in chain when the process starts, checks in again whenever the network comes up,
 * and switches the permission auto-allow service on with root on first start.
 */
public class LauncherApp extends Application {

    /**
     * Process start: first check-in, network watch, accessibility service
     */
    @Override
    public void onCreate() {
        super.onCreate();
        CheckInManager.checkIn( this, false );
        watchNetwork();
        enableAutoAllow();
    }

    /**
     * Checks in whenever a network becomes available (the manifest CONNECTIVITY_CHANGE broadcast no longer
     * reaches apps on Android 7+)
     */
    private void watchNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService( Context.CONNECTIVITY_SERVICE );
        if( cm == null ) {
            return;
        }
        final Context app = this;
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            /**
             * A network is up: check in
             *
             * @param network The network
             */
            @Override
            public void onAvailable( Network network ) {
                CheckInManager.checkIn( app, false );
            }
        };
        cm.registerDefaultNetworkCallback( callback );
    }

    /**
     * Switches the auto-allow accessibility service on with root, in the background (su can be slow)
     */
    private void enableAutoAllow() {
        final Context app = this;
        new Thread( new Runnable() {
            /**
             * Enables the service unless it is on already
             */
            @Override
            public void run() {
                if( !PermissionAutoAllowService.isEnabled( app ) ) {
                    PermissionAutoAllowService.enableWithRoot( app );
                }
            }
        }, "auto-allow-enable" ).start();
    }
}
