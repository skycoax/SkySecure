package uz.jac.secure.android;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * The verdict iconography, as geometry.
 *
 * Four glyphs — block, warning, check, shield — drawn from {@link Path} rather
 * than shipped as vector drawables. Two reasons, in order of weight:
 *
 * 1. The same glyph has to appear inside a Canvas-drawn message bubble, inside
 *    a Canvas-drawn chat-list row, and inside a normal View hierarchy on the
 *    interstitial. A {@code VectorDrawable} serves the third case well and the
 *    first two badly (inflate, cache, tint, setBounds per draw, on the
 *    scrolling path). A Path scales to any size and draws in one call.
 *
 * 2. It is the same shape in all three places, from one definition. A user who
 *    learns the octagon means "blocked" in the chat list must meet exactly that
 *    octagon in the bubble; a drifting icon set makes the vocabulary something
 *    to re-learn per screen.
 *
 * Every glyph is authored in a 24×24 box and mapped onto the requested bounds,
 * so callers pass the size they want and never a scale factor.
 */
public final class JacIcons {

    public enum Glyph {
        /** A crossed circle. Blocked — the file is in quarantine. */
        BLOCK,
        /** A triangle with a bang. Suspicious — worth reading, not blocked. */
        WARNING,
        /** A tick. Checked and a reputation source said clean. */
        CHECK,
        /** A shield. The interstitial, and the app's own identity. */
        SHIELD,
        /** A phone outline. Checked on this device only (secret chat). */
        DEVICE,
        /** A ring. Scan in flight; callers rotate it. */
        SPINNER,
        /**
         * A spiked cell. Malware, in the one place the user is looking.
         *
         * Deliberately not {@link #BLOCK} or {@link #WARNING}. Those are the
         * vocabulary of the app disagreeing with you — a barrier, a caution —
         * and they sit in the same visual family as every "are you sure?" the
         * user has already learned to tap through. This one names the thing
         * instead, and it is the only glyph in the set that does.
         */
        VIRUS,
    }

    private JacIcons() {
    }

    private static final float BOX = 24f;

    /**
     * Draw {@code glyph} centred in {@code bounds}.
     *
     * {@code paint} supplies colour and antialiasing; stroke width is set here,
     * proportionally, so a 14 dp icon and a 64 dp one look like the same icon
     * rather than the same icon with a heavier pen.
     */
    public static void draw(Canvas canvas, Glyph glyph, RectF bounds, Paint paint) {
        float size = Math.min(bounds.width(), bounds.height());
        float scale = size / BOX;
        float stroke = 2f * scale;

        int saved = canvas.save();
        canvas.translate(bounds.centerX() - size / 2f, bounds.centerY() - size / 2f);
        canvas.scale(scale, scale);

        Paint.Style previousStyle = paint.getStyle();
        float previousStroke = paint.getStrokeWidth();
        paint.setStrokeWidth(stroke / scale);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        switch (glyph) {
            case BLOCK:
                drawBlock(canvas, paint);
                break;
            case WARNING:
                drawWarning(canvas, paint);
                break;
            case CHECK:
                drawCheck(canvas, paint);
                break;
            case SHIELD:
                drawShield(canvas, paint);
                break;
            case DEVICE:
                drawDevice(canvas, paint);
                break;
            case SPINNER:
                drawSpinner(canvas, paint);
                break;
            case VIRUS:
                drawVirus(canvas, paint);
                break;
            default:
                break;
        }

        paint.setStyle(previousStyle);
        paint.setStrokeWidth(previousStroke);
        canvas.restoreToCount(saved);
    }

    private static void drawBlock(Canvas canvas, Paint paint) {
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(12f, 12f, 9f, paint);
        // The bar runs corner to corner of the inscribed square, not edge to
        // edge of the circle, so it stops short of the ring instead of
        // thickening it where the two meet.
        canvas.drawLine(5.6f, 5.6f, 18.4f, 18.4f, paint);
    }

    private static void drawWarning(Canvas canvas, Paint paint) {
        paint.setStyle(Paint.Style.STROKE);
        Path triangle = new Path();
        triangle.moveTo(12f, 3.2f);
        triangle.lineTo(22.2f, 20.4f);
        triangle.lineTo(1.8f, 20.4f);
        triangle.close();
        canvas.drawPath(triangle, paint);
        canvas.drawLine(12f, 9.6f, 12f, 14.6f, paint);
        // The dot is a filled cap rather than a stroked point: a zero-length
        // stroked line renders as nothing on some GPU pipelines.
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(12f, 17.4f, 1.05f, paint);
    }

