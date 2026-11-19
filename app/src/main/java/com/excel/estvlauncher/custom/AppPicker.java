package com.excel.estvlauncher.custom;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The apps that can be opened on the box (launcher and TV launcher activities), for the Home Buttons editor and
 * the Secret Menu's auto-start app
 */
public final class AppPicker {

    /** Not instantiated */
    private AppPicker() {
    }

    /**
     * Launcher and TV launcher activities of the installed apps, without this launcher, sorted by label
     *
     * @param context Context
     * @return Activities
     */
    public static List<ResolveInfo> launchableApps( Context context ) {
        final PackageManager pm = context.getPackageManager();
        List<ResolveInfo> apps = new ArrayList<>( pm.queryIntentActivities( new Intent( Intent.ACTION_MAIN ).addCategory( Intent.CATEGORY_LAUNCHER ), 0 ) );
        for( ResolveInfo tv : pm.queryIntentActivities( new Intent( Intent.ACTION_MAIN ).addCategory( Intent.CATEGORY_LEANBACK_LAUNCHER ), 0 ) ) {
            boolean known = false;
            for( ResolveInfo a : apps ) {
                if( a.activityInfo.name.equals( tv.activityInfo.name ) ) {
                    known = true;
                    break;
                }
            }
            if( !known ) {
                apps.add( tv );
            }
        }
        // Not this launcher
        for( int i = apps.size() - 1; i >= 0; i-- ) {
            if( context.getPackageName().equals( apps.get( i ).activityInfo.packageName ) ) {
                apps.remove( i );
            }
        }
        Collections.sort( apps, new Comparator<ResolveInfo>() {
            /**
             * By label
             *
             * @param a First
             * @param b Second
             * @return Order
             */
            @Override
            public int compare( ResolveInfo a, ResolveInfo b ) {
                return a.loadLabel( pm ).toString().compareToIgnoreCase( b.loadLabel( pm ).toString() );
            }
        } );
        return apps;
    }

    /**
     * "Label  (package)" for each app, for a list dialog
     *
     * @param context Context
     * @param apps    From launchableApps
     * @return Texts
     */
    public static String[] labels( Context context, List<ResolveInfo> apps ) {
        PackageManager pm     = context.getPackageManager();
        String[]       labels = new String[ apps.size() ];
        for( int i = 0; i < apps.size(); i++ ) {
            labels[ i ] = apps.get( i ).loadLabel( pm ) + "  (" + apps.get( i ).activityInfo.packageName + ")";
        }
        return labels;
    }
}
