package com.excel.estvlauncher.custom;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Presses Install / Allow on the Android package installer and permission dialogs, so an APK update or a
 * permission prompt never waits for someone with a remote. Based on AccessibilityServicePermission's
 * PermissionAutoAllowService, but limited to the installer and permission controller packages (the
 * config XML also lists only these), so it never clicks inside any other app.
 */
public class PermissionAutoAllowService extends AccessibilityService {

    private static final String TAG = "CL.AutoAllow";

    /** The only windows the service acts on */
    static final List<String> PACKAGES = Arrays.asList(
            "com.android.packageinstaller",                 // Android 5 - 9
            "com.google.android.packageinstaller",          // GMS builds
            "com.android.permissioncontroller",             // Android 10+
            "com.google.android.permissioncontroller"       // GMS builds, Android 10+
    );

    /** Button labels pressed (whole label, case-insensitive), in order of preference */
    private static final List<String> LABELS = Arrays.asList(
            "install", "update", "allow", "allow all the time", "allow only while using the app",
            "while using the app", "always allow", "done"
    );

    /**
     * Logs that the service is on
     */
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i( TAG, "Connected" );
    }

    /**
     * Required by AccessibilityService; nothing to stop
     */
    @Override
    public void onInterrupt() {
    }

    /**
     * Looks for a known button in installer / permission windows and presses it
     *
     * @param event Window event
     */
    @Override
    public void onAccessibilityEvent( AccessibilityEvent event ) {
        if( event.getPackageName() == null || !PACKAGES.contains( event.getPackageName().toString() ) ) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if( root == null || root.getPackageName() == null || !PACKAGES.contains( root.getPackageName().toString() ) ) {
            return;
        }
        for( String label : LABELS ) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText( label );
            if( nodes == null ) {
                continue;
            }
            for( AccessibilityNodeInfo node : nodes ) {
                CharSequence text = node.getText();
                if( text == null || !text.toString().trim().toLowerCase( Locale.ROOT ).equals( label ) || !node.isEnabled() ) {
                    continue;
                }
                AccessibilityNodeInfo target = clickable( node );
                if( target != null ) {
                    Log.i( TAG, "Pressing \"" + text + "\" in " + root.getPackageName() );
                    target.performAction( AccessibilityNodeInfo.ACTION_CLICK );
                    return;
                }
            }
        }
    }

    /**
     * The node itself or its nearest clickable parent
     *
     * @param node Node with the label
     * @return Clickable node, or null
     */
    private static AccessibilityNodeInfo clickable( AccessibilityNodeInfo node ) {
        AccessibilityNodeInfo n = node;
        while( n != null ) {
            if( n.isClickable() ) {
                return n;
            }
            n = n.getParent();
        }
        return null;
    }

    /**
     * This service as Settings.Secure writes it, e.g. "com.excel.estvlauncher.custom/....PermissionAutoAllowService"
     *
     * @param context Context
     * @return Flattened component name
     */
    static String componentName( Context context ) {
        return new ComponentName( context, PermissionAutoAllowService.class ).flattenToString();
    }

    /**
     * Whether the service is switched on in Accessibility settings
     *
     * @param context Context
     * @return true when on
     */
    public static boolean isEnabled( Context context ) {
        String enabled = Settings.Secure.getString( context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES );
        if( TextUtils.isEmpty( enabled ) ) {
            return false;
        }
        String me        = componentName( context );
        String shortName = new ComponentName( context, PermissionAutoAllowService.class ).flattenToShortString();
        for( String s : enabled.split( ":" ) ) {
            if( s.equalsIgnoreCase( me ) || s.equalsIgnoreCase( shortName ) ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Switches the service on with root: adds it to enabled_accessibility_services (keeping the services
     * already there) and sets accessibility_enabled to 1
     *
     * @param context Context
     * @return true when it is on afterwards
     */
    public static boolean enableWithRoot( Context context ) {
        if( isEnabled( context ) ) {
            return true;
        }
        String current = Settings.Secure.getString( context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES );
        String me      = componentName( context );
        String value   = ( TextUtils.isEmpty( current ) || "null".equals( current ) ) ? me : current + ":" + me;
        Shell.su( "settings put secure enabled_accessibility_services " + Shell.quote( value ),
                  "settings put secure accessibility_enabled 1" );
        return isEnabled( context );
    }
}
