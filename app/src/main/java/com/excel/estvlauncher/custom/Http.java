package com.excel.estvlauncher.custom;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Small HTTP helpers on HttpURLConnection (no extra libraries)
 */
public final class Http {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS    = 20000;
    private static final int FILE_TIMEOUT_MS    = 120000;

    /** Answer of a request */
    public static final class Response {
        /** HTTP status */
        public final int    status;
        /** Body as text */
        public final String body;

        /**
         * @param status HTTP status
         * @param body   Body
         */
        Response( int status, String body ) {
            this.status = status;
            this.body   = body;
        }
    }

    /** Not instantiated */
    private Http() {
    }

    /**
     * POSTs JSON
     *
     * @param url  Full URL
     * @param json Body
     * @return Response (any status)
     * @throws IOException When the server cannot be reached
     */
    public static Response postJson( String url, String json ) throws IOException {
        return post( url, "application/json; charset=utf-8", json );
    }

    /**
     * POSTs a form (application/x-www-form-urlencoded)
     *
     * @param url    Full URL
     * @param fields Field values
     * @return Response (any status)
     * @throws IOException When the server cannot be reached
     */
    public static Response postForm( String url, Map<String, String> fields ) throws IOException {
        StringBuilder body = new StringBuilder();
        for( Map.Entry<String, String> e : fields.entrySet() ) {
            if( body.length() > 0 ) {
                body.append( '&' );
            }
            body.append( URLEncoder.encode( e.getKey(), "UTF-8" ) ).append( '=' ).append( URLEncoder.encode( e.getValue(), "UTF-8" ) );
        }
        return post( url, "application/x-www-form-urlencoded; charset=utf-8", body.toString() );
    }

    /**
     * POSTs a body
     *
     * @param url         Full URL
     * @param contentType Content-Type
     * @param body        Body text
     * @return Response
     * @throws IOException When the server cannot be reached
     */
    private static Response post( String url, String contentType, String body ) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL( url ).openConnection();
        try {
            c.setConnectTimeout( CONNECT_TIMEOUT_MS );
            c.setReadTimeout( READ_TIMEOUT_MS );
            c.setRequestMethod( "POST" );
            c.setDoOutput( true );
            c.setRequestProperty( "Content-Type", contentType );
            c.setRequestProperty( "User-Agent", "esTV-Custom-Launcher/" + BuildConfig.VERSION_NAME );
            byte[] bytes = body.getBytes( "UTF-8" );
            c.setFixedLengthStreamingMode( bytes.length );
            try( OutputStream out = c.getOutputStream() ) {
                out.write( bytes );
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : Shell.readFully( in );
            return new Response( status, text );
        }
        finally {
            c.disconnect();
        }
    }

    /**
     * Downloads a file (to a .part file first, renamed when complete) and returns its SHA-256
     *
     * @param url  Full URL
     * @param dest Final file
     * @return SHA-256 of the file, lower case hex
     * @throws IOException On HTTP errors or when the server cannot be reached
     */
    public static String download( String url, File dest ) throws IOException {
        File part = new File( dest.getPath() + ".part" );
        HttpURLConnection c = (HttpURLConnection) new URL( url ).openConnection();
        try {
            c.setConnectTimeout( CONNECT_TIMEOUT_MS );
            c.setReadTimeout( FILE_TIMEOUT_MS );
            c.setRequestProperty( "User-Agent", "esTV-Custom-Launcher/" + BuildConfig.VERSION_NAME );
            int status = c.getResponseCode();
            if( status != 200 ) {
                throw new IOException( "HTTP " + status );
            }
            MessageDigest sha = MessageDigest.getInstance( "SHA-256" );
            try( InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream( part ) ) {
                byte[] buffer = new byte[ 65536 ];
                int n;
                while( ( n = in.read( buffer ) ) != -1 ) {
                    out.write( buffer, 0, n );
                    sha.update( buffer, 0, n );
                }
            }
            if( dest.exists() && !dest.delete() ) {
                throw new IOException( "Cannot replace " + dest.getName() );
            }
            if( !part.renameTo( dest ) ) {
                throw new IOException( "Cannot save " + dest.getName() );
            }
            return hex( sha.digest() );
        }
        catch( java.security.NoSuchAlgorithmException e ) {
            throw new IOException( "SHA-256 not available" );
        }
        finally {
            c.disconnect();
            if( part.exists() ) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
    }

    /**
     * Bytes as lower case hex
     *
     * @param bytes Bytes
     * @return Hex text
     */
    static String hex( byte[] bytes ) {
        StringBuilder sb = new StringBuilder( bytes.length * 2 );
        for( byte b : bytes ) {
            sb.append( String.format( "%02x", b & 0xff ) );
        }
        return sb.toString();
    }
}
