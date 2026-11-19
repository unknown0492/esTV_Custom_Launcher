package com.excel.estvlauncher.custom;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Everything the launcher stores on the box (one SharedPreferences file)
 */
public final class Prefs {

    /** CMS address of a brand-new box (the technician can change it in the Secret Menu) */
    public static final String DEFAULT_CMS_HOST = "172.19.0.3";
    /** Port the CMS listens on */
    public static final int    DEFAULT_CMS_PORT = 4053;

    /** buttons_source: the box shows the CMS list */
    public static final String SOURCE_CMS   = "cms";
    /** buttons_source: changed in the Secret Menu */
    public static final String SOURCE_LOCAL = "local";

    private static final String FILE = "custom_launcher";

    private static final String K_CMS_HOST         = "cms_host";
    private static final String K_CMS_PORT         = "cms_port";
    private static final String K_BOX_NAME         = "box_name";
    private static final String K_PROPERTY_ID      = "property_id";
    private static final String K_PROPERTY_NAME    = "property_name";
    private static final String K_BUTTONS          = "buttons";
    private static final String K_BUTTONS_SOURCE   = "buttons_source";
    private static final String K_BUTTONS_REV      = "buttons_rev";
    private static final String K_WALLPAPER_HASH   = "wallpaper_hash";
    private static final String K_LAST_RESULT      = "last_checkin_result";
    private static final String K_LAST_OK_AT       = "last_checkin_ok_at";
    private static final String K_APK_RESULTS      = "apk_results";
    private static final String K_APK_PACKAGES     = "apk_packages";
    private static final String K_FAILED_APKS      = "failed_apks";
    private static final String K_PENDING_INSTALLS = "pending_installs";
    private static final String K_SECRET_REFUSED   = "eslink_secret_refused_for";
    private static final String K_DISPLAY_APPLIED  = "display_applied";

    private final SharedPreferences sp;

    /**
     * @param context Any context
     */
    public Prefs( Context context ) {
        sp = context.getApplicationContext().getSharedPreferences( FILE, Context.MODE_PRIVATE );
    }

    /** @return CMS host (default 172.19.0.3) */
    public String getCmsHost() {
        return sp.getString( K_CMS_HOST, DEFAULT_CMS_HOST );
    }

    /** @return CMS port (default 4053) */
    public int getCmsPort() {
        return sp.getInt( K_CMS_PORT, DEFAULT_CMS_PORT );
    }

    /**
     * @param host CMS IP or host name
     * @param port CMS port
     */
    public void setCms( String host, int port ) {
        sp.edit().putString( K_CMS_HOST, host ).putInt( K_CMS_PORT, port ).apply();
    }

    /** @return Base URL of the CMS, e.g. "http://172.19.0.3:4053" */
    public String getCmsBaseUrl() {
        String host = getCmsHost();
        if( host.contains( ":" ) && !host.startsWith( "[" ) ) {
            host = "[" + host + "]";                 // IPv6 literal
        }
        return "http://" + host + ":" + getCmsPort();
    }

    /** @return Box name set in the Secret Menu ("" = none) */
    public String getBoxName() {
        return sp.getString( K_BOX_NAME, "" );
    }

    /** @param name Box name */
    public void setBoxName( String name ) {
        sp.edit().putString( K_BOX_NAME, name ).apply();
    }

    /** @return Property ID pulled from the CMS ("" until the first check-in) */
    public String getPropertyId() {
        return sp.getString( K_PROPERTY_ID, "" );
    }

    /** @return Property name pulled from the CMS */
    public String getPropertyName() {
        return sp.getString( K_PROPERTY_NAME, "" );
    }

    /**
     * @param id   Property ID
     * @param name Property name
     */
    public void setProperty( String id, String name ) {
        sp.edit().putString( K_PROPERTY_ID, id ).putString( K_PROPERTY_NAME, name ).apply();
    }

