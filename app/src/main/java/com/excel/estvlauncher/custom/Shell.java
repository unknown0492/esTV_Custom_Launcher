package com.excel.estvlauncher.custom;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Root shell commands. The boxes are rooted (as for esTVLauncher): commands are written to "su" on stdin,
 * so the same code works with the different su builds found on TV boxes.
 */
public final class Shell {

    private static final String TAG = "CL.Shell";

    /** Result of one su run */
    public static final class Result {
        /** Exit code of su (0 = success); -1 when su could not be started */
        public final int    exitCode;
        /** stdout and stderr together */
        public final String output;

        /**
         * @param exitCode Exit code
         * @param output   Combined output
         */
        Result( int exitCode, String output ) {
            this.exitCode = exitCode;
            this.output   = output;
        }

        /**
         * @return true when su ran and exited with 0
         */
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** Not instantiated */
    private Shell() {
    }

    /**
     * Runs commands as root, one after the other in the same su session
     *
     * @param commands Shell commands
     * @return Result (exit code of the last command, combined output)
     */
    public static Result su( String... commands ) {
        Process process = null;
        try {
            process = new ProcessBuilder( "su" ).redirectErrorStream( true ).start();
            DataOutputStream in = new DataOutputStream( process.getOutputStream() );
            for( String command : commands ) {
                in.writeBytes( command + "\n" );
            }
            in.writeBytes( "exit $?\n" );
            in.flush();
            in.close();
            String output = readFully( process.getInputStream() );
            int    code   = process.waitFor();
            return new Result( code, output.trim() );
        }
        catch( IOException | InterruptedException e ) {
            Log.w( TAG, "su failed: " + e.getMessage() );
            return new Result( -1, e.getMessage() == null ? "su failed" : e.getMessage() );
        }
        finally {
            if( process != null ) {
                process.destroy();
            }
        }
    }

    /**
     * Whether root is available (su runs as uid 0)
     *
     * @return true when rooted
     */
    public static boolean hasRoot() {
        Result r = su( "id" );
        return r.ok() && r.output.contains( "uid=0" );
    }

    /**
     * Reads a stream to the end as UTF-8 text
     *
     * @param is Stream
     * @return Text
     * @throws IOException On read errors
     */
    static String readFully( InputStream is ) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[ 4096 ];
        int n;
        while( ( n = is.read( buffer ) ) != -1 ) {
            out.write( buffer, 0, n );
        }
        return out.toString( "UTF-8" );
    }

    /**
     * Quotes a value for the shell (single quotes)
     *
     * @param value Any text
     * @return Quoted text
     */
    public static String quote( String value ) {
        return "'" + value.replace( "'", "'\\''" ) + "'";
    }
}
