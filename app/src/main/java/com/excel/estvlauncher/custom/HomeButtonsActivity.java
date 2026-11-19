package com.excel.estvlauncher.custom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Secret Menu > Home Buttons: add, edit, move and delete the app buttons on this box. A change here is kept
 * until the CMS list changes again (the newest change wins); "Use CMS Buttons" brings the CMS list back.
 */
public class HomeButtonsActivity extends Activity {

    private final List<HomeButton> buttons = new ArrayList<>();

    private ListView      lvButtons;
    private TextView      tvSource, tvEmpty;
    private ButtonAdapter adapter;

    /**
     * Builds the list and the bottom buttons
     *
     * @param savedInstanceState Saved state
     */
    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );
        setContentView( R.layout.activity_home_buttons );

        lvButtons = findViewById( R.id.lv_buttons );
        tvSource  = findViewById( R.id.tv_source );
        tvEmpty   = findViewById( R.id.tv_empty );
        adapter   = new ButtonAdapter();
        lvButtons.setAdapter( adapter );
        lvButtons.setItemsCanFocus( false );

        lvButtons.setOnItemClickListener( new AdapterView.OnItemClickListener() {
            /**
             * Opens the actions of a row
             *
             * @param parent   List
             * @param view     Row
             * @param position Position
             * @param id       Row id
             */
            @Override
            public void onItemClick( AdapterView<?> parent, View view, int position, long id ) {
                showActions( position );
            }
        } );
        findViewById( R.id.bt_add ).setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                showEditor( -1, new HomeButton( "", "", "" ) );
            }
        } );
        findViewById( R.id.bt_use_cms ).setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                useCmsButtons();
            }
        } );
        findViewById( R.id.bt_back ).setOnClickListener( new View.OnClickListener() {
            /** @param v Button */
            @Override
            public void onClick( View v ) {
                finish();
            }
        } );
    }

    /**
     * Reloads the stored list
     */
    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    /**
     * Reads the buttons and the source from the preferences
     */
    private void load() {
        Prefs prefs = new Prefs( this );
        buttons.clear();
        buttons.addAll( prefs.getButtons() );
        tvSource.setText( Prefs.SOURCE_LOCAL.equals( prefs.getButtonsSource() )
                ? "Changed on this box. The CMS list replaces it when it is changed on the CMS."
                : "From the CMS. Changes made here stay until the CMS list is changed again." );
        refreshList();
    }

    /**
     * Redraws the list (or the empty message)
     */
    private void refreshList() {
        adapter.notifyDataSetChanged();
        boolean empty = buttons.isEmpty();
        lvButtons.setVisibility( empty ? View.GONE : View.VISIBLE );
        tvEmpty.setVisibility( empty ? View.VISIBLE : View.GONE );
    }

    /**
     * Saves the list as a local change and shows it
     */
    private void save() {
        CheckInManager.saveLocalButtons( this, new ArrayList<>( buttons ) );
        load();
    }

    /**
     * Actions of one button: edit, move up / down, delete
     *
     * @param position Row
     */
    private void showActions( final int position ) {
        final HomeButton b = buttons.get( position );
        String[] actions = { "Edit", "Move Left (earlier)", "Move Right (later)", "Delete" };
        new AlertDialog.Builder( this )
                .setTitle( b.name )
                .setItems( actions, new DialogInterface.OnClickListener() {
                    /**
                     * Runs the chosen action
                     *
                     * @param dialog Dialog
                     * @param which  Action index
                     */
                    @Override
                    public void onClick( DialogInterface dialog, int which ) {
                        switch( which ) {
                            case 0:
                                showEditor( position, new HomeButton( b.name, b.packageName, b.activity ) );
                                break;
                            case 1:
                                if( position > 0 ) {
                                    Collections.swap( buttons, position, position - 1 );
                                    save();
                                    lvButtons.setSelection( position - 1 );
                                }
                                break;
                            case 2:
                                if( position < buttons.size() - 1 ) {
                                    Collections.swap( buttons, position, position + 1 );
                                    save();
                                    lvButtons.setSelection( position + 1 );
                                }
                                break;
                            default:
                                confirmDelete( position );
                        }
                    }
                } )
                .show();
    }

    /**
     * Asks before deleting a button
     *
     * @param position Row
     */
    private void confirmDelete( final int position ) {
        new AlertDialog.Builder( this )
                .setTitle( "Delete \"" + buttons.get( position ).name + "\"?" )
                .setMessage( "The button is removed from this box's home screen." )
                .setPositiveButton( "Delete", new DialogInterface.OnClickListener() {
                    /**
                     * Deletes the button
                     *
                     * @param dialog Dialog
                     * @param which  Button
                     */
                    @Override
                    public void onClick( DialogInterface dialog, int which ) {
                        buttons.remove( position );
                        save();
                    }
                } )
                .setNegativeButton( "Cancel", null )
                .show();
    }

    /**
     * Dialog to add or edit a button: name, package, start activity, and "Pick App"
     *
     * @param position Row being edited, or -1 to add
     * @param draft    Values to show
     */
    private void showEditor( final int position, final HomeButton draft ) {
        final EditText etName     = input( draft.name, "Name on the button, e.g. Movies", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS );
        final EditText etPackage  = input( draft.packageName, "Package, e.g. com.example.player", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI );
        final EditText etActivity = input( draft.activity, "Start activity (empty = the app's own)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI );

        LinearLayout box = new LinearLayout( this );
        box.setOrientation( LinearLayout.VERTICAL );
        int pad = (int) ( 20 * getResources().getDisplayMetrics().density );
        box.setPadding( pad, 0, pad, 0 );
        box.addView( etName );
        box.addView( etPackage );
        box.addView( etActivity );

        new AlertDialog.Builder( this )
                .setTitle( position < 0 ? "Add Button" : "Edit Button" )
                .setView( box )
                .setPositiveButton( "Save", new DialogInterface.OnClickListener() {
                    /**
                     * Checks and saves the button
                     *
                     * @param dialog Dialog
                     * @param which  Button
                     */
                    @Override
                    public void onClick( DialogInterface dialog, int which ) {
                        HomeButton b = new HomeButton( etName.getText().toString(), etPackage.getText().toString(), etActivity.getText().toString() );
                        String error = validate( b );
                        if( error != null ) {
                            Toast.makeText( HomeButtonsActivity.this, error, Toast.LENGTH_LONG ).show();
                            showEditor( position, b );
                            return;
                        }
                        if( position < 0 ) {
                            buttons.add( b );
                        }
                        else {
                            buttons.set( position, b );
                        }
                        save();
                    }
                } )
                .setNeutralButton( "Pick App", new DialogInterface.OnClickListener() {
                    /**
                     * Opens the installed-apps list, keeping what was typed
                     *
                     * @param dialog Dialog
                     * @param which  Button
                     */
                    @Override
                    public void onClick( DialogInterface dialog, int which ) {
                        pickApp( position, new HomeButton( etName.getText().toString(), etPackage.getText().toString(), etActivity.getText().toString() ) );
                    }
                } )
                .setNegativeButton( "Cancel", null )
                .show();
    }

    /**
     * Checks a button's values
     *
     * @param b Button
     * @return Error text, or null when valid
     */
    private static String validate( HomeButton b ) {
        if( b.name.isEmpty() || b.name.length() > 40 ) {
            return "Enter a name of up to 40 characters";
        }
        if( !b.packageName.matches( "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+" ) ) {
            return "The package name is not valid";
        }
        if( !b.activity.isEmpty() && !b.activity.matches( "\\.?[A-Za-z][A-Za-z0-9_$]*(\\.[A-Za-z0-9_$]+)*" ) ) {
            return "The start activity is not valid";
        }
        return null;
    }

    /**
     * Lists the apps that can be opened (launcher and TV launcher activities) and fills the editor
     *
     * @param position Row being edited, or -1
     * @param draft    Values typed so far
     */
    private void pickApp( final int position, final HomeButton draft ) {
        final PackageManager    fpm  = getPackageManager();
        final List<ResolveInfo> apps = AppPicker.launchableApps( this );
        final String[]          labels = AppPicker.labels( this, apps );
        new AlertDialog.Builder( this )
                .setTitle( "Pick App" )
                .setItems( labels, new DialogInterface.OnClickListener() {
                    /**
                     * Fills name (if empty), package and activity from the chosen app
                     *
                     * @param dialog Dialog
                     * @param which  App index
                     */
                    @Override
                    public void onClick( DialogInterface dialog, int which ) {
                        ResolveInfo app  = apps.get( which );
                        String      name = draft.name.isEmpty() ? app.loadLabel( fpm ).toString() : draft.name;
                        showEditor( position, new HomeButton( name, app.activityInfo.packageName, app.activityInfo.name ) );
                    }
                } )
                .setNegativeButton( "Cancel", new DialogInterface.OnClickListener() {
                    /**
                     * Back to the editor with what was typed
                     *
                     * @param dialog Dialog
                     * @param which  Button
                     */
                    @Override
                    public void onClick( DialogInterface dialog, int which ) {
                        showEditor( position, draft );
                    }
                } )
                .show();
    }

    /**
     * Brings the CMS list back at the next check-in (started at once)
     */
    private void useCmsButtons() {
        new AlertDialog.Builder( this )
                .setTitle( "Use CMS Buttons?" )
                .setMessage( "The buttons changed on this box are replaced by the CMS list." )
                .setPositiveButton( "Use CMS Buttons", new DialogInterface.OnClickListener() {
                    /**
                     * Forgets the applied revision and checks in
                     *
                     * @param dialog Dialog
                     * @param which  Button
                     */
                    @Override
                    public void onClick( DialogInterface dialog, int which ) {
                        new Prefs( HomeButtonsActivity.this ).resetButtonsToCms();
                        CheckInManager.checkIn( HomeButtonsActivity.this, false );
                        Toast.makeText( HomeButtonsActivity.this, "Asking the CMS for its buttons...", Toast.LENGTH_SHORT ).show();
                    }
                } )
                .setNegativeButton( "Cancel", null )
                .show();
    }

    /**
     * Text input for the editor
     *
     * @param value     Current value
     * @param hint      Hint
     * @param inputType Input type
     * @return EditText
     */
    private EditText input( String value, String hint, int inputType ) {
        EditText et = new EditText( this );
        et.setSingleLine( true );
        et.setInputType( inputType );
        et.setHint( hint );
        et.setText( value );
        return et;
    }

    /** Rows of the list */
    private final class ButtonAdapter extends BaseAdapter {

        /** @return Number of buttons */
        @Override
        public int getCount() {
            return buttons.size();
        }

        /**
         * @param position Row
         * @return The button
         */
        @Override
        public Object getItem( int position ) {
            return buttons.get( position );
        }

        /**
         * @param position Row
         * @return Row id (the position)
         */
        @Override
        public long getItemId( int position ) {
            return position;
        }

        /**
         * Fills a row: position, name, package / activity, installed or not
         *
         * @param position    Row
         * @param convertView Recycled row
         * @param parent      List
         * @return Row view
         */
        @Override
        public View getView( int position, View convertView, ViewGroup parent ) {
            View row = convertView != null ? convertView : LayoutInflater.from( HomeButtonsActivity.this ).inflate( R.layout.item_home_button, parent, false );
            HomeButton b = buttons.get( position );
            ( (TextView) row.findViewById( R.id.tv_position ) ).setText( String.format( Locale.US, "%d.", position + 1 ) );
            ( (TextView) row.findViewById( R.id.tv_name ) ).setText( b.name );
            ( (TextView) row.findViewById( R.id.tv_target ) ).setText( b.packageName + ( b.activity.isEmpty() ? "" : " / " + b.activity ) );
            long version = ApkInstaller.installedVersionCode( HomeButtonsActivity.this, b.packageName );
            String name  = ApkInstaller.installedVersionName( HomeButtonsActivity.this, b.packageName );
            ( (TextView) row.findViewById( R.id.tv_installed ) ).setText( version > 0 ? "v" + name : "Not installed" );
            return row;
        }
    }
}