    private static void drawCheck(Canvas canvas, Paint paint) {
        paint.setStyle(Paint.Style.STROKE);
        Path tick = new Path();
        tick.moveTo(4.5f, 12.6f);
        tick.lineTo(9.8f, 17.8f);
        tick.lineTo(19.6f, 6.6f);
        canvas.drawPath(tick, paint);
    }

    private static void drawShield(Canvas canvas, Paint paint) {
        paint.setStyle(Paint.Style.STROKE);
        Path shield = new Path();
        shield.moveTo(12f, 2.4f);
        shield.lineTo(20.4f, 5.8f);
        shield.lineTo(20.4f, 11.6f);
        // Two curves rather than a bezier pair per side: the tip has to be a
        // point, and a symmetric quad through the mid-flank gets there without
        // the waist a cubic tends to leave.
        shield.quadTo(20.4f, 18.2f, 12f, 21.6f);
        shield.quadTo(3.6f, 18.2f, 3.6f, 11.6f);
        shield.lineTo(3.6f, 5.8f);
        shield.close();
        canvas.drawPath(shield, paint);
        canvas.drawLine(12f, 8.4f, 12f, 13.2f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(12f, 16f, 1.05f, paint);
    }

    private static void drawDevice(Canvas canvas, Paint paint) {
        paint.setStyle(Paint.Style.STROKE);
        RectF body = new RectF(7f, 2.6f, 17f, 21.4f);
        canvas.drawRoundRect(body, 2.2f, 2.2f, paint);
        canvas.drawLine(10.4f, 18.6f, 13.6f, 18.6f, paint);
    }

    /**
     * A filled body with eight radial spikes and two dark cores.
     *
     * Filled rather than stroked, unlike every other glyph here. At the 24 dp
     * the message bubble draws it, an outlined cell of this shape collapses
     * into a grey smudge, and the one icon that has to read instantly at a
     * glance is the one that cannot afford to. The spikes are drawn as capped
     * lines from a radius inside the body, so they emerge from it rather than
     * touching it.
     */
    private static void drawVirus(Canvas canvas, Paint paint) {
        final float cx = 12f, cy = 12f, body = 6.4f;

        // The cores below are punched out with PorterDuff.CLEAR, which erases
        // whatever it lands on. On the bare canvas that is the message bubble
        // itself -- two holes straight through to the wallpaper. The layer is
        // what confines the erase to this glyph, so the holes show the bubble
        // instead of removing it. One saveLayer per icon, and only malicious
        // files draw one at all.
        int layer = canvas.saveLayer(0f, 0f, BOX, BOX, null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.9f);
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2 * i / 8f;
            float sin = (float) Math.sin(angle);
            float cos = (float) Math.cos(angle);
            canvas.drawLine(
                    cx + cos * (body - 0.6f), cy + sin * (body - 0.6f),
                    cx + cos * 10.2f, cy + sin * 10.2f, paint);
            // A knob on each spike. Without it the shape reads as a sun or a
            // gear; the bulb is what makes it a virus at thumbnail size.
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx + cos * 10.2f, cy + sin * 10.2f, 1.25f, paint);
            paint.setStyle(Paint.Style.STROKE);
        }

        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, body, paint);

        // The cores are punched out of the body, so they take the bubble
        // colour behind the icon rather than a colour of their own. Anything
        // else would need a second paint and would stop matching the tint the
        // caller asked for.
        paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        canvas.drawCircle(cx - 2.1f, cy - 1.4f, 1.5f, paint);
        canvas.drawCircle(cx + 1.9f, cy + 2.0f, 1.15f, paint);
        paint.setXfermode(null);

        canvas.restoreToCount(layer);
    }

    private static void drawSpinner(Canvas canvas, Paint paint) {
        paint.setStyle(Paint.Style.STROKE);
        // A 280° arc, not a full ring: the gap is what makes the rotation
        // visible. A full circle spinning looks like a static circle.
        RectF ring = new RectF(3.6f, 3.6f, 20.4f, 20.4f);
        canvas.drawArc(ring, -90f, 280f, false, paint);
    }
}
