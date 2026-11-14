package com.goutou.junshi.stealth;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import com.goutou.junshi.core.Rf;

/**
 * Builds a synthetic okhttp3 response that claims success.
 *
 * <p>Used to answer monitoring uploads locally: the host sees {@code HTTP 200 {"success":true}} and
 * stops retrying, while the payload never leaves the device.  Everything is reflective because the
 * host bundles its own okhttp and the module has no compile dependency on it.</p>
 */
final class OkhttpFake {

    private static final String BODY_JSON = "{\"success\":true,\"result\":true,\"data\":null}";

    private OkhttpFake() {
    }

    /** @return a ready {@code okhttp3.Response}, or {@code null} when the host okhttp is incompatible. */
    static Object success(String url) {
        try {
            Class<?> response = Class.forName("okhttp3.Response");
            Class<?> builderType = Class.forName("okhttp3.Response$Builder");
            Class<?> requestType = Class.forName("okhttp3.Request");
            Class<?> bodyType = Class.forName("okhttp3.ResponseBody");
            Class<?> protocolType = Class.forName("okhttp3.Protocol");

            Object builder = builderType.getDeclaredConstructor().newInstance();
            Object request = request(url, requestType);
            if (request == null) {
                return null;
            }
            invoke(builder, "request", requestType, request);
            Object protocol = enumValue(protocolType, "HTTP_1_1");
            if (protocol != null) {
                invoke(builder, "protocol", protocolType, protocol);
            }
            invoke(builder, "code", int.class, 200);
            invoke(builder, "message", String.class, "OK");
            Object body = body(bodyType);
            if (body != null) {
                invoke(builder, "body", bodyType, body);
            }
            Object built = invoke(builder, "build");
            return response.isInstance(built) ? built : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object request(String url, Class<?> requestType) {
        try {
            Class<?> builderType = Class.forName("okhttp3.Request$Builder");
            Object builder = builderType.getDeclaredConstructor().newInstance();
            invoke(builder, "url", String.class, url);
            Object req = invoke(builder, "build");
            return requestType.isInstance(req) ? req : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object body(Class<?> bodyType) {
        Object media = mediaType();
        // okhttp3 3.x: ResponseBody.create(MediaType, String)
        Object b = staticCreate(bodyType, media, BODY_JSON, media == null ? null : media.getClass());
        if (b != null) {
            return b;
        }
        // okhttp3 4.x: ResponseBody.create(String, MediaType)
        return staticCreate(bodyType, BODY_JSON, media, null);
    }

    private static Object staticCreate(Class<?> owner, Object first, Object second, Class<?> firstType) {
        try {
            for (Method m : owner.getDeclaredMethods()) {
                if (!"create".equals(m.getName()) || !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 2) {
                    continue;
                }
                if (ps[1] == String.class && (firstType == null || ps[0].isAssignableFrom(firstType))) {
                    m.setAccessible(true);
                    return m.invoke(null, first, second);
                }
                if (ps[0] == String.class) {
                    m.setAccessible(true);
                    return m.invoke(null, first, second);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object mediaType() {
        try {
            Class<?> type = Class.forName("okhttp3.MediaType");
            Method get = Rf.arity(type, "parse", 1);
            if (get == null) {
                get = Rf.arity(type, "get", 1);
            }
            if (get == null) {
                return null;
            }
            return get.invoke(null, "application/json");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object enumValue(Class<?> type, String name) {
        try {
            for (Object constant : type.getEnumConstants()) {
                if (name.equals(String.valueOf(constant))) {
                    return constant;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object invoke(Object target, String name, Class<?> paramType, Object arg) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(name, paramType);
            m.setAccessible(true);
            return m.invoke(target, arg);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invoke(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
