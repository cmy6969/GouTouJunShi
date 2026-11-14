/*
 * Compile-time stub of the classic Xposed API (api-82 surface subset).
 *
 * This file is NOT part of the shipped module: it is compiled into a separate
 * jar that is placed on the *compile only* classpath (see app/build.gradle,
 * `compileOnly files('libs/xposed-api-stub.jar')`).  At runtime every one of
 * these classes is provided by the Xposed framework that loads the module.
 *
 * Keeping the stub local keeps the project buildable offline and makes the
 * exact ABI the module depends on explicit.
 */
package de.robv.android.xposed;

import java.lang.reflect.Member;

import de.robv.android.xposed.callbacks.XCallback;

public abstract class XC_MethodHook extends XCallback {

    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;
    public static final int PRIORITY_HIGHEST = 10000;

    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public final void setPriority(int priority) {
    }

    public final int getPriority() {
        return PRIORITY_DEFAULT;
    }

    public final class Unhook {
        public final Member hookMethod;
        public final XC_MethodHook callback;

        private Unhook(Member hookMethod, XC_MethodHook callback) {
            this.hookMethod = hookMethod;
            this.callback = callback;
        }

        public Member getHookedMethod() {
            return hookMethod;
        }

        public XC_MethodHook getCallback() {
            return callback;
        }

        public void unhook() {
        }
    }

    public static final class MethodHookParam extends XCallback.Param {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result = null;
        private Throwable throwable = null;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }
}
