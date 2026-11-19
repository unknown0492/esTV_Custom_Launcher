package com.excel.estvlauncher.custom;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The 5-minute check-in with the CMS (docs/box-protocol.md of estv-custom-launcher-cms):
 * reports MAC, IP, name and versions; applies the wallpaper, the home buttons and APK updates the CMS sends.
 * When the CMS cannot be reached, asks eslink for the CMS address once and tries again.
 * One check-in at a time, on a background thread.
 */
public final class CheckInManager {

    private static final String TAG = "CL.CheckIn";

    /** Protocol version sent and expected */
    static final int PROTOCOL = 1;

    /** Wallpaper file in the app's files folder */
    public static final String WALLPAPER_FILE = "wallpaper.img";

    /** Something the screens show has changed (buttons, wallpaper, check-in result) */
    public interface Listener {
        /**
         * Called on the main thread after a check-in
         */
        void onCheckInFinished();
    }

    private static final ExecutorService   EXECUTOR  = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean     RUNNING   = new AtomicBoolean( false );
    private static final Set<Listener>     LISTENERS = new CopyOnWriteArraySet<>();
    private static final Handler           MAIN      = new Handler( Looper.getMainLooper() );

    /** Seconds until the next check-in, as the CMS asked (default 5 minutes) */
    private static volatile int intervalSec = 300;

    /** Not instantiated */
    private CheckInManager() {
    }

    /**
     * @param listener Screen to tell after each check-in
     */
    public static void addListener( Listener listener ) {
        LISTENERS.add( listener );
    }

    /**
     * @param listener Screen that no longer wants to know
     */
    public static void removeListener( Listener listener ) {
        LISTENERS.remove( listener );
    }

    /**
     * @return Interval the CMS asked for, in seconds (60 to 3600)
     */
    public static int getIntervalSec() {
        return intervalSec;
    }

    /**
     * Starts a check-in in the background (ignored while one is running)
     *
     * @param context Context
     * @param force   true for "Check now": APKs that failed are tried again
     */
    public static void checkIn( Context context, final boolean force ) {
        final Context app = context.getApplicationContext();
        if( !RUNNING.compareAndSet( false, true ) ) {
            return;
        }
        EXECUTOR.execute( new Runnable() {
            /**
             * Runs the check-in and tells the screens
             */
            @Override
            public void run() {
                try {
                    runCheckIn( app, force );
                }
                catch( Exception e ) {
                    Log.e( TAG, "Check-in crashed", e );
                    new Prefs( app ).setLastResult( stamp() + " Failed: " + e.getMessage(), false );
                }
                finally {
                    RUNNING.set( false );
                    CheckInScheduler.scheduleNext( app, intervalSec );
                    notifyListeners();
                }
            }
        } );
    }

    /**
     * One check-in: CMS, else eslink for a new CMS address and the CMS once more
     *
     * @param context Application context
     * @param force   Retry failed APKs
     */
    private static void runCheckIn( Context context, boolean force ) {
        Prefs prefs = new Prefs( context );
        if( force ) {
            prefs.clearFailedApks();
        }
        resolvePendingInstalls( context, prefs );

        String error;
        try {
            handleAnswer( context, prefs, callCms( context, prefs ) );
            return;
        }
        catch( CmsRefused e ) {                       // The CMS answered: no eslink fallback
            prefs.setLastResult( stamp() + " CMS refused: " + e.getMessage(), false );
            return;
        }
        catch( IOException e ) {
            error = e.getMessage();
        }

        // The CMS cannot be reached: ask eslink where it is now
        String note = askEslink( prefs );
        if( note == null ) {                          // eslink gave an address: once more
            try {
                handleAnswer( context, prefs, callCms( context, prefs ) );
                return;
            }
            catch( CmsRefused e ) {
                prefs.setLastResult( stamp() + " CMS refused: " + e.getMessage(), false );
                return;
            }
            catch( IOException e ) {
                error = e.getMessage();
                note  = "eslink gave " + prefs.getCmsHost() + ":" + prefs.getCmsPort() + ", also unreachable";
            }
        }
        prefs.setLastResult( stamp() + " CMS " + prefs.getCmsHost() + ":" + prefs.getCmsPort() + " unreachable (" + error + "). " + note, false );
    }

