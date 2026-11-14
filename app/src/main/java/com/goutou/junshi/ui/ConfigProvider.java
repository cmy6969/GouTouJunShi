package com.goutou.junshi.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.goutou.junshi.core.Cfg;

/**
 * Exposes the saved configuration to the hooked process.
 *
 * <p>The host application has its own uid and no storage permission, so it cannot read either the
 * module's private preferences or anything under {@code Download}.  A tiny exported provider closes
 * that gap without asking the user for any permission: the host calls {@code call("get")} and gets
 * the JSON string back.</p>
 *
 * <p>Only the configuration is exposed — no file system access, no other data.</p>
 */
public final class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.goutou.junshi.config";
    public static final String METHOD_GET = "get";
    public static final String KEY_JSON = "json";

    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/config");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if (METHOD_GET.equals(method)) {
            result.putString(KEY_JSON, storedJson(getContext()));
        }
        return result;
    }

    /** Reads the JSON the settings activity saved (empty string when nothing was saved yet). */
    public static String storedJson(Context ctx) {
        if (ctx == null) {
            return "";
        }
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(Cfg.PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(Cfg.PREFS_KEY, "");
            if (json != null) {
                return json;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/json";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
