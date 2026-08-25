package uz.jac.secure.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.Utilities;

/**
 * The false ("duress") passcode: a second unlock code that, when entered,
 * quietly wipes instead of unlocking.
 *
 * <h3>What it is for</h3>
 *
 * Someone with power over the phone's owner — at a checkpoint, in a search,
 * during coercion — demands the passcode. The owner gives this one. Instead of
 * unlocking, the app erases everything it holds on the phone and restarts
 * empty, so what the adversary can open is a fresh Telegram with nothing to
 * read and no local session to seize.
 *
 * <h3>Why it is stored the way the real passcode is</h3>
 *
 * The code is kept only as a salted SHA-256 hash, byte-for-byte the scheme
 * {@code SharedConfig} uses for the real passcode (salt ∥ code ∥ salt, hashed).
 * A duress code held any weaker than the real one would be the easier thing to
 * attack, and the whole point is that the two are indistinguishable — including
 * to anyone who later reads the preferences file.
 *
 * <h3>The one rule the checker must enforce</h3>
 *
 * The real passcode always wins a tie. If someone sets the same string for both,
 * entering it must unlock, never wipe — a wipe triggered by the owner's own real
 * code would be a foot-gun far more common than the threat this defends against.
 * {@link #isDuress} is therefore only ever consulted <em>after</em> the real
 * passcode has already been ruled out.
 */
public final class DuressConfig {

    private static final String PREFS = "jac_duress";
    private static final String KEY_HASH = "hash";
    private static final String KEY_SALT = "salt";
    private static final String KEY_ACTION = "action";

    /** Log every account out (server-side) and wipe local data. The default. */
    public static final int ACTION_LOGOUT_ALL = 0;

    private DuressConfig() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        try {
            return prefs(context).getString(KEY_HASH, "").length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int getAction(Context context) {
        try {
            return prefs(context).getInt(KEY_ACTION, ACTION_LOGOUT_ALL);
        } catch (Throwable ignored) {
            return ACTION_LOGOUT_ALL;
        }
    }

    /**
     * Store {@code code} as the duress passcode, salted and hashed exactly as
     * {@code SharedConfig} stores the real one.
     */
    public static void set(Context context, String code, int action) {
        try {
            byte[] salt = new byte[16];
            Utilities.random.nextBytes(salt);
            prefs(context).edit()
                    .putString(KEY_HASH, hash(code, salt))
                    .putString(KEY_SALT, Utilities.bytesToHex(salt))
                    .putInt(KEY_ACTION, action)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    public static void clear(Context context) {
        try {
            prefs(context).edit().remove(KEY_HASH).remove(KEY_SALT).remove(KEY_ACTION).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Whether {@code code} is the configured duress passcode.
     *
     * <p>Constant-ish: it always computes the hash when a code is set, so a
     * wrong guess costs the same work as a right one. Returns false on any
     * error — a duress check that throws must fail toward "ordinary wrong
     * passcode", never toward an accidental wipe.
     */
    public static boolean isDuress(Context context, String code) {
        try {
            String stored = prefs(context).getString(KEY_HASH, "");
            if (stored.length() == 0) {
                return false;
            }
            byte[] salt = Utilities.hexToBytes(prefs(context).getString(KEY_SALT, ""));
            if (salt == null || salt.length == 0) {
                return false;
            }
            return stored.equals(hash(code, salt));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String hash(String code, byte[] salt) throws Exception {
        byte[] codeBytes = code.getBytes("UTF-8");
        byte[] bytes = new byte[32 + codeBytes.length];
        System.arraycopy(salt, 0, bytes, 0, 16);
        System.arraycopy(codeBytes, 0, bytes, 16, codeBytes.length);
        System.arraycopy(salt, 0, bytes, codeBytes.length + 16, 16);
        return Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
    }
}