    /** The CMS answered with an error */
    private static final class CmsRefused extends Exception {
        /**
         * @param message CMS error
         */
        CmsRefused( String message ) {
            super( message );
        }
    }

    /**
     * Sends the check-in to the CMS
     *
     * @param context Context
     * @param prefs   Stored state
     * @return CMS answer (ok = true)
     * @throws IOException When the CMS cannot be reached (or is not a CMS)
     * @throws CmsRefused  When the CMS answered with an error
     */
    private static JSONObject callCms( Context context, Prefs prefs ) throws IOException, CmsRefused {
        JSONObject request = buildRequest( context, prefs );
        Http.Response r = Http.postJson( prefs.getCmsBaseUrl() + "/api/box/checkin", request.toString() );
        JSONObject answer;
        try {
            answer = new JSONObject( r.body );
        }
        catch( JSONException e ) {
            throw new IOException( "HTTP " + r.status + ", not a CMS answer" );
        }
        if( r.status != 200 || !answer.optBoolean( "ok" ) ) {
            if( r.status == 404 || r.status >= 500 && !answer.has( "error" ) ) {
                throw new IOException( "HTTP " + r.status );
            }
            throw new CmsRefused( answer.optString( "error", "HTTP " + r.status ) );
        }
        prefs.removeApkResults( request.optJSONArray( "apk_results" ) );
        return answer;
    }

    /**
     * The check-in body
     *
     * @param context Context
     * @param prefs   Stored state
     * @return JSON request
     * @throws IOException When the box has no MAC (nothing to identify it by)
     */
    private static JSONObject buildRequest( Context context, Prefs prefs ) throws IOException {
        String mac = DeviceInfo.getMac( context );
        if( mac.isEmpty() ) {
            throw new IOException( "no MAC address or Android ID readable" );
        }
        try {
            // Versions of every button package and every APK package of the last answer
            Set<String> packages = new LinkedHashSet<>();
            for( HomeButton b : prefs.getButtons() ) {
                packages.add( b.packageName );
            }
            JSONArray apkPackages = prefs.getApkPackages();
            for( int i = 0; i < apkPackages.length(); i++ ) {
                packages.add( apkPackages.optString( i ) );
            }
            packages.remove( context.getPackageName() );
            packages.remove( "" );
            JSONArray apps = new JSONArray();
            for( String p : packages ) {
                apps.put( new JSONObject()
                        .put( "package", p )
                        .put( "version_code", ApkInstaller.installedVersionCode( context, p ) )
                        .put( "version_name", ApkInstaller.installedVersionName( context, p ) ) );
            }
            return new JSONObject()
                    .put( "protocol", PROTOCOL )
                    .put( "mac", mac )
                    .put( "ip", DeviceInfo.getIp() )
                    .put( "box_name", prefs.getBoxName() )
                    .put( "launcher", new JSONObject().put( "version_code", BuildConfig.VERSION_CODE ).put( "version_name", BuildConfig.VERSION_NAME ) )
                    .put( "apps", apps )
                    .put( "buttons_source", prefs.getButtonsSource() )
                    .put( "buttons_rev", prefs.getButtonsRev() )
                    .put( "apk_results", prefs.getApkResults() )
                    .put( "firmware", DeviceInfo.getFirmware() )
                    .put( "android", Build.VERSION.RELEASE );
        }
        catch( JSONException e ) {
            throw new IOException( "request could not be built" );
        }
    }

