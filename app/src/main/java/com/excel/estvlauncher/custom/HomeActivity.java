package com.excel.estvlauncher.custom;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The home screen (HOME): the wallpaper from the CMS and a centred row of equal buttons - Menu first (opens
 * the Secret Menu), then the app buttons from the CMS. Rows wrap when there are many buttons.
 * Auto-start app (1.0.4): after boot, and after the home screen sat idle for the relaunch interval, a countdown
 * at the bottom opens the auto-start app; any remote key cancels it.
 */
public class HomeActivity extends Activity implements CheckInManager.Listener {

    /** Buttons per row before wrapping */
    private static final int MAX_PER_ROW = 4;

    private ImageView    ivWallpaper;
    private LinearLayout llButtons;

    /** Wallpaper hash on screen, so it is only reloaded when it changed */
    private String shownWallpaper = null;
    /** Buttons on screen, as JSON text, so the row is only rebuilt when it changed */
    private String shownButtons   = null;

    /** Countdown before an app relaunch, at least this long so it can be cancelled */
    private static final int MIN_RELAUNCH_COUNTDOWN_SEC = 3;

    private final Handler handler = new Handler( Looper.getMainLooper() );
    private TextView tvAutostart;

    /** Seconds left of the running countdown, -1 = none */
    private int    countdownLeft  = -1;
    /** Label of the app being counted down to */
    private String countdownLabel = "";
    /** Key code whose release must also be swallowed (the press that cancelled a countdown) */
    private int    swallowKeyUp   = -1;
    /** Auto-start app + relaunch interval the idle wait was set for, so a check-in only restarts it on a change */
    private String relaunchKey    = null;

