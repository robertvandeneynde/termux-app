package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.IntDef;
import android.util.Log;
import android.util.TypedValue;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;

final class TermuxPreferences {

    @IntDef({BELL_VIBRATE, BELL_BEEP, BELL_IGNORE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AsciiBellBehaviour {
    }

    static final int BELL_VIBRATE = 1;
    static final int BELL_BEEP = 2;
    static final int BELL_IGNORE = 3;

    private final int MIN_FONTSIZE;
    private static final int MAX_FONTSIZE = 256;

    private static final String SHOW_EXTRA_KEYS_KEY = "show_extra_keys";
    private static final String FONTSIZE_KEY = "fontsize";
    private static final String CURRENT_SESSION_KEY = "current_session";

    private int mFontSize;

    @AsciiBellBehaviour
    int mBellBehaviour = BELL_VIBRATE;

    boolean mBackIsEscape;
    boolean mShowExtraKeys;
    
    /**
     * If value is not in the range [min, max], set it to either min or max.
     */
    static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    TermuxPreferences(Context context) {
        reloadFromProperties(context);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        float dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, context.getResources().getDisplayMetrics());

        // This is a bit arbitrary and sub-optimal. We want to give a sensible default for minimum font size
        // to prevent invisible text due to zoom be mistake:
        MIN_FONTSIZE = (int) (4f * dipInPixels);

        mShowExtraKeys = prefs.getBoolean(SHOW_EXTRA_KEYS_KEY, true);

        // http://www.google.com/design/spec/style/typography.html#typography-line-height
        int defaultFontSize = Math.round(12 * dipInPixels);
        // Make it divisible by 2 since that is the minimal adjustment step:
        if (defaultFontSize % 2 == 1) defaultFontSize--;

        try {
            mFontSize = Integer.parseInt(prefs.getString(FONTSIZE_KEY, Integer.toString(defaultFontSize)));
        } catch (NumberFormatException | ClassCastException e) {
            mFontSize = defaultFontSize;
        }
        mFontSize = clamp(mFontSize, MIN_FONTSIZE, MAX_FONTSIZE); 
    }

    boolean isShowExtraKeys() {
        return mShowExtraKeys;
    }

    boolean toggleShowExtraKeys(Context context) {
        mShowExtraKeys = !mShowExtraKeys;
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(SHOW_EXTRA_KEYS_KEY, mShowExtraKeys).apply();
        return mShowExtraKeys;
    }

    int getFontSize() {
        return mFontSize;
    }

    void changeFontSize(Context context, boolean increase) {
        mFontSize += (increase ? 1 : -1) * 2;
        mFontSize = Math.max(MIN_FONTSIZE, Math.min(mFontSize, MAX_FONTSIZE));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(FONTSIZE_KEY, Integer.toString(mFontSize)).apply();
    }

    static void storeCurrentSession(Context context, TerminalSession session) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(TermuxPreferences.CURRENT_SESSION_KEY, session.mHandle).apply();
    }

    static TerminalSession getCurrentSession(TermuxActivity context) {
        String sessionHandle = PreferenceManager.getDefaultSharedPreferences(context).getString(TermuxPreferences.CURRENT_SESSION_KEY, "");
        for (int i = 0, len = context.mTermService.getSessions().size(); i < len; i++) {
            TerminalSession session = context.mTermService.getSessions().get(i);
            if (session.mHandle.equals(sessionHandle)) return session;
        }
        return null;
    }
    
    public String[][] mExtraKeys = new String[0][0];

    public void reloadFromProperties(Context context) {
        Properties props = findProps();
        
        setDefaultBellBehaviour();
        setDefaultBackKeys();
        setDefaultExtraKeys();
        setDefaultShortcuts();
        
        try {
            parseBellBehaviour(props);
        } catch (Exception e) {
            toastException(e);
        }
            
        try {
            parseExtraKeys(props);
        } catch (Exception e) {
            toastException(e);
        }
        
        try {
            parseBackKeys(props);
        } catch (Exception e) {
            toastException(e);
        }
        
        try {
            parseShortcuts(props);
        } catch (Exception e) {
            toastException(e);
        }
    }
    
    void toastException(Exception e) {
        Toast.makeText(context, "Error loading properties: " + e.getMessage(), Toast.LENGTH_LONG).show();
        Log.e("termux", "Error loading props", e);
    }
    
    Properties findProps() {
        File propsFile = new File(TermuxService.HOME_PATH + "/.termux/termux.properties");
        if (!propsFile.exists())
            propsFile = new File(TermuxService.HOME_PATH + "/.config/termux/termux.properties");

        Properties props = new Properties();
        if (propsFile.isFile() && propsFile.canRead()) {
            try (FileInputStream in = new FileInputStream(propsFile)) {
                props.load(new InputStreamReader(in, "utf-8")); // "utf-8" is the most useful default nowadays
            }
        }
        return props;
    }
    
    void setDefaultBellBehaviour() {
        mBellBehaviour = BELL_VIBRATE;
    }
    
