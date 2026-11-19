package com.excel.estvlauncher.custom;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One app button on the home screen (Menu is not one of these: it is always shown first)
 */
public final class HomeButton {

    /** Text on the button */
    public String name;
    /** Package to open */
    public String packageName;
    /** Activity to start; "" = the package's launcher activity */
    public String activity;

    /**
     * @param name        Text on the button
     * @param packageName Package to open
     * @param activity    Activity, or "" for the launcher activity
     */
    public HomeButton( String name, String packageName, String activity ) {
        this.name        = name == null ? "" : name.trim();
        this.packageName = packageName == null ? "" : packageName.trim();
        this.activity    = activity == null ? "" : activity.trim();
    }

    /**
     * @return JSON in the CMS format { name, package, activity }
     * @throws JSONException Never in practice
     */
    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put( "name", name )
                .put( "package", packageName )
                .put( "activity", activity );
    }

    /**
     * Reads a list in the CMS format; entries without a package are skipped
     *
     * @param array JSON array, may be null
     * @return Buttons in order
     */
    public static List<HomeButton> listFromJson( JSONArray array ) {
        List<HomeButton> list = new ArrayList<>();
        if( array == null ) {
            return list;
        }
        for( int i = 0; i < array.length(); i++ ) {
            JSONObject o = array.optJSONObject( i );
            if( o == null || o.optString( "package" ).trim().isEmpty() ) {
                continue;
            }
            String name = o.optString( "name" ).trim();
            list.add( new HomeButton( name.isEmpty() ? o.optString( "package" ) : name, o.optString( "package" ), o.optString( "activity" ) ) );
        }
        return list;
    }

    /**
     * Writes a list in the CMS format
     *
     * @param list Buttons
     * @return JSON array
     */
    public static JSONArray listToJson( List<HomeButton> list ) {
        JSONArray array = new JSONArray();
        for( HomeButton b : list ) {
            try {
                array.put( b.toJson() );
            }
            catch( JSONException ignored ) {
                // Strings only: cannot happen
            }
        }
        return array;
    }

    /**
     * Full class name of the activity: ".Main" becomes "<package>.Main"
     *
     * @return Class name, or "" when no activity is set
     */
    public String fullActivity() {
        if( activity.isEmpty() ) {
            return "";
        }
        return activity.startsWith( "." ) ? packageName + activity : activity;
    }
}