    /**
     * Builds the screen
     *
     * @param savedInstanceState Saved state
     */
    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );
        setContentView( R.layout.activity_home );
        ivWallpaper = findViewById( R.id.iv_wallpaper );
        llButtons   = findViewById( R.id.ll_buttons );
        tvAutostart = findViewById( R.id.tv_autostart );
    }

    /**
     * Shows the current wallpaper and buttons and listens for check-ins
     */
    @Override
    protected void onResume() {
        super.onResume();
        CheckInManager.addListener( this );
        AutoStart.setResumedHome( this );
        refresh();
        relaunchKey = null;
        if( !checkBootAutostart() ) {
            scheduleRelaunch();
        }
    }

    /**
     * Stops listening and every auto-start timer (the Secret Menu or an app is in front now)
     */
    @Override
    protected void onPause() {
        super.onPause();
        CheckInManager.removeListener( this );
        AutoStart.setResumedHome( null );
        stopAutostartTimers();
    }

    /**
     * A check-in finished: the wallpaper, the buttons or the auto-start app may have changed
     */
    @Override
    public void onCheckInFinished() {
        refresh();
        if( countdownLeft < 0 && !relaunchKeyNow().equals( relaunchKey ) ) {
            scheduleRelaunch();
        }
    }

    /**
     * Any remote key: cancels a running countdown (that key is swallowed) and restarts the idle wait
     *
     * @param event Key event
     * @return true when the key was swallowed
     */
    @Override
    public boolean dispatchKeyEvent( KeyEvent event ) {
        if( event.getAction() == KeyEvent.ACTION_UP && event.getKeyCode() == swallowKeyUp ) {
            swallowKeyUp = -1;
            return true;
        }
        if( event.getAction() == KeyEvent.ACTION_DOWN ) {
            if( countdownLeft >= 0 ) {
                cancelCountdown();
                swallowKeyUp = event.getKeyCode();
                scheduleRelaunch();
                return true;
            }
            scheduleRelaunch();
        }
        return super.dispatchKeyEvent( event );
    }

    /**
     * Boot auto-start: right after boot (flag set by AutoStart at BOOT_COMPLETED) the countdown starts once.
     * Called on resume and by AutoStart when the home screen was already in front at BOOT_COMPLETED.
     *
     * @return true when a boot countdown was started
     */
    boolean checkBootAutostart() {
        Prefs prefs = new Prefs( this );
        if( !prefs.isBootPending() ) {
            return false;
        }
        prefs.setBootPending( false );
        if( prefs.getAutostartPackage().isEmpty() ) {
            return false;
        }
        stopAutostartTimers();
        startCountdown( prefs.getAutostartDelaySec() );
        return true;
    }

    /**
     * Auto-start app + relaunch interval, as one text
     *
     * @return Key
     */
    private String relaunchKeyNow() {
        Prefs prefs = new Prefs( this );
        return prefs.getAutostartPackage() + "|" + prefs.getAutostartActivity() + "|" + prefs.getAutostartRelaunchMin();
    }

    /**
     * (Re)starts the idle wait: when the home screen stays in front for the relaunch interval without a key
     * press, the countdown to the auto-start app starts
     */
    private void scheduleRelaunch() {
        handler.removeCallbacks( relaunchRunnable );
        relaunchKey = relaunchKeyNow();
        Prefs prefs = new Prefs( this );
        if( prefs.getAutostartPackage().isEmpty() || prefs.getAutostartRelaunchMin() <= 0 || countdownLeft >= 0 ) {
            return;
        }
        handler.postDelayed( relaunchRunnable, prefs.getAutostartRelaunchMin() * 60000L );
    }

    /** The idle wait is over: count down to the app */
    private final Runnable relaunchRunnable = new Runnable() {
        /**
         * Starts the relaunch countdown
         */
        @Override
        public void run() {
            startCountdown( Math.max( MIN_RELAUNCH_COUNTDOWN_SEC, new Prefs( HomeActivity.this ).getAutostartDelaySec() ) );
        }
    };

    /**
     * Shows "Starting <app> in N s" and opens the app at 0; any key cancels (dispatchKeyEvent)
     *
     * @param seconds Countdown length (0 = open at once)
     */
    private void startCountdown( int seconds ) {
        Prefs  prefs = new Prefs( this );
        String pkg   = prefs.getAutostartPackage();
        if( pkg.isEmpty() ) {
            return;
        }
        if( !AutoStart.isInstalled( this, pkg ) ) {
            Toast.makeText( this, getString( R.string.app_not_installed, pkg ), Toast.LENGTH_LONG ).show();
            return;
        }
        countdownLabel = AutoStart.label( this, pkg );
        countdownLeft  = Math.max( 0, seconds );
        handler.removeCallbacks( tickRunnable );
        handler.post( tickRunnable );
    }

    /** One second of the countdown */
    private final Runnable tickRunnable = new Runnable() {
        /**
         * Shows the seconds left, or opens the app at 0
         */
        @Override
        public void run() {
            if( countdownLeft < 0 ) {
                return;
            }
            if( countdownLeft == 0 ) {
                cancelCountdown();
                Prefs prefs = new Prefs( HomeActivity.this );
                openApp( new HomeButton( countdownLabel, prefs.getAutostartPackage(), prefs.getAutostartActivity() ) );
                return;
            }
            tvAutostart.setText( getString( R.string.autostart_countdown, countdownLabel, countdownLeft ) );
            tvAutostart.setVisibility( View.VISIBLE );
            countdownLeft--;
            handler.postDelayed( this, 1000 );
        }
    };

    /**
     * Stops a running countdown and hides it
     */
    private void cancelCountdown() {
        countdownLeft = -1;
        handler.removeCallbacks( tickRunnable );
        tvAutostart.setVisibility( View.GONE );
    }

    /**
     * Stops the countdown and the idle wait
     */
    private void stopAutostartTimers() {
        cancelCountdown();
        handler.removeCallbacks( relaunchRunnable );
    }

    /**
     * The launcher is HOME: Back does nothing here
     */
    @Override
    public void onBackPressed() {
        // Stay on the home screen
    }

    /**
     * Updates the wallpaper and the buttons when they changed
     */
    private void refresh() {
        Prefs prefs = new Prefs( this );

        String hash = prefs.getWallpaperHash();
        if( !hash.equals( shownWallpaper ) ) {
            showWallpaper( hash );
            shownWallpaper = hash;
        }

        List<HomeButton> buttons = prefs.getButtons();
        String key = HomeButton.listToJson( buttons ).toString();
        if( !key.equals( shownButtons ) ) {
            buildButtons( buttons );
            shownButtons = key;
        }
    }

    /**
     * Shows the cached CMS wallpaper, or the built-in one
     *
     * @param hash Hash of the cached wallpaper ("" = built-in)
     */
    private void showWallpaper( String hash ) {
        File file = new File( getFilesDir(), CheckInManager.WALLPAPER_FILE );
        if( hash.isEmpty() || !file.exists() ) {
            ivWallpaper.setImageResource( R.drawable.wallpaper_default );
            return;
        }
        Bitmap bitmap = decodeForScreen( file );
        if( bitmap == null ) {
            ivWallpaper.setImageResource( R.drawable.wallpaper_default );
        }
        else {
            ivWallpaper.setImageBitmap( bitmap );
        }
    }

    /**
     * Decodes an image at about screen size (a 4K picture would not fit in memory on small boxes)
     *
     * @param file Image file
     * @return Bitmap, or null when it is not an image
     */
    private Bitmap decodeForScreen( File file ) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile( file.getPath(), bounds );
        if( bounds.outWidth <= 0 || bounds.outHeight <= 0 ) {
            return null;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int sample = 1;
        while( bounds.outWidth / ( sample * 2 ) >= dm.widthPixels && bounds.outHeight / ( sample * 2 ) >= dm.heightPixels ) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile( file.getPath(), options );
    }

    /**
     * Builds the button rows: Menu, then the app buttons, MAX_PER_ROW per row, each row centred
     *
     * @param apps App buttons
     */
    private void buildButtons( List<HomeButton> apps ) {
        llButtons.removeAllViews();

        List<Button> all = new ArrayList<>();
        Button menu = newButton( getString( R.string.menu ) );
        menu.setOnClickListener( new View.OnClickListener() {
            /**
             * Opens the Secret Menu
             *
             * @param v Menu button
             */
            @Override
            public void onClick( View v ) {
                startActivity( new Intent( HomeActivity.this, SecretMenuActivity.class ) );
            }
        } );
        all.add( menu );
        for( final HomeButton b : apps ) {
            Button button = newButton( b.name );
            button.setOnClickListener( new View.OnClickListener() {
                /**
                 * Opens the button's app
                 *
                 * @param v The button
                 */
                @Override
                public void onClick( View v ) {
                    openApp( b );
                }
            } );
            all.add( button );
        }

        sizeButtons( all );

        LinearLayout row = null;
        for( int i = 0; i < all.size(); i++ ) {
            if( i % MAX_PER_ROW == 0 ) {
                row = new LinearLayout( this );
                row.setOrientation( LinearLayout.HORIZONTAL );
                row.setGravity( Gravity.CENTER );
                llButtons.addView( row, new LinearLayout.LayoutParams( LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT ) );
            }
            row.addView( all.get( i ) );
        }
        menu.requestFocus();
    }

    /**
     * Sizes the buttons to the screen: at most home_button_width wide, and a full row never takes more than
     * 80 % of the screen width (a 1080p box at density 2.0 is only 960 dp wide). Height and text follow.
     *
     * @param all Every button, Menu first
     */
    private void sizeButtons( List<Button> all ) {
        int screen   = getResources().getDisplayMetrics().widthPixels;
        int margin   = getResources().getDimensionPixelSize( R.dimen.home_button_margin );
        int maxW     = getResources().getDimensionPixelSize( R.dimen.home_button_width );
        int maxH     = getResources().getDimensionPixelSize( R.dimen.home_button_height );
        float maxTxt = getResources().getDimension( R.dimen.home_button_text );
        int perRow   = Math.max( 1, Math.min( all.size(), MAX_PER_ROW ) );
        int width    = Math.min( maxW, (int) ( screen * 0.8f / perRow ) - 2 * margin );
        int height   = Math.min( maxH, width * maxH / maxW );
        float text   = Math.min( maxTxt, height * 0.3f );
        for( Button button : all ) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
            lp.width  = width;
            lp.height = height;
            button.setLayoutParams( lp );
            button.setTextSize( android.util.TypedValue.COMPLEX_UNIT_PX, text );
        }
    }

    /**
     * One home button: focusable with the remote, grows a little when focused (sized by sizeButtons)
     *
     * @param text Label
     * @return Button
     */
    private Button newButton( String text ) {
        Button button = new Button( this );
        button.setText( text );
        button.setAllCaps( false );
        button.setSingleLine( true );
        button.setEllipsize( android.text.TextUtils.TruncateAt.END );
        button.setTextColor( getResources().getColor( R.color.white ) );
        button.setTextSize( android.util.TypedValue.COMPLEX_UNIT_PX, getResources().getDimension( R.dimen.home_button_text ) );
        button.setBackgroundResource( R.drawable.home_button_bg );
        button.setFocusable( true );
        button.setFocusableInTouchMode( true );
        button.setStateListAnimator( null );

        int margin = getResources().getDimensionPixelSize( R.dimen.home_button_margin );
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                getResources().getDimensionPixelSize( R.dimen.home_button_width ),
                getResources().getDimensionPixelSize( R.dimen.home_button_height ) );
        lp.setMargins( margin, margin, margin, margin );
        button.setLayoutParams( lp );

        button.setOnFocusChangeListener( new View.OnFocusChangeListener() {
            /**
             * Scales the focused button up a little
             *
             * @param v        Button
             * @param hasFocus Focus state
             */
            @Override
            public void onFocusChange( View v, boolean hasFocus ) {
                float scale = hasFocus ? 1.08f : 1f;
                v.animate().scaleX( scale ).scaleY( scale ).setDuration( 150 ).start();
            }
        } );
        return button;
    }

    /**
     * Opens a button's app: its set activity, else its launcher (or TV launcher) activity
     *
     * @param b Button
     */
    private void openApp( HomeButton b ) {
        PackageManager pm = getPackageManager();
        try {
            pm.getPackageInfo( b.packageName, 0 );
        }
        catch( PackageManager.NameNotFoundException e ) {
            Toast.makeText( this, getString( R.string.app_not_installed, b.name ), Toast.LENGTH_LONG ).show();
            return;
        }
        Intent intent;
        if( !b.fullActivity().isEmpty() ) {
            intent = new Intent( Intent.ACTION_MAIN ).setComponent( new ComponentName( b.packageName, b.fullActivity() ) );
        }
        else {
            intent = pm.getLaunchIntentForPackage( b.packageName );
            if( intent == null ) {
                intent = pm.getLeanbackLaunchIntentForPackage( b.packageName );
            }
        }
        if( intent == null ) {
            Toast.makeText( this, getString( R.string.app_cannot_open, b.name ), Toast.LENGTH_LONG ).show();
            return;
        }
        try {
            startActivity( intent.addFlags( Intent.FLAG_ACTIVITY_NEW_TASK ) );
        }
        catch( Exception e ) {
            Toast.makeText( this, getString( R.string.app_cannot_open, b.name ), Toast.LENGTH_LONG ).show();
        }
    }
}
