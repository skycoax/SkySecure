package uz.jac.secure.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.R;

/**
 * The Uzbek lattice, banded along the edges of a page.
 *
 * <h3>What this is for</h3>
 *
 * One tiled bitmap drawn as a border at the top and bottom of a screen, fading
 * out towards the middle, so the product reads as coming from somewhere rather
 * than as stock Telegram with a new name.
 *
 * <p>This is a <em>suzani border</em>, not a wallpaper. An embroidered panel puts
 * its geometry in a band around the edge and leaves the field open; the eye reads
 * the frame and then stops seeing it. That shape suits a chat list exactly: the
 * strongest ornament lands in the status bar and behind the tab bar, where there
 * is nothing to read, and has decayed to nothing by the time it reaches the rows.
 *
 * <p>It replaced an even wash across the whole page. A wash strong enough to be
 * seen is a wash sitting under every line of text, so it had to be held at 9-11%,
 * where it was not really visible at all — the worst of both. Banding buys the
 * strength back: the peak here is about two and a half times the old wash and
 * still lands nowhere near a row of text.
 *
 * <h3>Why the artwork carries no colour</h3>
 *
 * {@code humo_ornament} is a white bitmap with graded alpha, not the three-colour
 * textile it was drawn from. A literal-colour tile cannot work on both polarities
 * of theme: its cream ground sits at 97% luminance, so over white it disappears
 * (correct) but over a near-black page it <em>lifts the page</em> while the navy
 * figure stays dark, and the pattern arrives as a negative of itself. The navy
 * measured against both stock dark themes comes out around 1.01:1 — under one
 * part in a hundred — so no single opacity rescues it.
 *
 * <p>So the tile ships as geometry and the ink is chosen here, from the page it
 * is being drawn onto. The source palette survives as two alpha steps rather
 * than two hues, which costs nothing: at this strength navy and cyan are already
 * indistinguishable from each other.
 *
 * <h3>Why polarity is read from the page colour and not from {@code JacTheme}</h3>
 *
 * {@link JacTheme#isDark} answers "is the system in night mode", which is a
 * different question and can be the opposite answer. Telegram's theme is chosen
 * inside the app: a user may switch automatic night mode off entirely and pick a
 * dark theme as their day theme, and on that configuration the system flag says
 * light while every pixel on screen is dark. Painting a dark ink onto a dark page
 * is not a slightly-wrong shade, it is an invisible ornament.
 *
 * <p>Deciding from the luminance of the page colour is also what upstream does
 * for any theme it does not recognise by name (see {@code Theme.checkIsDark}),
 * and it is the only test that stays correct for a downloaded theme nobody has
 * seen. It cannot be fooled, because the input is the very colour the ornament
 * will be composited over.
 *
 * <h3>Lifetime</h3>
 *
 * One instance per host view, and one built paint per direction. Shaders are
 * built once and then only when the page colour or the band height actually
 * changes, because this is called from {@code onDraw} and {@code dispatchDraw}
 * on a list that invalidates aggressively — allocating a paint or decoding a
 * bitmap per frame here would be a dropped frame on every scroll. The per-frame
 * path is a {@code setAlpha}, a translate and a {@code drawRect}.
 */
public final class HumoOrnament {

    /** Dense at the band's top edge, gone at its bottom one: the top frieze. */
    public static final int FADE_DOWN = 0;

    /** Dense at the band's bottom edge, gone at its top one: the bottom frieze. */
    public static final int FADE_UP = 1;

    /**
     * Band heights, in dp.
     *
     * <p>The top band is measured from the top of the action bar, so it covers
     * the status bar at full strength, the title at roughly half, the stories row
     * at a quarter, and has reached zero before the first list row. The bottom
     * band is measured from the bottom of the screen: its peak sits in the
     * navigation bar area and the floating tab bar crosses it at a fifth to a
     * half, where the tab bar's own blur freezes the pattern behind glass.
     */
    public static final int TOP_BAND_DP = 160;
    public static final int BOTTOM_BAND_DP = 120;

    /** Ink for a light page: the darker of the two source blues. */
    private static final int INK_ON_LIGHT = 0xFF1E1A54;

    /** Ink for a dark page: the lighter one, or nothing would read at all. */
    private static final int INK_ON_DARK = 0xFF49C6E4;

    /**
     * Peak strength at the outer edge of a band, 0..255.
     *
     * <p>A dark page takes a little more than a light one — the eye resolves
     * less detail in the shadows — but the two are close enough that this is a
     * nudge rather than a separate design.
     *
     * <p>The light figure was tried at 84 first, on the strength of a photometric
     * reading that turned out to have been taken through the stories row rather
     * than against the bare page. Over real white it measured three times the
     * intended strength and the border stopped being a border: the lattice read
     * as wallpaper the header had been placed on top of. Sixty is the value that
     * survives contact with an empty chat list, which is the hardest case,
     * because there is nothing on the page to share the eye with.
     *
     * <p>They are the ceiling as well as the default, and they are affordable
     * only because of where they land. The old whole-page wash had to respect a
     * limit of roughly 15%, past which the lattice stops being a surface the
     * content sits on and becomes a thing the content sits <em>in front of</em>.
     * That limit still holds under text; it simply does not bind at the edge of
     * the screen, where by construction there is no text.
     */
    private static final int BAND_ON_LIGHT = 60;
    private static final int BAND_ON_DARK = 72;

