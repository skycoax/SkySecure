package uz.jac.secure.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import org.telegram.messenger.R;

/**
 * The Humo — Humogram's mark — sized for a specific slot.
 *
 * <h3>Why a helper rather than another drawable resource</h3>
 *
 * The mark is needed at several sizes, and a PNG has exactly one intrinsic size
 * per density bucket. The place that wants it first is a chat-list title, and
 * the host there is {@code SimpleTextView}, which measures and lays out a left
 * drawable off {@code getIntrinsicWidth()} / {@code getIntrinsicHeight()} and
 * ignores whatever bounds a caller sets. So a drawable handed to it has to
 * already claim the right intrinsic size; scaling at the call site is not an
 * option. Shipping one more PNG per slot would mean five more files for every
 * size the design ever asks for.
 *
 * <h3>Why the artwork is white</h3>
 *
 * The {@code humo_bird} artwork is a white silhouette carrying only an alpha
 * channel, which is the same contract {@code humogram_wordmark} has always
 * used. It matters because the two tinting paths that reach this mark
 * both multiply: {@code SimpleTextView.setSideDrawablesColor} routes through
 * {@code Theme.setDrawableColor}, whose fallback branch is a
 * {@code PorterDuffColorFilter(colour, MULTIPLY)}, and white multiplied by a
 * colour is that colour exactly. Tint a blue bird the same way and you get the
 * product of two blues, which is neither of them.
 *
 * <p>The corollary is that a caller who forgets to tint gets a white bird on a
 * white bar — invisible rather than merely wrong-coloured. Every call site must
 * set a colour.
 */
public final class HumoMark {

    private HumoMark() {
    }

    /**
     * The mark at {@code heightDp} tall, ready to hand to a text view.
     *
     * <p>Returns {@code null} if the asset cannot be decoded, because the two
     * things this is used for — a title and a header — are both places where a
     * missing mark should cost the brand and nothing else. Callers null-check
     * and carry on rather than propagating.
     *
     * @param heightDp the height the mark should occupy; width follows the
     *                 artwork's own aspect, which is slightly wider than tall.
     */
    public static Drawable forHeight(Context context, float heightDp) {
        if (context == null || heightDp <= 0) {
            return null;
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int height = Math.max(1, Math.round(heightDp * metrics.density));

        Bitmap source = BitmapFactory.decodeResource(context.getResources(), R.drawable.humo_bird);
        if (source == null) {
            return null;
        }
        int width = Math.max(1, Math.round(height * (float) source.getWidth() / source.getHeight()));
        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);

        // Stamp the display density onto the scaled bitmap. BitmapDrawable
        // reports getIntrinsicWidth() as getScaledWidth(targetDensity), so a
        // bitmap still carrying the density of the drawable-*dpi bucket it was
        // decoded from would be re-scaled a second time by the host — the mark
        // would come out right on the bucket that happens to match the device
        // and wrong everywhere else.
        scaled.setDensity(metrics.densityDpi);

        BitmapDrawable drawable = new BitmapDrawable(context.getResources(), scaled);
        drawable.setTargetDensity(metrics);
        return drawable;
    }
}
