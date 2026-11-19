package com.excel.estvlauncher.custom;

import android.annotation.SuppressLint;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Screen size (overscan margin %) and resolution (output mode) of the box, through the firmware's
 * android.os.DisplayOutputManager - the same API esTVLauncher's "Adjust Screen Size" uses (DisplaySetting).
 * The launcher is signed with the platform key, which this API needs. On a box without the API every method
 * reports "not available" and nothing is changed.
 */
public final class DisplayHelper {

    private static final String TAG = "DisplayHelper";

    /** Display 0 (the main output) */
    private static final int DISPLAY_MAIN = 0;

    /** DisplayOutputManager.DISPLAY_OUTPUT_TYPE_HDMI */
    private static final int OUTPUT_TYPE_HDMI = 4;

    /** Smallest / largest screen size the CMS may set */
    public static final int MIN_SCALE = 80;
    public static final int MAX_SCALE = 100;

    /** Firmware mode code -> name, as esTVLauncher's DisplaySetting / OutputMode (the names are what the CMS shows) */
    private static final Map<Integer, String> MODES = new LinkedHashMap<>();
    static {
        MODES.put( 255, "AUTO" );
        MODES.put( 0, "480I" );
        MODES.put( 1, "576I" );
        MODES.put( 2, "480P" );
        MODES.put( 3, "576P" );
        MODES.put( 4, "720P 50Hz" );
        MODES.put( 5, "720P 60Hz" );
        MODES.put( 6, "1080I 50Hz" );
        MODES.put( 7, "1080I 60Hz" );
        MODES.put( 8, "1080P 24Hz" );
        MODES.put( 9, "1080P 50Hz" );
        MODES.put( 10, "1080P 60Hz" );
        MODES.put( 11, "1080p 24Hz (3D FP)" );
        MODES.put( 12, "1080P 25Hz" );
        MODES.put( 13, "1080P 30Hz" );
        MODES.put( 14, "PAL" );
        MODES.put( 15, "PAL SVIDEO" );
        MODES.put( 16, "NTSC" );
        MODES.put( 17, "NTSC SVIDEO" );
        MODES.put( 18, "PAL M" );
        MODES.put( 19, "PAL M SVIDEO" );
        MODES.put( 20, "PAL NC" );
        MODES.put( 21, "PAL NC SVIDEO" );
        MODES.put( 22, "4K2K 30HZ" );
        MODES.put( 29, "4K2K 25HZ" );
        MODES.put( 30, "4K2K 24HZ" );
        MODES.put( 31, "4096x2160P 24HZ" );
        MODES.put( 32, "4096x2160P 25HZ" );
        MODES.put( 33, "4096x2160P 30HZ" );
        MODES.put( 34, "4K2K 60HZ" );
        MODES.put( 35, "4096x2160P 60HZ" );
        MODES.put( 36, "4K2K 50HZ" );
        MODES.put( 37, "4096x2160P 50HZ" );
    }

    /** Not instantiated */
    private DisplayHelper() {
    }

    /**
     * A new DisplayOutputManager and its class, or null when the firmware has none
     *
     * @return [ manager, class ] or null
     */
    @SuppressLint( "PrivateApi" )
    private static Object[] manager() {
        try {
            Class<?> clazz = Class.forName( "android.os.DisplayOutputManager" );
            return new Object[]{ clazz.getConstructor().newInstance(), clazz };
        }
        catch( Throwable e ) {
            return null;
        }
    }

    /**
     * Calls a method of DisplayOutputManager
     *
     * @param name  Method name
     * @param types Parameter types
     * @param args  Arguments
     * @return Result, or null when the API or the method is missing or fails
     */
    private static Object call( String name, Class<?>[] types, Object... args ) {
        Object[] m = manager();
        if( m == null ) {
            return null;
        }
        try {
            Method method = ( (Class<?>) m[ 1 ] ).getDeclaredMethod( name, types );
            return method.invoke( m[ 0 ], args );
        }
        catch( Throwable e ) {
            Log.w( TAG, name + " failed: " + e );
            return null;
        }
    }

    /**
     * @return true when the firmware has the display API
     */
    public static boolean isAvailable() {
        return manager() != null;
    }

    /**
     * @return Current screen size in % (80-100), or -1 when unknown
     */
    public static int getScale() {
        Object r = call( "getDisplayMargin", new Class<?>[]{ int.class }, DISPLAY_MAIN );
        return r instanceof int[] && ( (int[]) r ).length > 0 ? ( (int[]) r )[ 0 ] : -1;
    }

    /**
     * @return Current resolution name (e.g. "1080P 60Hz"), or "" when unknown
     */
    public static String getMode() {
        Object r = call( "getDisplayOutputMode", new Class<?>[]{ int.class }, DISPLAY_MAIN );
        if( !( r instanceof Integer ) ) {
            return "";
        }
        String name = MODES.get( (Integer) r );
        return name != null ? name : "";
    }

    /**
     * Resolutions the connected TV supports. As the firmware's own android.base.api.settings.DisplaySetting does:
     * find the display (0 or 1) whose output type is HDMI, then getSupportModes( thatDisplay, 0 ).
     *
     * @return Names; empty when unknown
     */
    public static JSONArray getSupportedModes() {
        JSONArray names = new JSONArray();
        int display = DISPLAY_MAIN;
        for( int i = 0; i < 2; i++ ) {
            Object type = call( "getDisplayOutputType", new Class<?>[]{ int.class }, i );
            if( type instanceof Integer && (Integer) type == OUTPUT_TYPE_HDMI ) {
                display = i;
                break;
            }
        }
        Object r = call( "getSupportModes", new Class<?>[]{ int.class, int.class }, display, 0 );
        if( r instanceof int[] ) {
            for( int code : (int[]) r ) {
                String name = MODES.get( code );
                if( name != null ) {
                    names.put( name );
                }
            }
        }
        return names;
    }

    /**
     * Sets the screen size
     *
     * @param scale 80-100 %
     * @return true when the firmware accepted it
     */
    public static boolean setScale( int scale ) {
        if( scale < MIN_SCALE || scale > MAX_SCALE ) {
            return false;
        }
        call( "setDisplayMargin", new Class<?>[]{ int.class, int.class, int.class }, DISPLAY_MAIN, scale, scale );
        boolean ok = getScale() == scale;             // A void method: confirmed by reading it back
        Log.i( TAG, "Screen size " + scale + "%: " + ( ok ? "set" : "failed" ) );
        return ok;
    }

    /**
     * Sets the resolution
     *
     * @param name Resolution name from {@link #getSupportedModes()}
     * @return true when the box now reports that resolution
     */
    public static boolean setMode( String name ) {
        for( Map.Entry<Integer, String> e : MODES.entrySet() ) {
            if( e.getValue().equals( name ) ) {
                call( "setDisplayOutputMode", new Class<?>[]{ int.class, int.class }, DISPLAY_MAIN, e.getKey() );
                boolean ok = name.equals( getMode() );
                Log.i( TAG, "Resolution " + name + ": " + ( ok ? "set" : "not confirmed" ) );
                return ok;
            }
        }
        Log.w( TAG, "Unknown resolution " + name );
        return false;
    }

    /**
     * The box's display for the check-in
     *
     * @return { scale, mode, modes } or null when the firmware has no display API
     * @throws JSONException Never in practice
     */
    public static JSONObject report() throws JSONException {
        if( !isAvailable() ) {
            return null;
        }
        return new JSONObject()
                .put( "scale", getScale() )
                .put( "mode", getMode() )
                .put( "modes", getSupportedModes() );
    }
}
