package de.robv.android.xposed.callbacks;

import java.lang.reflect.Member;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public abstract class XCallback implements Comparable<XCallback> {

    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;
    public static final int PRIORITY_HIGHEST = 10000;

    public final int priority;

    public XCallback() {
        this.priority = PRIORITY_DEFAULT;
    }

    public XCallback(int priority) {
        this.priority = priority;
    }

    public static void callAll(Param param) {
    }

    @Override
    public int compareTo(XCallback other) {
        return Integer.compare(other.priority, this.priority);
    }

    public static abstract class Param {
        public Object getObjectExtra(String key) {
            return null;
        }

        public void setObjectExtra(String key, Object o) {
        }
    }
}
