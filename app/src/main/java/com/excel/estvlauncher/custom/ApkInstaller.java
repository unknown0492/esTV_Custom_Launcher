package com.excel.estvlauncher.custom;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;

import java.io.File;

/**
 * Installs an APK downloaded from the CMS: silently with root, else through the Android installer
 * (whose Install button the accessibility service presses)
 */
public final class ApkInstaller {

    /** Folder (in the app's files) that holds downloaded APKs; shared with the installer by the FileProvider */
    public static final String APK_DIR = "apks";

    /** Outcome of an install attempt */
    public enum Outcome {
        /** Installed (pm said Success) */
        INSTALLED,
        /** Handed to the Android installer: the outcome is known at the next check-in */
        HANDED_TO_INSTALLER,
        /** Failed (see the message) */
        FAILED
    }

    /** Last failure reason (only for FAILED) */
    private static String lastMessage = "";

    /** Not instantiated */
    private ApkInstaller() {
    }

    /**
     * @return Reason of the last FAILED outcome
     */
    public static String getLastMessage() {
        return lastMessage;
    }

    /**
     * Installed versionCode of a package
     *
     * @param context     Context
     * @param packageName Package
     * @return versionCode, or 0 when not installed
     */
    public static long installedVersionCode( Context context, String packageName ) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo( packageName, 0 );
            return PackageInfoCompat.getLongVersionCode( info );
        }
        catch( PackageManager.NameNotFoundException e ) {
            return 0;
        }
    }

    /**
     * Installed versionName of a package
     *
     * @param context     Context
     * @param packageName Package
     * @return versionName, or "" when not installed
     */
    public static String installedVersionName( Context context, String packageName ) {
        try {
            String name = context.getPackageManager().getPackageInfo( packageName, 0 ).versionName;
            return name == null ? "" : name;
        }
        catch( PackageManager.NameNotFoundException e ) {
            return "";
        }
    }

    /**
     * Installs an APK. Root first: the file is copied to /data/local/tmp (SELinux keeps pm from reading
     * app data) and installed with "pm install -r -d". Without root the Android installer is opened.
     *
     * @param context Context
     * @param apk     Downloaded file (already checked against its SHA-256)
     * @return Outcome
     */
    public static Outcome install( Context context, File apk ) {
        lastMessage = "";
        if( Shell.hasRoot() ) {
            String tmp = "/data/local/tmp/estv_cl_" + System.currentTimeMillis() + ".apk";
            Shell.Result r = Shell.su(
                    "cp " + Shell.quote( apk.getAbsolutePath() ) + " " + tmp,
                    "chmod 644 " + tmp,
                    "pm install -r -d " + tmp + "; code=$?; rm -f " + tmp + "; (exit $code)" );
            if( r.output.contains( "Success" ) ) {
                return Outcome.INSTALLED;
            }
            lastMessage = shorten( r.output.isEmpty() ? "pm install failed (exit " + r.exitCode + ")" : r.output );
            return Outcome.FAILED;
        }
        return openInstaller( context, apk );
    }

    /**
     * Opens the Android package installer for an APK
     *
     * @param context Context
     * @param apk     APK in the app's apks folder
     * @return HANDED_TO_INSTALLER, or FAILED when the installer cannot be opened
     */
    private static Outcome openInstaller( Context context, File apk ) {
        try {
            Uri uri = FileProvider.getUriForFile( context, context.getPackageName() + ".files", apk );
            Intent intent = new Intent( Intent.ACTION_VIEW )
                    .setDataAndType( uri, "application/vnd.android.package-archive" )
                    .addFlags( Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION );
            context.startActivity( intent );
            return Outcome.HANDED_TO_INSTALLER;
        }
        catch( Exception e ) {
            lastMessage = "No root, and the Android installer could not be opened: " + e.getMessage();
            return Outcome.FAILED;
        }
    }

    /**
     * Keeps a message short enough for the CMS table
     *
     * @param text Message
     * @return At most 200 characters, on one line
     */
    private static String shorten( String text ) {
        String one = text.replace( '\n', ' ' ).trim();
        return one.length() > 200 ? one.substring( 0, 200 ) : one;
    }
}