    /**
     * The fade, as an eased alpha ramp from the dense edge to nothing.
     *
     * <p>A straight linear ramp reads as a gradient someone applied to a texture.
     * Holding near full strength for the first quarter and then falling away
     * quickly reads as a border with an edge to it, which is what embroidery
     * actually does. Four stops are enough to carry that curve.
     */
    private static final int[] FADE_COLOURS = {0xFFFFFFFF, 0xD8FFFFFF, 0x40FFFFFF, 0x00FFFFFF};
    private static final float[] FADE_STOPS = {0f, 0.28f, 0.62f, 1f};

    /** The tile itself, decoded once per chosen texture and shared by both bands. */
    private Bitmap tile;
    private int tileRes;

    /** Built state, one slot per fade direction. */
    private final Paint[] paints = new Paint[2];
    private final int[] builtForPage = new int[2];
    private final int[] builtForHeight = new int[2];
    private final int[] builtForRes = new int[2];
    private final boolean[] built = new boolean[2];

    /**
     * Paint one band of ornament across {@code width}, starting at {@code top}.
     *
     * <p>Draws nothing and throws nothing if the asset is missing; a brand
     * texture is not worth an exception raised inside a list's draw pass.
     *
     * @param top           y of the band's upper edge, in this canvas's coordinates.
     * @param height        band height in pixels. Fixed from dp by the caller, so
     *                      in practice this only ever changes on a density change.
     * @param fadeDirection {@link #FADE_DOWN} or {@link #FADE_UP} — which edge of
     *                      the band is the dense one.
     * @param pageColour    the colour already filling this view, which decides
     *                      both the ink and how strong the band may be.
     * @param strengthMul   0..1, a per-frame dimmer for states that want the
     *                      ornament out of the way (search, selection mode). It
     *                      rides on the paint's alpha, so it costs no rebuild.
     */
    public void drawBand(Canvas canvas, Context context, int width, float top, int height,
                         int fadeDirection, int pageColour, float strengthMul) {
        if (canvas == null || context == null || width <= 0 || height <= 0) {
            return;
        }
        if (fadeDirection != FADE_DOWN && fadeDirection != FADE_UP) {
            return;
        }
        float multiplier = strengthMul < 0f ? 0f : Math.min(strengthMul, 1f);
        if (multiplier <= 0f) {
            return;
        }
        // Which texture the user picked. 0 means "off" — draw nothing at all.
        final int wantRes = HumogramConfig.ornamentTileRes(HumogramConfig.getOrnament(context));
        if (wantRes == 0) {
            return;
        }
        if (!built[fadeDirection]
                || pageColour != builtForPage[fadeDirection]
                || height != builtForHeight[fadeDirection]
                || wantRes != builtForRes[fadeDirection]) {
            build(context, fadeDirection, pageColour, height, wantRes);
        }
        Paint paint = paints[fadeDirection];
        if (paint == null) {
            return;
        }
        boolean darkPage = ColorUtils.calculateLuminance(pageColour) < 0.5f;
        paint.setAlpha((int) ((darkPage ? BAND_ON_DARK : BAND_ON_LIGHT) * multiplier));

        // The gradient is built in the band's own space, 0..height, so the band
        // is positioned by moving the canvas rather than by re-making the shader.
        // A local matrix on a shader already wrapped in a ComposeShader is not
        // reliably picked up once the draw has been handed to the hardware
        // pipeline, and rebuilding per frame is exactly what this class exists to
        // avoid. The visible consequence is that the top band's tile phase rides
        // with the action bar as it collapses on scroll, which is the right
        // behaviour anyway: the ornament belongs to the bar, not to the window.
        canvas.save();
        canvas.translate(0, top);
        canvas.drawRect(0, 0, width, height, paint);
        canvas.restore();
    }

    private void build(Context context, int direction, int pageColour, int height, int tileResId) {
        built[direction] = true;
        builtForPage[direction] = pageColour;
        builtForHeight[direction] = height;
        builtForRes[direction] = tileResId;

        // Re-decode only when the chosen texture changes, not per band and not
        // per frame. The shader below wraps this bitmap, so both bands share one
        // decode and a texture switch drops the old one.
        if (tile == null || tileRes != tileResId) {
            tile = BitmapFactory.decodeResource(context.getResources(), tileResId);
            tileRes = tileResId;
        }
        if (tile == null) {
            paints[direction] = null;
            return;
        }
        if (paints[direction] == null) {
            paints[direction] = new Paint(Paint.FILTER_BITMAP_FLAG);
        }

        int[] colours;
        float[] stops;
        if (direction == FADE_DOWN) {
            colours = FADE_COLOURS;
            stops = FADE_STOPS;
        } else {
            colours = new int[FADE_COLOURS.length];
            stops = new float[FADE_STOPS.length];
            for (int i = 0; i < FADE_COLOURS.length; i++) {
                int mirrored = FADE_COLOURS.length - 1 - i;
                colours[i] = FADE_COLOURS[mirrored];
                stops[i] = 1f - FADE_STOPS[mirrored];
            }
        }

        // dst = the lattice, src = the fade; DST_IN keeps the lattice only where
        // the fade has alpha. Both children are of different types, which is what
        // the hardware pipeline requires of a ComposeShader before API 28.
        Shader lattice = new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Shader fade = new LinearGradient(0, 0, 0, height, colours, stops, Shader.TileMode.CLAMP);
        paints[direction].setShader(new ComposeShader(lattice, fade, PorterDuff.Mode.DST_IN));

        boolean darkPage = ColorUtils.calculateLuminance(pageColour) < 0.5f;
        paints[direction].setColorFilter(new PorterDuffColorFilter(
                darkPage ? INK_ON_DARK : INK_ON_LIGHT, PorterDuff.Mode.SRC_IN));
    }
}
