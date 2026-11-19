package com.excel.estvlauncher.custom;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;

/**
 * Facts about the box: MAC (its identity on the CMS), IP, active interface, firmware.
 * Replaces the few ExcelClassLibrary helpers esTVLauncher uses, so this app stands alone.
 */
public final class DeviceInfo {

    /** Ethernet first, then WiFi - the order the MAC and the IP are looked for in */
    private static final String[] INTERFACES = { "eth0", "eth1", "wlan0" };

    /** Not instantiated */
    private DeviceInfo() {
    }

    /** Identity found once per process (reading it may need su, which is slow) */
    private static volatile String cachedMac = null;

    /**
     * The box's identity on the CMS: the Ethernet MAC when the box has one, else the WiFi MAC. Stable whichever
     * cable or WiFi is in use, so the CMS always sees the same box. When Android hides the MAC and there is no
     * root (Android 10+ without su), a stable locally administered address made from ANDROID_ID is used
     * instead, e.g. "02:4F:1A:...", so the box can still check in; see {@link #isRealMac(String)}.
     *
     * @param context Context (for ANDROID_ID)
     * @return Upper case, colon-separated, e.g. "AA:BB:CC:DD:EE:FF"; "" only when nothing at all is readable
     */
    public static String getMac( Context context ) {
        String mac = cachedMac;
        if( mac != null && !mac.isEmpty() ) {
            return mac;
        }
        mac = readRealMac();
        if( mac.isEmpty() ) {
            mac = macFromAndroidId( context );
        }
        cachedMac = mac;
        return mac;
    }

    /**
     * Whether an identity is the box's real MAC (and not the ANDROID_ID stand-in, which starts with 02:)
     *
     * @param mac From getMac
     * @return true for a real hardware MAC
     */
    public static boolean isRealMac( String mac ) {
        return !mac.isEmpty() && !mac.startsWith( "02:" );
    }

    /**
     * The real MAC: /sys (directly, else with su), then Java's NetworkInterface (works on older Android)
     *
     * @return MAC or ""
     */
    private static String readRealMac() {
        for( String name : INTERFACES ) {
            String mac = readMac( name );
            if( !mac.isEmpty() ) {
                return mac;
            }
        }
        for( String name : INTERFACES ) {
            try {
                NetworkInterface nif = NetworkInterface.getByName( name );
                byte[] hw = nif == null ? null : nif.getHardwareAddress();
                String mac = hw == null || hw.length != 6 ? "" : format( hw );
                if( !mac.isEmpty() && !mac.equals( "00:00:00:00:00:00" ) && !mac.equals( "02:00:00:00:00:00" ) ) {
                    return mac;
                }
            } catch( Exception e ) {
                // Next interface
            }
        }
        return "";
    }

    /**
     * Stand-in identity from ANDROID_ID: "02" (locally administered, never a real vendor MAC) + 5 bytes of its
     * SHA-256. Survives reboots and app updates; changes only on a factory reset.
     *
     * @param context Context
     * @return MAC-shaped text, or "" when ANDROID_ID is missing
     */
    @SuppressLint( "HardwareIds" )
    private static String macFromAndroidId( Context context ) {
        String id = Settings.Secure.getString( context.getContentResolver(), Settings.Secure.ANDROID_ID );
        if( id == null || id.isEmpty() ) {
            return "";
        }
        try {
            byte[] h  = MessageDigest.getInstance( "SHA-256" ).digest( id.getBytes( "UTF-8" ) );
            byte[] hw = new byte[]{ 0x02, h[ 0 ], h[ 1 ], h[ 2 ], h[ 3 ], h[ 4 ] };
            return format( hw );
        } catch( Exception e ) {
            return "";
        }
    }

    /**
     * Bytes as "AA:BB:CC:DD:EE:FF"
     *
     * @param hw 6 bytes
     * @return Upper case MAC text
     */
    private static String format( byte[] hw ) {
        StringBuilder sb = new StringBuilder();
        for( int i = 0; i < hw.length; i++ ) {
            sb.append( i > 0 ? ":" : "" ).append( String.format( Locale.US, "%02X", hw[ i ] & 0xFF ) );
        }
        return sb.toString();
    }

    /**
     * MAC of one interface from /sys (directly, else through root - newer Android hides it from apps)
     *
     * @param name Interface name
     * @return MAC in upper case, or "" when missing / all zero
     */
    private static String readMac( String name ) {
        String path = "/sys/class/net/" + name + "/address";
        String mac  = readFirstLine( new File( path ) );
        if( mac.isEmpty() && new File( "/sys/class/net/" + name ).exists() ) {
            Shell.Result r = Shell.su( "cat " + path );
            mac = r.ok() ? r.output.trim() : "";
        }
        mac = mac.toUpperCase( Locale.US );
        if( !mac.matches( "([0-9A-F]{2}:){5}[0-9A-F]{2}" ) || mac.equals( "00:00:00:00:00:00" ) ) {
            return "";
        }
        return mac;
    }

    /**
     * The interface that has an IPv4 address now (Ethernet first)
     *
     * @return Interface name, or null when the box is offline
     */
    public static String getActiveInterface() {
        for( String name : INTERFACES ) {
            if( ipv4Of( name ) != null ) {
                return name;
            }
        }
        try {
            for( NetworkInterface nif : Collections.list( NetworkInterface.getNetworkInterfaces() ) ) {
                if( !nif.isLoopback() && nif.isUp() && ipv4Of( nif.getName() ) != null ) {
                    return nif.getName();
                }
            }
        }
        catch( Exception ignored ) {
            // No interfaces readable: offline
        }
        return null;
    }

    /**
     * Active interface as shown in the Secret Menu, e.g. "eth0 (Ethernet)"
     *
     * @return Label, or "Network Disconnected"
     */
    public static String getInterfaceLabel() {
        String name = getActiveInterface();
        if( name == null ) {
            return "Network Disconnected";
        }
        return name + ( name.startsWith( "wlan" ) ? " (WiFi)" : name.startsWith( "eth" ) ? " (Ethernet)" : "" );
    }

    /**
     * IPv4 address of the active interface
     *
     * @return e.g. "172.19.0.57", or "" when offline
     */
    public static String getIp() {
        String name = getActiveInterface();
        String ip   = ( name == null ) ? null : ipv4Of( name );
        return ip == null ? "" : ip;
    }

    /**
     * First IPv4 address of an interface
     *
     * @param name Interface name
     * @return Address, or null
     */
    private static String ipv4Of( String name ) {
        try {
            NetworkInterface nif = NetworkInterface.getByName( name );
            if( nif == null || !nif.isUp() ) {
                return null;
            }
            for( InetAddress address : Collections.list( nif.getInetAddresses() ) ) {
                if( address instanceof Inet4Address && !address.isLoopbackAddress() ) {
                    return address.getHostAddress();
                }
            }
        }
        catch( Exception ignored ) {
            // Interface gone while reading
        }
        return null;
    }

    /**
     * Firmware name (ro.build.display.id)
     *
     * @return e.g. "HGC.12June.v1.5"
     */
    public static String getFirmware() {
        return Build.DISPLAY == null ? "" : Build.DISPLAY;
    }

    /**
     * First line of a small text file
     *
     * @param file File
     * @return Trimmed line, or "" when unreadable
     */
    private static String readFirstLine( File file ) {
        try( BufferedReader reader = new BufferedReader( new FileReader( file ) ) ) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        }
        catch( Exception e ) {
            return "";
        }
    }
}