    /** @return Home buttons (without Menu) */
    public List<HomeButton> getButtons() {
        return HomeButton.listFromJson( readArray( K_BUTTONS ) );
    }

    /** @return "cms" or "local" */
    public String getButtonsSource() {
        return sp.getString( K_BUTTONS_SOURCE, SOURCE_CMS );
    }

    /** @return CMS buttons revision last applied (-1 = none yet) */
    public int getButtonsRev() {
        return sp.getInt( K_BUTTONS_REV, -1 );
    }

    /**
     * Buttons from the CMS: replaces the list, source becomes "cms"
     *
     * @param list Buttons
     * @param rev  CMS revision
     */
    public void setCmsButtons( List<HomeButton> list, int rev ) {
        sp.edit().putString( K_BUTTONS, HomeButton.listToJson( list ).toString() )
                 .putString( K_BUTTONS_SOURCE, SOURCE_CMS )
                 .putInt( K_BUTTONS_REV, rev )
                 .apply();
    }

    /**
     * Buttons changed in the Secret Menu: kept until the CMS revision changes
     *
     * @param list Buttons
     */
    public void setLocalButtons( List<HomeButton> list ) {
        sp.edit().putString( K_BUTTONS, HomeButton.listToJson( list ).toString() )
                 .putString( K_BUTTONS_SOURCE, SOURCE_LOCAL )
                 .apply();
    }

    /** Forgets the applied CMS revision, so the next check-in brings the CMS list back */
    public void resetButtonsToCms() {
        sp.edit().putInt( K_BUTTONS_REV, -1 ).apply();
    }

    /** @return SHA-256 of the cached wallpaper ("" = built-in default) */
    public String getWallpaperHash() {
        return sp.getString( K_WALLPAPER_HASH, "" );
    }

    /** @param hash SHA-256 of the cached wallpaper, "" for the default */
    public void setWallpaperHash( String hash ) {
        sp.edit().putString( K_WALLPAPER_HASH, hash ).apply();
    }

    /** @return Text of the last check-in result, for the Secret Menu */
    public String getLastResult() {
        return sp.getString( K_LAST_RESULT, "Not checked in yet" );
    }

    /** @return Epoch ms of the last successful check-in (0 = never) */
    public long getLastOkAt() {
        return sp.getLong( K_LAST_OK_AT, 0 );
    }

    /**
     * @param text Result text
     * @param ok   true when the CMS answered ok
     */
    public void setLastResult( String text, boolean ok ) {
        SharedPreferences.Editor e = sp.edit().putString( K_LAST_RESULT, text );
        if( ok ) {
            e.putLong( K_LAST_OK_AT, System.currentTimeMillis() );
        }
        e.apply();
    }

    /** @return Install results not yet delivered to the CMS */
    public JSONArray getApkResults() {
        return readArray( K_APK_RESULTS );
    }

    /**
     * Queues an install result for the next check-in (a newer result for the same apk_id replaces it)
     *
     * @param apkId   CMS APK id
     * @param state   "installed" or "failed"
     * @param message Reason when failed
     */
    public synchronized void addApkResult( int apkId, String state, String message ) {
        JSONArray old = getApkResults();
        JSONArray now = new JSONArray();
        for( int i = 0; i < old.length(); i++ ) {
            JSONObject o = old.optJSONObject( i );
            if( o != null && o.optInt( "apk_id" ) != apkId ) {
                now.put( o );
            }
        }
        try {
            now.put( new JSONObject().put( "apk_id", apkId ).put( "state", state ).put( "message", message == null ? "" : message ) );
        }
        catch( JSONException ignored ) {
            // Plain values only
        }
        sp.edit().putString( K_APK_RESULTS, now.toString() ).apply();
    }

