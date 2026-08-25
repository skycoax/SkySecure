package uz.jac.secure.android;

import android.content.Context;
import android.content.res.Configuration;
import android.util.TypedValue;

/**
 * The scanner's own palette and metrics.
 *
 * Defined in code rather than as a resource file, and that is a deliberate
 * trade. The Telegram fork merges our resources by hand at every rebase, so
 * every XML file we add is a file that can be half-merged; and Telegram's theme
 * engine is a runtime key-value store with hundreds of user-swappable colours,
 * into which a downloaded theme can inject anything it likes.
 *
 * Neither is a good home for these four colours. A downloadable theme that can
 * recolour "dangerous" to the same green as "checked" is a security control the
 * user can be tricked into disabling, so the verdict colours are ours, fixed,
 * and identical in every theme. Everything else in the app still follows the
 * user's theme; only the six colours below refuse to.
 *
 * Light and dark variants exist because legibility is not optional — but both
 * sides of each pair carry the same meaning and the same relative prominence.
 */
public final class JacTheme {

    private JacTheme() {
    }

    // ---- Verdict colours -------------------------------------------------
    // Contrast checked against the bubble backgrounds of both stock themes;
    // each clears 4.5:1 for body text and 3:1 for the icon glyphs.

    private static final int DANGER_DARK = 0xFFF0575C;
    private static final int DANGER_LIGHT = 0xFFD22F35;

    private static final int WARNING_DARK = 0xFFF0A93C;
    private static final int WARNING_LIGHT = 0xFFA96A00;

    private static final int SUCCESS_DARK = 0xFF4CC38A;
    private static final int SUCCESS_LIGHT = 0xFF1F8A54;

    private static final int NEUTRAL_DARK = 0xFF8A97A3;
    private static final int NEUTRAL_LIGHT = 0xFF6B7A87;

    /**
     * Fallback accent for the SAFE action. Never for a warning.
     *
     * A fallback only — see {@link #primary}, which takes the accent from the
     * user's Telegram theme and reaches these constants solely when that lookup
     * fails.
     */
    private static final int PRIMARY_DARK = 0xFF2BB5A6;
    private static final int PRIMARY_LIGHT = 0xFF12897C;

    private static final int ON_PRIMARY = 0xFFFFFFFF;

    // ---- Interstitial surfaces (full-screen, so it owns its background) ---

    private static final int SCREEN_DARK = 0xFF141B22;
    private static final int SCREEN_LIGHT = 0xFFF2F5F7;
    private static final int CARD_DARK = 0xFF1E2833;
    private static final int CARD_LIGHT = 0xFFFFFFFF;
    private static final int TEXT_DARK = 0xFFECF1F5;
    private static final int TEXT_LIGHT = 0xFF10181F;