    /**
     * Applies a CMS answer: property, buttons, wallpaper, APKs
     *
     * @param context Context
     * @param prefs   Stored state
     * @param a       CMS answer
     */
    private static void handleAnswer( Context context, Prefs prefs, JSONObject a ) {
        int interval = a.optInt( "interval_sec", 300 );
        intervalSec = Math.max( 60, Math.min( 3600, interval ) );

        String propertyId = a.optString( "property_id", "" );
        if( !propertyId.isEmpty() ) {
            prefs.setProperty( propertyId, a.optString( "property_name", "" ) );
        }

        // Buttons: a new CMS revision replaces the list, even one changed on this box (newest change wins)
        JSONObject buttons = a.optJSONObject( "buttons" );
        if( buttons != null ) {
            int rev = buttons.optInt( "rev", -1 );
            if( rev != prefs.getButtonsRev() ) {
                prefs.setCmsButtons( HomeButton.listFromJson( buttons.optJSONArray( "list" ) ), rev );
            }
        }

        String wallpaperNote = applyWallpaper( context, prefs, a.optJSONObject( "wallpaper" ) );
        String apkNote       = applyApks( context, prefs, a.optJSONArray( "apks" ) );

        prefs.setLastResult( stamp() + " OK (CMS " + prefs.getCmsHost() + ":" + prefs.getCmsPort() + ")" + wallpaperNote + apkNote, true );
    }

