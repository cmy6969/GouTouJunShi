package de.robv.android.xposed;

import android.content.SharedPreferences;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time stub of {@code XSharedPreferences} (classic Xposed API).
 *
 * <p>Only the surface the module uses is declared here; at runtime the class comes from the
 * framework, which on LSPosed redirects the read to the module's own preferences.</p>
 */
public final class XSharedPreferences implements SharedPreferences {

    public XSharedPreferences(String packageName, String prefFileName) {
    }

    public XSharedPreferences(String packageName) {
    }

    public XSharedPreferences(File prefFile) {
    }

    public void reload() {
    }

    public boolean makeWorldReadable() {
        return true;
    }

    public File getFile() {
        return null;
    }

    @Override
    public Map<String, ?> getAll() {
        return null;
    }

    @Override
    public String getString(String key, String defValue) {
        return defValue;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        return defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        return defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        return defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return defValue;
    }

    @Override
    public boolean contains(String key) {
        return false;
    }

    @Override
    public Editor edit() {
        return null;
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }
}
