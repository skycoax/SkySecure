package uz.jac.secure.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.R;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The device's bearer token for the scanning API.
 *
 * <h3>What a "device" is here, and what it deliberately is not</h3>
 *
 * It is an opaque row on our server with no phone number, no Telegram user id,
 * and no link to any of them. It exists so the API can rate-limit per install
 * rather than per IP — without it, one misbehaving device throttles a whole
 * mobile carrier's NAT, and everyone behind it silently stops getting verdicts.
 *
 * It is NOT an identity. Registration sends a platform string and an app
 * version, nothing else, and there is no call anywhere in this package that
 * could attach a token to a user: verdict telemetry has no device column to put
 * one in. See docs/PRIVACY.md.
 *
 * <h3>Failure is normal and must stay quiet</h3>
 *
 * A phone with no connectivity, or a server that is down, must produce a
 * scanner that still works — local heuristics run regardless, and they are the
 * half that catches a homograph registered an hour ago. So every failure path
 * here returns null rather than throwing, and the engine treats a null token
 * as "no backend this time" rather than as an error worth showing anyone.
 */
public final class BackendTokens implements uz.jac.secure.core.engine.EngineFactory.TokenSource {

    private static final String PREFS = "jac_secure";
    private static final String KEY_TOKEN = "device_token";

    private final Context context;
    private final String baseUrl;
    private final String appVersion;
    /** Guards against a registration storm when several scans start at once. */
    private final AtomicBoolean registering = new AtomicBoolean(false);

    private volatile String cached;

    public BackendTokens(Context context, String baseUrl, String appVersion) {
        this.context = context.getApplicationContext();
        // Trailing slash trimmed once here rather than at every call site: the
        // value comes from a string resource a human edits, and "…/" plus
        // "/v1/…" is a 404 that looks like the server being down.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.appVersion = appVersion;
        this.cached = prefs().getString(KEY_TOKEN, null);
    }

    /**
     * Current token, or null.
     *
     * Called from a scanner thread, never the main thread — the engine only
     * asks for a token when it is about to make a network call, which is
     * already off the UI thread by construction.
     */
    @Override
    public String token() {
        String token = cached;
        if (token != null) {
            return token;
        }
        // Whoever wins the flag registers; everyone else gets null for this
        // one scan and falls back to local analysis. Blocking the losers on a
        // lock would stall several scans behind one HTTP round trip, and the
        // next scan will have the token anyway.
        if (!registering.compareAndSet(false, true)) {
            return null;
        }
        try {
            String fresh = register();
            if (fresh != null) {
                cached = fresh;
                prefs().edit().putString(KEY_TOKEN, fresh).apply();
            }
            return fresh;
        } finally {
            registering.set(false);
        }
    }

    /** Drop the stored token; the next scan re-registers. For a 401. */
    public void invalidate() {
        cached = null;
        prefs().edit().remove(KEY_TOKEN).apply();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String register() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(baseUrl + "/v1/device/register")
                    .toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(10_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("content-type", "application/json");
            // Platform and app version only. There is no field here for
            // anything else, which is the point — see the class comment.
            String body = "{\"platform\":\"android\",\"app_version\":\"" + appVersion + "\"}";
            connection.getOutputStream().write(body.getBytes("UTF-8"));

            if (connection.getResponseCode() / 100 != 2) {
                return null;
            }
            return extractToken(read(connection.getInputStream()));
        } catch (Throwable t) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String read(InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        stream.close();
        return out.toString("UTF-8");
    }

    /**
     * Pull "token" out of the response.
     *
     * Hand-rolled rather than pulling in a JSON parser: this module is linked
     * into an app that already carries several, and the response shape is one
     * field we control at both ends.
     */
    private static String extractToken(String json) {
        int key = json.indexOf("\"token\"");
        if (key < 0) {
            return null;
        }
        int start = json.indexOf('"', json.indexOf(':', key) + 1);
        if (start < 0) {
            return null;
        }
        int end = json.indexOf('"', start + 1);
        return end > start ? json.substring(start + 1, end) : null;
    }
}
