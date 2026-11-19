package com.excel.estvlauncher.custom;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The eslink fallback: asks eslink.online for the property's CMS address, only when the CMS cannot be reached.
 * Signs in with the launcher app's app_id + secret (from the build, never in git), then calls
 * eslink_custom_launcher_get_cms. See docs/box-protocol.md of estv-custom-launcher-cms, section 4.
 */
public final class EslinkClient {

    /** Request field that carries the Scodezy token (Apache drops headers with underscores) */
    private static final String TOKEN_FIELD = "xAwBo5Re9a";

    /** CMS address from eslink */
    public static final class CmsAddress {
        /** IP or host name */
        public final String host;
        /** Port */
        public final int    port;

        /**
         * @param host IP or host name
         * @param port Port
         */
        CmsAddress( String host, int port ) {
            this.host = host;
            this.port = port;
        }
    }

    /** eslink refused (wrong secret, not linked, no address set ...) */
    public static final class Refused extends Exception {
        /** true when the app secret itself was refused (never retried with this build) */
        public final boolean secretRefused;

        /**
         * @param message       eslink's message
         * @param secretRefused true when the secret was refused
         */
        Refused( String message, boolean secretRefused ) {
            super( message );
            this.secretRefused = secretRefused;
        }
    }

    /** Not instantiated */
    private EslinkClient() {
    }

    /**
     * Whether the build carries an app_id and secret
     *
     * @return true when the fallback can be used
     */
    public static boolean isConfigured() {
        return !BuildConfig.ESLINK_APP_ID.isEmpty() && !BuildConfig.ESLINK_APP_SECRET.isEmpty();
    }

    /**
     * Asks eslink for the CMS address of a property
     *
     * @param propertyId Property ID pulled from the CMS earlier
     * @return Address
     * @throws IOException When eslink cannot be reached or answers garbage
     * @throws Refused     When eslink refuses
     */
    public static CmsAddress getCms( String propertyId ) throws IOException, Refused {
        String url = BuildConfig.ESLINK_BASE_URL + ( BuildConfig.ESLINK_BASE_URL.endsWith( "/" ) ? "" : "/" ) + "webservice.php";

        // 1. App sign-in (eslink hands back the current token while it is valid)
        Map<String, String> auth = new LinkedHashMap<>();
        auth.put( "what_do_you_want", "scodezy_authenticate_app" );
        auth.put( "app_id", BuildConfig.ESLINK_APP_ID );
        auth.put( "app_secret", BuildConfig.ESLINK_APP_SECRET );
        JSONObject a = answer( Http.postForm( url, auth ) );
        if( !"success".equals( a.optString( "type" ) ) ) {
            String message = a.optString( "info", "App sign-in refused" );
            String lower   = message.toLowerCase( Locale.ROOT );
            throw new Refused( message, lower.contains( "authentication failed" ) || lower.contains( "invalid" ) );
        }
        JSONObject info  = a.optJSONObject( "info" );
        String     token = info == null ? "" : info.optString( "access_token" );
        if( token.isEmpty() ) {
            throw new IOException( "eslink sent no token" );
        }

        // 2. CMS address of the property
        Map<String, String> ask = new LinkedHashMap<>();
        ask.put( "what_do_you_want", "eslink_custom_launcher_get_cms" );
        ask.put( "property_id", propertyId );
        ask.put( TOKEN_FIELD, token );
        JSONObject g = answer( Http.postForm( url, ask ) );
        if( !"success".equals( g.optString( "type" ) ) ) {
            Object i = g.opt( "info" );
            throw new Refused( i instanceof String ? (String) i : "eslink refused the request", false );
        }
        JSONObject cms  = g.optJSONObject( "info" );
        String     host = cms == null ? "" : cms.optString( "cms_host" ).trim();
        int        port = cms == null ? 0 : cms.optInt( "cms_port" );
        if( host.isEmpty() || port < 1 || port > 65535 ) {
            throw new IOException( "eslink sent no CMS address" );
        }
        return new CmsAddress( host, port );
    }

    /**
     * First element of an eslink answer ([ { type, for, info } ])
     *
     * @param r HTTP response
     * @return The answer object
     * @throws IOException When it is not JSON
     */
    private static JSONObject answer( Http.Response r ) throws IOException {
        try {
            String body = r.body.trim();
            if( body.startsWith( "[" ) ) {
                JSONArray array = new JSONArray( body );
                return array.getJSONObject( 0 );
            }
            return new JSONObject( body );
        }
        catch( Exception e ) {
            throw new IOException( "eslink answered HTTP " + r.status + ", not JSON" );
        }
    }
}
