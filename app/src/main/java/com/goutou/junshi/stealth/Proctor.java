package com.goutou.junshi.stealth;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import com.goutou.junshi.core.Cfg;
import com.goutou.junshi.core.Hooks;
import com.goutou.junshi.core.Logx;
import com.goutou.junshi.core.Rf;

/**
 * Exam proctoring countermeasures.
 *
 * <p>The host application reports the exam environment through four channels: a face capture task, a
 * screen capture service, a monitoring event uploader, and a JavaScript bridge used by the exam web
 * page.  This class disables the local endpoints of those channels — no capture is produced, no
 * event is uploaded — while leaving the rest of the exam flow untouched so that answering and
 * submission still work.</p>
 *
 * <p>All hooks are optional and fail open: if a class of a newer host version is missing, the
 * feature is skipped and the log records it.</p>
 */
public final class Proctor {

    /** Classes that run face capture / exam live supervision. Every method becomes a no-op. */
    private static final String[] FACE_CLASSES = {
            "com.chaoxing.mobile.exam.collect.FaceCollectManager",
            "com.chaoxing.mobile.exam.face.ExamFaceImageCapture",
            "com.chaoxing.mobile.exam.face.task.ExamFaceCaptureTask",
            "com.chaoxing.mobile.exam.live.ExamLiveManager"
    };

    /** Screen monitoring uploaders (the package moved between host releases). */
    private static final String[] MONITOR_CLASSES = {
            "com.chaoxing.mobile.exam.ScreenMonitorUploadDispatcher",
            "com.chaoxing.mobile.exam.collect.ScreenMonitorUploadDispatcher",
            "com.chaoxing.mobile.exam.upload.ScreenMonitorUploadDispatcher"
    };

    /** Exam page JS bridges that report focus / blur / environment changes. */
    private static final String[] BRIDGE_CLASSES = {
            "com.chaoxing.mobile.webapp.jsprotocal.exam.ExamEveriskProtocol",
            "com.chaoxing.mobile.webapp.jsprotocol.exam.ExamEveriskProtocol",
            "com.chaoxing.mobile.webapp.jsprotocal.exam.g"
    };

    /** URL fragments of monitoring uplinks that must never reach the server. */
    private static final String[] UPLINK_MARKS = {
            "monitor", "screenshot", "screencap", "faceimg", "facecapture", "examface",
            "exam/monitor", "supervise", "proctor", "screenupload", "uplaodface"
    };

    private Proctor() {
    }

    public static void install(ClassLoader cl, Cfg.Snapshot cfg) {
        if (!cfg.proctor) {
            return;
        }
        int muted = 0;
        muted += muteAll(cl, FACE_CLASSES, "face");
        muted += muteAll(cl, MONITOR_CLASSES, "monitor");
        muted += muteAll(cl, BRIDGE_CLASSES, "bridge");
        muted += silenceProjection(cl);
        muted += guardUplink();
        Logx.i("proctor: active, neutralized methods=" + muted);
    }

    /** Replaces the capture/report entries of every existing candidate class with a no-op. */
    private static int muteAll(ClassLoader cl, String[] names, String tag) {
        int count = 0;
        for (String name : names) {
            Class<?> c = Rf.cls(cl, name);
            if (c == null) {
                continue;
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            int muted = 0;
            for (Method m : Rf.allMethods(c)) {
                if (m.getDeclaringClass() == Object.class
                        || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                    continue;
                }
                Class<?> returnType = m.getReturnType();
                int params = m.getParameterTypes().length;
                // Only the capture side is muted: actions (void), state getters (no-arg object) and
                // boolean predicates.  Business methods with arguments keep their behaviour so that
                // answering and submission still work.
                boolean capture = returnType == void.class
                        || (params == 0 && !returnType.isPrimitive())
                        || (params <= 2 && (returnType == boolean.class || returnType == Boolean.class));
                if (!capture) {
                    continue;
                }
                if (!seen.add(m.getName())) {
                    continue; // hookAllMethods already covers every overload of this name
                }
                final Object neutral = returnType == boolean.class || returnType == Boolean.class
                        ? Boolean.FALSE : null;
                try {
                    Hooks.all(c, m.getName(), new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (Cfg.get().proctor) {
                                param.setResult(neutral);
                            }
                        }
                    });
                    muted++;
                    count++;
                } catch (Throwable ignored) {
                }
            }
            Logx.i("proctor: muted " + tag + " -> " + name + " (" + muted + " entries)");
        }
        return count;
    }

    /**
     * Keeps the media projection service from starting a capture session.
     *
     * <p>Returning {@code START_NOT_STICKY} keeps the service contract intact; the system simply has
     * no surface to record.</p>
     */
    private static int silenceProjection(ClassLoader cl) {
        int count = 0;
        Class<?> svc = Rf.cls(cl, "com.chaoxing.mobile.course.shot.MediaProjectionService");
        if (svc != null) {
            Hooks.all(svc, "onStartCommand", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Cfg.get().proctor) {
                        param.setResult(2 /* START_NOT_STICKY */);
                    }
                }
            });
            count++;
        }
        for (String name : new String[]{"com.chaoxing.mobile.course.shot.a", "com.chaoxing.mobile.course.shot.b"}) {
            Class<?> c = Rf.cls(cl, name);
            if (c != null) {
                count += muteAll(cl, new String[]{name}, "shot");
            }
        }
        return count;
    }

    /**
     * Intercepts the okhttp interceptor chain.
     *
     * <p>Every monitoring upload is answered locally with a successful empty payload, which keeps the
     * host from retrying and keeps the teacher-side page in its "already reported" state.  Building
     * the response is fully reflective so that the module does not depend on the okhttp version the
     * host bundles.</p>
     */
    private static int guardUplink() {
        Class<?> chain = null;
        for (String name : new String[]{
                "okhttp3.internal.http.RealInterceptorChain",
                "okhttp3.internal.connection.RealInterceptorChain"}) {
            chain = Hooks.hostClass(name);
            if (chain != null) {
                break;
            }
        }
        if (chain == null) {
            return 0;
        }
        Hooks.all(chain, "proceed", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Cfg.get().proctor) {
                    return;
                }
                String url = requestUrl(param.thisObject);
                if (url == null || !matches(url)) {
                    return;
                }
                Object fake = OkhttpFake.success(url);
                if (fake != null) {
                    param.setResult(fake);
                    Logx.i("proctor: uplink blocked -> " + url);
                } else {
                    param.setThrowable(new java.io.IOException("timeout"));
                    Logx.w("proctor: uplink dropped (hard) -> " + url);
                }
            }
        });
        return 1;
    }

    private static String requestUrl(Object chain) {
        try {
            Object request = Rf.call(chain, Rf.method(chain.getClass(), "request"));
            if (request == null) {
                return null;
            }
            Object httpUrl = Rf.call(request, Rf.method(request.getClass(), "url"));
            return httpUrl == null ? null : String.valueOf(httpUrl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean matches(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String mark : UPLINK_MARKS) {
            if (lower.contains(mark)) {
                return true;
            }
        }
        return false;
    }
}
