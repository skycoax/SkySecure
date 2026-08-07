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

    /** Brand accent, used only for the SAFE action. Never for a warning. */
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

    public static int primary(Context c) {
        return isDark(c) ? PRIMARY_DARK : PRIMARY_LIGHT;
    }

    public static int onPrimary(Context c) {
        return ON_PRIMARY;
    }

    public static int screen(Context c) {
        return isDark(c) ? SCREEN_DARK : SCREEN_LIGHT;
    }

    public static int card(Context c) {
        return isDark(c) ? CARD_DARK : CARD_LIGHT;
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
