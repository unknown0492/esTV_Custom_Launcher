package com.excel.estvlauncher.custom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The Secret Menu (opened by Menu on the home screen). Same look as esTVLauncher's ShortcutsActivity, without
 * room number / OTS / screen size, plus: box name, home buttons, CMS address, property, check-in, permission helper.
 */
public class SecretMenuActivity extends Activity implements CheckInManager.Listener {

    private TextView tvMac, tvFirmware, tvVersion, tvInterface, tvProperty;
    private Button   btBoxName, btIp, btCms, btLastCheckIn, btCheckNow, btHomeButtons, btAutoAllow;

    /**
     * Builds the menu and wires the buttons
     *
     * @param savedInstanceState Saved state
     */
    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );
        setContentView( R.layout.activity_secret_menu );

        tvMac         = findViewById( R.id.tv_mac_address );
        tvFirmware    = findViewById( R.id.tv_firmware_version );
        tvVersion     = findViewById( R.id.tv_launcher_version );
        tvInterface   = findViewById( R.id.tv_interface );
        tvProperty    = findViewById( R.id.tv_property );
        btBoxName     = findViewById( R.id.bt_box_name );
        btIp          = findViewById( R.id.bt_ip_address );
        btCms         = findViewById( R.id.bt_cms );
        btLastCheckIn = findViewById( R.id.bt_last_checkin );
        btCheckNow    = findViewById( R.id.bt_check_now );
        btHomeButtons = findViewById( R.id.bt_home_buttons );
        btAutoAllow   = findViewById( R.id.bt_auto_allow );

        tvFirmware.setText( DeviceInfo.getFirmware() );
        tvVersion.setText( BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")" );

        btBoxName.setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                editBoxName();
            }
        } );
        btIp.setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                refreshNetwork();
            }
        } );
        btCms.setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                editCms();
            }
        } );
        btLastCheckIn.setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                new AlertDialog.Builder( SecretMenuActivity.this ).setTitle( "Last Check-in" )
                        .setMessage( new Prefs( SecretMenuActivity.this ).getLastResult() )
                        .setPositiveButton( "OK", null ).show();
            }
        } );
        btCheckNow.setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                btCheckNow.setText( "Checking..." );
                CheckInManager.checkIn( SecretMenuActivity.this, true );
            }
        } );
        btHomeButtons.setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                startActivity( new Intent( SecretMenuActivity.this, HomeButtonsActivity.class ) );
            }
        } );
        btAutoAllow.setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                turnOnAutoAllow();
            }
        } );
        findViewById( R.id.bt_reboot_box ).setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                runRoot( "reboot" );
            }
        } );
        findViewById( R.id.bt_reboot_recovery ).setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                runRoot( "reboot recovery" );
            }
        } );
        findViewById( R.id.bt_root_browser ).setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                if( !openPackage( "com.jrummyapps.rootbrowser.classic" ) && !openPackage( "com.jrummy.root.browserfree" ) ) {
                    toast( "Root Browser is not installed" );
                }
            }
        } );
        findViewById( R.id.bt_mbox ).setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                openMboxSettings();
            }
        } );
        findViewById( R.id.bt_settings ).setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                openAndroidSettings();
            }
        } );
    }

    /**
     * Shows the current values and listens for check-ins
     */
    @Override
    protected void onResume() {
        super.onResume();
        CheckInManager.addListener( this );
        render();
        refreshNetwork();
    }

    /**
     * Stops listening
     */
    @Override
    protected void onPause() {
        super.onPause();
        CheckInManager.removeListener( this );
    }

    /**
     * A check-in finished: show its result
     */
    @Override
    public void onCheckInFinished() {
        btCheckNow.setText( "Check Now" );
        render();
    }

    /**
     * Fills the values kept in the preferences
     */
    private void render() {
        Prefs prefs = new Prefs( this );
        btBoxName.setText( prefs.getBoxName().isEmpty() ? "Set Box Name" : prefs.getBoxName() );
        btCms.setText( prefs.getCmsHost() + ":" + prefs.getCmsPort() );
        String property = prefs.getPropertyId();
        tvProperty.setText( property.isEmpty() ? "Not known yet (from the CMS)" : property + ( prefs.getPropertyName().isEmpty() ? "" : "  " + prefs.getPropertyName() ) );
        btLastCheckIn.setText( prefs.getLastResult() );
        int count = prefs.getButtons().size();
        btHomeButtons.setText( "Edit (" + count + ( Prefs.SOURCE_LOCAL.equals( prefs.getButtonsSource() ) ? ", changed on this box)" : ", from CMS)" ) );
        btAutoAllow.setText( PermissionAutoAllowService.isEnabled( this ) ? "On" : "Off - Turn On" );
    }

    /**
     * Reads MAC, interface and IP again (in the background: they may need root)
     */
    private void refreshNetwork() {
        new Thread( new Runnable() {
            /**
             * Reads the values, then shows them
             */
            @Override
            public void run() {
                final String mac = DeviceInfo.getMac( SecretMenuActivity.this );
                final String nif = DeviceInfo.getInterfaceLabel();
                final String ip  = DeviceInfo.getIp();
                runOnUiThread( new Runnable() {
                    /**
                     * Shows the values
                     */
                    @Override
                    public void run() {
                        tvMac.setText( mac.isEmpty() ? "Not readable" : ( DeviceInfo.isRealMac( mac ) ? mac : mac + " (ID)" ) );
                        tvInterface.setText( nif );
                        btIp.setText( ip.isEmpty() ? "Network Disconnected" : ip );
                    }
                } );
            }
        }, "secret-menu-network" ).start();
    }

    /**
     * Dialog: box name (sent to the CMS at the next check-in, which starts at once)
     */
    private void editBoxName() {
        final Prefs    prefs = new Prefs( this );
        final EditText et    = newInput( prefs.getBoxName(), "e.g. Lobby TV 1", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS );
        new AlertDialog.Builder( this )
                .setTitle( "Box Name" )
                .setMessage( "Shown in the CMS box list." )
                .setView( wrap( et ) )
                .setPositiveButton( "Save", new DialogInterface.OnClickListener() {
                    /**
                     * Saves the name
                     *
                     * @param dialog Dialog
                     * @param which  Button
                     */
                    @Override
                    public void onClick( DialogInterface dialog, int which ) {
                        String name = et.getText().toString().trim();
                        if( name.length() > 60 ) {
                            name = name.substring( 0, 60 );
                        }
                        prefs.setBoxName( name );
                        render();
                        CheckInManager.checkIn( SecretMenuActivity.this, false );
                    }
                } )
                .setNegativeButton( "Cancel", null )
                .show();
    }

    /**
     * Dialog: CMS IP / host name and port
     */
    private void editCms() {
        final Prefs    prefs  = new Prefs( this );
        final EditText etHost = newInput( prefs.getCmsHost(), Prefs.DEFAULT_CMS_HOST, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI );
        final EditText etPort = newInput( String.valueOf( prefs.getCmsPort() ), String.valueOf( Prefs.DEFAULT_CMS_PORT ), InputType.TYPE_CLASS_NUMBER );
        LinearLayout box = wrap( etHost );
        box.addView( etPort );
        new AlertDialog.Builder( this )
                .setTitle( "CMS IP / Port" )
                .setMessage( "IP or host name of the CMS PC, then its port. The default is " + Prefs.DEFAULT_CMS_HOST + ":" + Prefs.DEFAULT_CMS_PORT + "." )
                .setView( box )
                .setPositiveButton( "Save", new DialogInterface.OnClickListener() {
                    /**
                     * Checks and saves the address, then checks in
                     *
                     * @param dialog Dialog
                     * @param which  Button
                     */
                    @Override
                    public void onClick( DialogInterface dialog, int which ) {
                        String host = etHost.getText().toString().trim();
                        int    port;
                        try {
                            port = Integer.parseInt( etPort.getText().toString().trim() );
                        }
                        catch( NumberFormatException e ) {
                            port = -1;
                        }
                        if( !host.matches( "[A-Za-z0-9.:\\-]{1,253}" ) ) {
                            toast( "The CMS IP / host name is not valid" );
                            return;
                        }
                        if( port < 1 || port > 65535 ) {
                            toast( "The port must be a number from 1 to 65535" );
                            return;
                        }
                        prefs.setCms( host, port );
                        render();
                        btCheckNow.setText( "Checking..." );
                        CheckInManager.checkIn( SecretMenuActivity.this, true );
                    }
                } )
                .setNeutralButton( "Default", new DialogInterface.OnClickListener() {
                    /**
                     * Back to 172.19.0.3:4053
                     *
                     * @param dialog Dialog
                     * @param which  Button
                     */
                    @Override
                    public void onClick( DialogInterface dialog, int which ) {
                        prefs.setCms( Prefs.DEFAULT_CMS_HOST, Prefs.DEFAULT_CMS_PORT );
                        render();
                        CheckInManager.checkIn( SecretMenuActivity.this, true );
                    }
                } )
                .setNegativeButton( "Cancel", null )
                .show();
    }

    /**
     * Switches the permission helper on with root, else opens Accessibility settings
     */
    private void turnOnAutoAllow() {
        if( PermissionAutoAllowService.isEnabled( this ) ) {
            toast( "The permission helper is already on" );
            return;
        }
        btAutoAllow.setText( "Turning on..." );
        new Thread( new Runnable() {
            /**
             * Tries root, then reports
             */
            @Override
            public void run() {
                final boolean on = PermissionAutoAllowService.enableWithRoot( SecretMenuActivity.this );
                runOnUiThread( new Runnable() {
                    /**
                     * Shows the outcome
                     */
                    @Override
                    public void run() {
                        render();
                        if( !on ) {
                            toast( "Could not switch it on with root. Turn on \"" + getString( R.string.app_name ) + "\" in Accessibility." );
                            try {
                                startActivity( new Intent( Settings.ACTION_ACCESSIBILITY_SETTINGS ) );
                            }
                            catch( Exception ignored ) {
                                // No Accessibility settings screen on this box
                            }
                        }
                    }
                } );
            }
        }, "auto-allow-on" ).start();
    }

    /**
     * Opens the box maker's settings app (as esTVLauncher does)
     */
    private void openMboxSettings() {
        if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ) {
            runRoot( "am start -n android.settings/.MainActivity" );
        }
        else if( !openPackage( "com.sdmc.settings" ) ) {
            toast( "SDMC Settings not found" );
        }
    }

    /**
     * Opens Android Settings
     */
    private void openAndroidSettings() {
        try {
            startActivity( new Intent( Settings.ACTION_SETTINGS ).addFlags( Intent.FLAG_ACTIVITY_NEW_TASK ) );
        }
        catch( Exception e ) {
            runRoot( "am start -a android.settings.SETTINGS" );
        }
    }

    /**
     * Opens an app by its package
     *
     * @param packageName Package
     * @return false when it is not installed
     */
    private boolean openPackage( String packageName ) {
        Intent intent = getPackageManager().getLaunchIntentForPackage( packageName );
        if( intent == null ) {
            intent = getPackageManager().getLeanbackLaunchIntentForPackage( packageName );
        }
        if( intent == null ) {
            return false;
        }
        startActivity( intent.addFlags( Intent.FLAG_ACTIVITY_NEW_TASK ) );
        return true;
    }

    /**
     * Runs a root command in the background
     *
     * @param command Shell command
     */
    private void runRoot( final String command ) {
        new Thread( new Runnable() {
            /**
             * Runs the command
             */
            @Override
            public void run() {
                Shell.su( command );
            }
        }, "secret-menu-root" ).start();
    }

    /**
     * Text input for a dialog
     *
     * @param value     Current value
     * @param hint      Hint
     * @param inputType Input type
     * @return EditText
     */
    private EditText newInput( String value, String hint, int inputType ) {
        EditText et = new EditText( this );
        et.setSingleLine( true );
        et.setInputType( inputType );
        et.setHint( hint );
        et.setText( value );
        et.setSelection( et.getText().length() );
        return et;
    }

    /**
     * Puts a view in a padded vertical box for a dialog
     *
     * @param view View
     * @return Box
     */
    private LinearLayout wrap( View view ) {
        LinearLayout box = new LinearLayout( this );
        box.setOrientation( LinearLayout.VERTICAL );
        int pad = (int) ( 20 * getResources().getDisplayMetrics().density );
        box.setPadding( pad, 0, pad, 0 );
        box.addView( view );
        return box;
    }

    /**
     * @param text Message
     */
    private void toast( String text ) {
        Toast.makeText( this, text, Toast.LENGTH_LONG ).show();
    }
}