    /**
     * Downloads the wallpaper when its hash changed; null = back to the built-in default
     *
     * @param context   Context
     * @param prefs     Stored state
     * @param wallpaper { hash, url } or null
     * @return Note for the result text ("" when nothing happened)
     */
    private static String applyWallpaper( Context context, Prefs prefs, JSONObject wallpaper ) {
        File file = new File( context.getFilesDir(), WALLPAPER_FILE );
        if( wallpaper == null ) {
            if( !prefs.getWallpaperHash().isEmpty() ) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                prefs.setWallpaperHash( "" );
                return ". Wallpaper: default";
            }
            return "";
        }
        String hash = wallpaper.optString( "hash" ).toLowerCase( Locale.US );
        if( hash.isEmpty() || ( hash.equals( prefs.getWallpaperHash() ) && file.exists() ) ) {
            return "";
        }
        File tmp = new File( context.getFilesDir(), WALLPAPER_FILE + ".new" );
        try {
            String got = Http.download( absolute( prefs, wallpaper.optString( "url" ) ), tmp );
            if( !got.equals( hash ) ) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return ". Wallpaper download damaged";
            }
            if( ( file.exists() && !file.delete() ) || !tmp.renameTo( file ) ) {
                return ". Wallpaper could not be saved";
            }
            prefs.setWallpaperHash( hash );
            return ". New wallpaper";
        }
        catch( IOException e ) {
            return ". Wallpaper download failed (" + e.getMessage() + ")";
        }
    }

    /**
     * Installs each offered APK that is newer than (or missing from) the box. The launcher itself is done
     * last, because installing it ends this process.
     *
     * @param context Context
     * @param prefs   Stored state
     * @param apks    Offered APKs, may be null
     * @return Note for the result text
     */
    private static String applyApks( Context context, Prefs prefs, JSONArray apks ) {
        JSONArray packages = new JSONArray();
        if( apks == null ) {
            prefs.setApkPackages( packages );
            return "";
        }
        for( int i = 0; i < apks.length(); i++ ) {
            JSONObject apk = apks.optJSONObject( i );
            if( apk != null ) {
                packages.put( apk.optString( "package" ) );
            }
        }
        prefs.setApkPackages( packages );

        int installed = 0;
        int failed    = 0;
        JSONObject self = null;
        for( int i = 0; i < apks.length(); i++ ) {
            JSONObject apk = apks.optJSONObject( i );
            if( apk == null || !needsInstall( context, prefs, apk ) ) {
                continue;
            }
            if( context.getPackageName().equals( apk.optString( "package" ) ) ) {
                self = apk;
                continue;
            }
            if( installOne( context, prefs, apk ) ) {
                installed++;
            }
            else {
                failed++;
            }
        }
        if( self != null ) {
            installOne( context, prefs, self );            // Ends the process on success
        }
        return ( installed > 0 ? ". Installed " + installed + " APK(s)" : "" ) + ( failed > 0 ? ". " + failed + " APK install(s) failed" : "" );
    }

    /**
     * Whether an offered APK should be installed: newer than the installed version (or not installed),
     * not failed before with the same apk_id, and not already waiting for its outcome
     *
     * @param context Context
     * @param prefs   Stored state
     * @param apk     Offered APK
     * @return true to install
     */
    private static boolean needsInstall( Context context, Prefs prefs, JSONObject apk ) {
        String pkg   = apk.optString( "package" );
        int    apkId = apk.optInt( "apk_id" );
        if( pkg.isEmpty() || apkId <= 0 ) {
            return false;
        }
        long have = ApkInstaller.installedVersionCode( context, pkg );
        if( have > 0 && have >= apk.optLong( "version_code" ) ) {
            return false;
        }
        if( prefs.getFailedApks().optInt( pkg, -1 ) == apkId ) {
            return false;
        }
        return !prefs.getPendingInstalls().has( String.valueOf( apkId ) );
    }

    /**
     * Downloads, checks and installs one APK, and queues its result for the next check-in
     *
     * @param context Context
     * @param prefs   Stored state
     * @param apk     Offered APK
     * @return true when installed (or handed to the installer)
     */
    private static boolean installOne( Context context, Prefs prefs, JSONObject apk ) {
        String pkg   = apk.optString( "package" );
        int    apkId = apk.optInt( "apk_id" );
        File   dir   = new File( context.getFilesDir(), ApkInstaller.APK_DIR );
        if( !dir.isDirectory() && !dir.mkdirs() ) {
            return fail( prefs, pkg, apkId, "cannot create the download folder" );
        }
        File file = new File( dir, "apk_" + apkId + ".apk" );
        try {
            String got = Http.download( absolute( prefs, apk.optString( "url" ) ), file );
            if( !got.equalsIgnoreCase( apk.optString( "sha256" ) ) ) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return fail( prefs, pkg, apkId, "download damaged (SHA-256 differs)" );
            }
        }
        catch( IOException e ) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return fail( prefs, pkg, apkId, "download failed: " + e.getMessage() );
        }

        boolean isSelf = context.getPackageName().equals( pkg );
        if( isSelf ) {                                 // The outcome is checked when the new version starts
            addPending( prefs, apkId, pkg, apk.optLong( "version_code" ) );
        }
        ApkInstaller.Outcome outcome = ApkInstaller.install( context, file );
        switch( outcome ) {
            case INSTALLED:
                removePending( prefs, apkId );
                prefs.addApkResult( apkId, "installed", "" );
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return true;
            case HANDED_TO_INSTALLER:
                addPending( prefs, apkId, pkg, apk.optLong( "version_code" ) );
                return true;                           // File kept for the installer
            default:
                removePending( prefs, apkId );
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return fail( prefs, pkg, apkId, ApkInstaller.getLastMessage() );
        }
    }

    /**
     * Records a failed install (not retried until another apk_id or "Check now")
     *
     * @param prefs   Stored state
     * @param pkg     Package
     * @param apkId   apk_id
     * @param message Reason
     * @return false
     */
    private static boolean fail( Prefs prefs, String pkg, int apkId, String message ) {
        Log.w( TAG, "APK " + apkId + " (" + pkg + ") failed: " + message );
        prefs.setFailedApk( pkg, apkId );
        prefs.addApkResult( apkId, "failed", message );
        return false;
    }

    /**
     * Settles installs whose outcome was not known at once (self-update, Android installer):
     * installed when the package now has at least the offered version
     *
     * @param context Context
     * @param prefs   Stored state
     */
    static void resolvePendingInstalls( Context context, Prefs prefs ) {
        JSONObject pending = prefs.getPendingInstalls();
        if( pending.length() == 0 ) {
            return;
        }
        JSONObject left = new JSONObject();
        Iterator<String> keys = pending.keys();
        while( keys.hasNext() ) {
            String     key   = keys.next();
            JSONObject p     = pending.optJSONObject( key );
            int        apkId = Integer.parseInt( key );
            if( p == null ) {
                continue;
            }
            String pkg = p.optString( "package" );
            if( ApkInstaller.installedVersionCode( context, pkg ) >= p.optLong( "version_code" ) ) {
                prefs.addApkResult( apkId, "installed", "" );
                new File( new File( context.getFilesDir(), ApkInstaller.APK_DIR ), "apk_" + apkId + ".apk" ).delete();
            }
            else if( System.currentTimeMillis() - p.optLong( "since" ) > 30 * 60 * 1000L ) {   // Given up after 30 minutes
                fail( prefs, pkg, apkId, "not installed (the installer did not finish)" );
                new File( new File( context.getFilesDir(), ApkInstaller.APK_DIR ), "apk_" + apkId + ".apk" ).delete();
            }
            else {
                try {
                    left.put( key, p );
                }
                catch( JSONException ignored ) {
                    // Copying an existing object
                }
            }
        }
        prefs.setPendingInstalls( left );
    }

    /**
     * @param prefs       Stored state
     * @param apkId       apk_id
     * @param pkg         Package
     * @param versionCode Offered versionCode
     */
    private static void addPending( Prefs prefs, int apkId, String pkg, long versionCode ) {
        JSONObject pending = prefs.getPendingInstalls();
        try {
            pending.put( String.valueOf( apkId ), new JSONObject()
                    .put( "package", pkg ).put( "version_code", versionCode ).put( "since", System.currentTimeMillis() ) );
        }
        catch( JSONException ignored ) {
            // Plain values only
        }
        prefs.setPendingInstalls( pending );
    }

    /**
     * @param prefs Stored state
     * @param apkId apk_id no longer pending
     */
    private static void removePending( Prefs prefs, int apkId ) {
        JSONObject pending = prefs.getPendingInstalls();
        pending.remove( String.valueOf( apkId ) );
        prefs.setPendingInstalls( pending );
    }

    /**
     * Asks eslink for the CMS address and stores it
     *
     * @param prefs Stored state
     * @return null when eslink gave an address; otherwise why it could not help
     */
    private static String askEslink( Prefs prefs ) {
        if( prefs.getPropertyId().isEmpty() ) {
            return "No property ID yet, so eslink cannot be asked (the box must reach its CMS once).";
        }
        if( !EslinkClient.isConfigured() ) {
            return "This build has no eslink app ID / secret.";
        }
        if( prefs.getSecretRefusedFor() == BuildConfig.VERSION_CODE ) {
            return "eslink refused this launcher's app secret earlier; not asked again until the launcher is updated.";
        }
        try {
            EslinkClient.CmsAddress cms = EslinkClient.getCms( prefs.getPropertyId() );
            prefs.setCms( cms.host, cms.port );
            return null;
        }
        catch( EslinkClient.Refused e ) {
            if( e.secretRefused ) {
                prefs.setSecretRefusedFor( BuildConfig.VERSION_CODE );
            }
            return "eslink: " + e.getMessage();
        }
        catch( IOException e ) {
            return "eslink unreachable (" + e.getMessage() + ")";
        }
    }

    /**
     * A CMS path ("/api/...") as a full URL; full URLs are kept
     *
     * @param prefs Stored state
     * @param url   Path or URL
     * @return Full URL
     */
    private static String absolute( Prefs prefs, String url ) {
        if( url.startsWith( "http://" ) || url.startsWith( "https://" ) ) {
            return url;
        }
        return prefs.getCmsBaseUrl() + ( url.startsWith( "/" ) ? "" : "/" ) + url;
    }

    /**
     * Current time for the result text
     *
     * @return e.g. "19 Nov 14:05"
     */
    private static String stamp() {
        return new SimpleDateFormat( "d MMM HH:mm", Locale.US ).format( new Date() );
    }

    /**
     * Tells the screens, on the main thread
     */
    private static void notifyListeners() {
        MAIN.post( new Runnable() {
            /**
             * Calls each listener
             */
            @Override
            public void run() {
                for( Listener l : LISTENERS ) {
                    l.onCheckInFinished();
                }
            }
        } );
    }

    /**
     * Saves buttons changed in the Secret Menu (kept until the CMS revision changes)
     *
     * @param context Context
     * @param list    Buttons
     */
    public static void saveLocalButtons( Context context, List<HomeButton> list ) {
        new Prefs( context ).setLocalButtons( list );
        notifyListeners();
    }
}