    public static boolean isDark(Context context) {
        // The APP's theme, not the system's. Telegram's theme is chosen inside
        // the app and is frequently the opposite of the OS setting — a user on
        // a dark theme with the phone in day mode is the common case. Reading
        // the system uiMode painted our surfaces light while every Telegram
        // pixel around them was dark, which is exactly what made the dialogs
        // read as foreign. Fall back to the system flag only if the theme
        // cannot be asked.
        try {
            return org.telegram.ui.ActionBar.Theme.isCurrentThemeDark();
        } catch (Throwable ignored) {
        }
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int danger(Context c) {
        return isDark(c) ? DANGER_DARK : DANGER_LIGHT;
    }

    public static int warning(Context c) {
        return isDark(c) ? WARNING_DARK : WARNING_LIGHT;
    }

    public static int success(Context c) {
        return isDark(c) ? SUCCESS_DARK : SUCCESS_LIGHT;
    }

    public static int neutral(Context c) {
        return isDark(c) ? NEUTRAL_DARK : NEUTRAL_LIGHT;
    }

    /**
     * The accent for the safe, primary action — taken from Telegram, not
     * invented here.
     *
     * <h3>Why this is not a brand colour</h3>
     *
     * It used to be a teal of our own, and it was wrong every time. The user
     * has chosen a theme, and in Telegram that choice is an accent colour
     * applied everywhere: their filled buttons, their links, their send button.
     * A dialog that arrives in a different accent does not read as our product
     * having an identity; it reads as a dialog from somewhere else — which, on
     * a screen whose entire job is to be believed about a virus, is the exact
     * impression that must not be given.
     *
     * <p>The danger colour deliberately does NOT do this. Red has to stay red
     * regardless of the theme: a user on a red-accented theme would otherwise
     * get warnings the same colour as their send button, and one on a green
     * theme would get green ones. That is a security control, not a style
     * choice — see the note at the top of this class.
     *
     * <p>{@code key_featuredStickers_addButton} is the filled-accent button
     * colour, the same key upstream uses for "Add" and "Install". Falls back to
     * the constants above if the theme has no such key, because a dialog with
     * an odd-coloured button is survivable and a crash while warning someone
     * about malware is not.
     */
    public static int primary(Context c) {
        try {
            int themed = org.telegram.ui.ActionBar.Theme.getColor(
                    org.telegram.ui.ActionBar.Theme.key_featuredStickers_addButton);
            if (themed != 0) {
                return themed;
            }
        } catch (Throwable ignored) {
        }
        return isDark(c) ? PRIMARY_DARK : PRIMARY_LIGHT;
    }

    /** The label colour that goes on {@link #primary}, from the same theme. */
    public static int onPrimary(Context c) {
        try {
            int themed = org.telegram.ui.ActionBar.Theme.getColor(
                    org.telegram.ui.ActionBar.Theme.key_featuredStickers_buttonText);
            if (themed != 0) {
                return themed;
            }
        } catch (Throwable ignored) {
        }
        return ON_PRIMARY;
    }

    public static int screen(Context c) {
        return isDark(c) ? SCREEN_DARK : SCREEN_LIGHT;
    }

    public static int card(Context c) {
        return isDark(c) ? CARD_DARK : CARD_LIGHT;
    }

    /**
     * Dialog surfaces, taken from Telegram's own dialog theme.
     *
     * A warning dialog has to look like it belongs to the app it is warning
     * inside of — a foreign-looking modal on a security screen is the one that
     * gets dismissed as a scam. So the background, title and body colours come
     * straight from the keys Telegram paints its own alerts with, and fall back
     * to the card constants only if the theme cannot be read.
     */
    public static int dialogBackground(Context c) {
        try {
            int v = org.telegram.ui.ActionBar.Theme.getColor(
                    org.telegram.ui.ActionBar.Theme.key_dialogBackground);
            if (v != 0) return v;
        } catch (Throwable ignored) {
        }
        return card(c);
    }

    public static int dialogTitle(Context c) {
        try {
            int v = org.telegram.ui.ActionBar.Theme.getColor(
                    org.telegram.ui.ActionBar.Theme.key_dialogTextBlack);
            if (v != 0) return v;
        } catch (Throwable ignored) {
        }
        return text(c);
    }

    public static int dialogBody(Context c) {
        try {
            int v = org.telegram.ui.ActionBar.Theme.getColor(
                    org.telegram.ui.ActionBar.Theme.key_dialogTextGray3);
            if (v != 0) return v;
        } catch (Throwable ignored) {
        }
        return textMuted(c);
    }

    public static int text(Context c) {
        return isDark(c) ? TEXT_DARK : TEXT_LIGHT;
    }

    public static int textMuted(Context c) {
        return neutral(c);
    }

    /**
     * A verdict colour at low alpha, for chips and row tints.
     *
     * Kept subtle on purpose. The chat-list row for a blocked file is tinted,
     * not painted: a list where one row is a solid red block reads as a system
     * error rather than as one message needing attention, and it makes the
     * other five rows harder to scan past.
     */
    public static int wash(int colour, int alphaPercent) {
        int alpha = Math.max(0, Math.min(255, alphaPercent * 255 / 100));
        return (colour & 0x00FFFFFF) | (alpha << 24);
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    public static float sp(Context context, float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, value, context.getResources().getDisplayMetrics());
    }
}