    /**
     * Removes the results the CMS has received
     *
     * @param sent The array that was sent
     */
    public synchronized void removeApkResults( JSONArray sent ) {
        JSONArray old = getApkResults();
        JSONArray now = new JSONArray();
        for( int i = 0; i < old.length(); i++ ) {
            JSONObject o = old.optJSONObject( i );
            if( o != null && !containsSame( sent, o ) ) {
                now.put( o );
            }
        }
        sp.edit().putString( K_APK_RESULTS, now.toString() ).apply();
    }

    /**
     * Whether an array holds an object with the same apk_id and state
     *
     * @param array Array
     * @param o     Result
     * @return true when found
     */
    private static boolean containsSame( JSONArray array, JSONObject o ) {
        for( int i = 0; i < array.length(); i++ ) {
            JSONObject x = array.optJSONObject( i );
            if( x != null && x.optInt( "apk_id" ) == o.optInt( "apk_id" ) && x.optString( "state" ).equals( o.optString( "state" ) ) ) {
                return true;
            }
        }
        return false;
    }

    /** @return Packages of the APKs in the last CMS answer (their versions are reported) */
    public JSONArray getApkPackages() {
        return readArray( K_APK_PACKAGES );
    }

    /** @param packages Packages of the last CMS answer */
    public void setApkPackages( JSONArray packages ) {
        sp.edit().putString( K_APK_PACKAGES, packages.toString() ).apply();
    }

    /** @return package -> apk_id that failed (not retried until the CMS offers another apk_id) */
    public JSONObject getFailedApks() {
        return readObject( K_FAILED_APKS );
    }

    /**
     * @param packageName Package
     * @param apkId       apk_id that failed
     */
    public synchronized void setFailedApk( String packageName, int apkId ) {
        JSONObject o = getFailedApks();
        try {
            o.put( packageName, apkId );
        }
        catch( JSONException ignored ) {
            // Plain values only
        }
        sp.edit().putString( K_FAILED_APKS, o.toString() ).apply();
    }

    /** Clears the failed list ("Check now" retries everything) */
    public void clearFailedApks() {
        sp.edit().remove( K_FAILED_APKS ).apply();
    }

    /** @return The CMS display target last applied ("scale|mode"), so each target is applied once */
    public String getDisplayApplied() {
        return sp.getString( K_DISPLAY_APPLIED, "" );
    }

    /** @param key Display target just applied ("" = apply the next one again) */
    public void setDisplayApplied( String key ) {
        sp.edit().putString( K_DISPLAY_APPLIED, key ).apply();
    }

    /**
     * Installs whose outcome is only known later (the launcher replacing itself, or the Android installer):
     * apk_id -> { package, version_code }
     *
     * @return Pending installs
     */
    public JSONObject getPendingInstalls() {
        return readObject( K_PENDING_INSTALLS );
    }

    /** @param pending Pending installs */
    public synchronized void setPendingInstalls( JSONObject pending ) {
        sp.edit().putString( K_PENDING_INSTALLS, pending.toString() ).apply();
    }

    /** @return versionCode of this launcher build whose eslink secret was refused (0 = none) */
    public int getSecretRefusedFor() {
        return sp.getInt( K_SECRET_REFUSED, 0 );
    }

    /** @param versionCode This build's versionCode (0 clears) */
    public void setSecretRefusedFor( int versionCode ) {
        sp.edit().putInt( K_SECRET_REFUSED, versionCode ).apply();
    }

    /**
     * @param key Preference key
     * @return Stored JSON array, or an empty one
     */
    private JSONArray readArray( String key ) {
        try {
            return new JSONArray( sp.getString( key, "[]" ) );
        }
        catch( JSONException e ) {
            return new JSONArray();
        }
    }

    /**
     * @param key Preference key
     * @return Stored JSON object, or an empty one
     */
    private JSONObject readObject( String key ) {
        try {
            return new JSONObject( sp.getString( key, "{}" ) );
        }
        catch( JSONException e ) {
            return new JSONObject();
        }
    }
}