    void parseBellBehaviour() {
        switch (props.getProperty("bell-character", "vibrate")) {
            case "beep":
                mBellBehaviour = BELL_BEEP;
                break;
            case "ignore":
                mBellBehaviour = BELL_IGNORE;
                break;
            default: // "vibrate".
                mBellBehaviour = BELL_VIBRATE;
                break;
        }
    }
    
    /** all(x instanceof JSONArray for x in list) */
    void allJsonArray(JSONArray list) {
        for(Object x : list)
            if(!(x instanceof JSONArray))
                return false;
        return true;
    }
    
    /** all(x not instanceof JSONArray for x in list) */
    void allNotJsonArray(JSONArray list) {
        for(Object x : list)
            if(x instanceof JSONArray)
                return false;
        return true;
    }
    
    void setDefaultExtraKeys() {
        mExtraKeys = new String[][]{{"ESC", "CTRL", "ALT", "TAB", "-", "/", "|"}};
    }
    
    void parseExtraKeys(Properties props) throws Exception {
        String property = props.getProperty("extra-keys");
        
        if(property == null)
            return 
        
        boolean isArray = property.startsWith("[");
        boolean isObject = property.startsWith("{");
        boolean isString = !isArray && !isObject;
        
        JSONArray matrix = new JSONArray(); // dimension 0, no keys
        if(isArray) {
            JSONArray matrix = new JSONArray(property);
            if(allJsonArray(matrix)) {
                // we already have a matrix (dimension 2), do nothing
                // or we have an empty array, do nothing.
            } else if(allNotJsonArray(matrix)) {
                // we have one line, we convert it to an array containing that array
                // python matrix = [matrix]
                matrix = new JSONArray().put(matrix);
            } else {
                throw Exception("extra-keys: Contains a list of mixed type, please use a list of strings or a list of list of strings");
            }
        } else if(isString) {
            // remove " or ' at the beginning and end of the string
            throw new Exception("extra-keys: Strings are not yet implemented");
        } else if(isObject) {
            throw new Exception("extra-keys: JSON Objects are not yet implemented");
        }
        
        String[][] newMatrix = new String[matrix.length()][];
        for(int i = 0; i < matrix.length(); i++) {
            JSONArray line = matrix.getJSONArray(i);
            mExtraKeys[i] = new String[line.length()];
            
            for(int j = 0; j < line.length(); j++) {
                Object elem = line.getObject(j);
                
                if(elem instanceof JSONObject)
                    throw new Exception("extra-keys: Per key configuration are not yet implemented");
                
                String key = elem instanceof String ? elem :
                             elem == JSONObject.NULL ? " " :
                             elem.toString(); // some kind of integer, float or boolean.
                
                mExtraKeys[i][j] = key;
            }
        }
        
        mExtraKeys = newMatrix;
    }
    
    void setDefaultShortcuts() {
        shortcuts.clear();
    }
    
    void parseShortcuts(Properties props) throws Exception {
        shortcuts.clear();
        parseAction("shortcut.create-session", SHORTCUT_ACTION_CREATE_SESSION, props);
        parseAction("shortcut.next-session", SHORTCUT_ACTION_NEXT_SESSION, props);
        parseAction("shortcut.previous-session", SHORTCUT_ACTION_PREVIOUS_SESSION, props);
        parseAction("shortcut.rename-session", SHORTCUT_ACTION_RENAME_SESSION, props);
    }
    
    void setDefaultBackKeys(Properties props) throws Exception {
        mBackIsEscape = false;
    }
    
    void parseBackKeys(Properties props) throws Exception {
        mBackIsEscape = "escape".equals(props.getProperty("back-key", "back"));
    }

    public static final int SHORTCUT_ACTION_CREATE_SESSION = 1;
    public static final int SHORTCUT_ACTION_NEXT_SESSION = 2;
    public static final int SHORTCUT_ACTION_PREVIOUS_SESSION = 3;
    public static final int SHORTCUT_ACTION_RENAME_SESSION = 4;

    public final static class KeyboardShortcut {

        public KeyboardShortcut(int codePoint, int shortcutAction) {
            this.codePoint = codePoint;
            this.shortcutAction = shortcutAction;
        }

        final int codePoint;
        final int shortcutAction;
    }

    final List<KeyboardShortcut> shortcuts = new ArrayList<>();

    private void parseAction(String name, int shortcutAction, Properties props) {
        String value = props.getProperty(name);
        if (value == null) return;
        String[] parts = value.toLowerCase().trim().split("\\+");
        String input = parts.length == 2 ? parts[1].trim() : null;
        if (!(parts.length == 2 && parts[0].trim().equals("ctrl")) || input.isEmpty() || input.length() > 2) {
            Log.e("termux", "Keyboard shortcut '" + name + "' is not Ctrl+<something>");
            return;
        }

        char c = input.charAt(0);
        int codePoint = c;
        if (Character.isLowSurrogate(c)) {
            if (input.length() != 2 || Character.isHighSurrogate(input.charAt(1))) {
                Log.e("termux", "Keyboard shortcut '" + name + "' is not Ctrl+<something>");
                return;
            } else {
                codePoint = Character.toCodePoint(input.charAt(1), c);
            }
        }
        shortcuts.add(new KeyboardShortcut(codePoint, shortcutAction));
    }

}
