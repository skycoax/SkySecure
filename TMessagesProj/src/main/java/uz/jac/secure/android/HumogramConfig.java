package uz.jac.secure.android;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.R;

/**
 * The handful of choices that make this app feel like the user's, kept in one
 * small store.
 *
 * <p>Right now that is one setting: which ornament the chat list wears. It is a
 * cosmetic, per-device preference — not account data — so it lives in a plain
 * {@link SharedPreferences} file rather than anywhere that syncs. A cached
 * value keeps the read off the draw path, which asks for it every frame.
 */
public final class HumogramConfig {

    private static final String PREFS = "jac_humogram";
    private static final String KEY_ORNAMENT = "ornament";
    private static final String KEY_LINK_HYGIENE = "link_hygiene";
    private static final String KEY_CHECKUP_AT = "checkup_at";
    private static final String KEY_CHECKUP_REMIND_AT = "checkup_remind_at";

    /**
     * The @username of the recommended cyber-awareness channel, without the {@code @}.
     *
     * <p>Were it ever emptied again, every surface that shows it checks for
     * emptiness and hides itself, so no dead link can ship.
     */
    public static final String SECURITY_CHANNEL = "jizzax_kiber";

    private static final String KEY_CHANNEL_HINT_DONE = "channel_hint_done";

    /**
     * Whether the chat list should still be suggesting the security channel.
     *
     * <p>Once: the hint disappears forever after one tap or one dismissal.
     * A recommendation that keeps coming back is an advertisement, and this
     * fork's standing with its users rests on never advertising at them.
     */
    public static boolean shouldShowChannelHint(Context context) {
        if (SECURITY_CHANNEL.isEmpty()) {
            return false;
        }
        try {
            return !prefs(context).getBoolean(KEY_CHANNEL_HINT_DONE, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setChannelHintDismissed(Context context) {
        try {
            prefs(context).edit().putBoolean(KEY_CHANNEL_HINT_DONE, true).apply();
        } catch (Throwable ignored) {
        }
    }

    /** The suzani border this shipped with — the default. */
    public static final int ORNAMENT_SUZANI = 0;
    /** An eight-point-star geometric lattice (khatam / mashrabiya). */
    public static final int ORNAMENT_GEOMETRY = 1;
    /** The crescent-and-stars of the Uzbek flag, as a repeating motif. */
    public static final int ORNAMENT_FLAG = 2;
    /** No ornament at all — a plain background. */
    public static final int ORNAMENT_OFF = 3;

    private static volatile int cachedOrnament = -1;

    private HumogramConfig() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getOrnament(Context context) {
        int c = cachedOrnament;
        if (c >= 0) {
            return c;
        }
        int value = ORNAMENT_SUZANI;
        try {
            value = prefs(context).getInt(KEY_ORNAMENT, ORNAMENT_SUZANI);
        } catch (Throwable ignored) {
        }
        cachedOrnament = value;
        return value;
    }

    public static void setOrnament(Context context, int value) {
        cachedOrnament = value;
        try {
            prefs(context).edit().putInt(KEY_ORNAMENT, value).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Whether tracking parameters are stripped from links before they open.
     *
     * <p>On by default: removing {@code utm_*} or {@code fbclid} never breaks
     * a page — these parameters exist for the advertiser, not the server — so
     * the safe setting is also the harmless one. Cached like the ornament,
     * because {@code Browser.openUrl} sits on the tap path.
     */
    private static volatile int cachedLinkHygiene = -1;

    public static boolean isLinkHygiene(Context context) {
        int c = cachedLinkHygiene;
        if (c >= 0) {
            return c == 1;
        }
        boolean value = true;
        try {
            value = prefs(context).getBoolean(KEY_LINK_HYGIENE, true);
        } catch (Throwable ignored) {
        }
        cachedLinkHygiene = value ? 1 : 0;
        return value;
    }

    public static void setLinkHygiene(Context context, boolean value) {
        cachedLinkHygiene = value ? 1 : 0;
        try {
            prefs(context).edit().putBoolean(KEY_LINK_HYGIENE, value).apply();
        } catch (Throwable ignored) {
        }
    }

    /** When the security checkup last ran, in wall-clock millis; 0 = never. */
    public static long getCheckupAt(Context context) {
        try {
            return prefs(context).getLong(KEY_CHECKUP_AT, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static void setCheckupAt(Context context, long when) {
        try {
            prefs(context).edit().putLong(KEY_CHECKUP_AT, when).apply();
        } catch (Throwable ignored) {
        }
    }

    /** When the monthly checkup reminder last fired; keeps the nudge to once a month. */
    public static long getCheckupRemindAt(Context context) {
        try {
            return prefs(context).getLong(KEY_CHECKUP_REMIND_AT, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static void setCheckupRemindAt(Context context, long when) {
        try {
            prefs(context).edit().putLong(KEY_CHECKUP_REMIND_AT, when).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * The tile drawable for the chosen ornament, or 0 for none.
     *
     * All tiles are white silhouettes with graded alpha, so the same tinting
     * path in {@link HumoOrnament} works for every one — the choice here is
     * geometry, not colour.
     */
    public static int ornamentTileRes(int ornament) {
        switch (ornament) {
            case ORNAMENT_GEOMETRY:
                return R.drawable.humo_geo;
            case ORNAMENT_FLAG:
                return R.drawable.humo_flag;
            case ORNAMENT_OFF:
                return 0;
            case ORNAMENT_SUZANI:
            default:
                return R.drawable.humo_ornament;
        }
    }
}
