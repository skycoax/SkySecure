package uz.jac.secure.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;

import java.io.File;

import org.telegram.messenger.R;

/**
 * The verdict, drawn inside the message bubble.
 *
 * <h3>Why this is not an overlay</h3>
 *
 * The verdict used to be a plaque floating over the file bubble. That failed
 * for three reasons, all of which this class exists to fix:
 *
 * <ul>
 *   <li>It covered the thing it was describing. To read the warning about a
 *       file you had to stop being able to see the file's name.</li>
 *   <li>An overlay reads as transient — the same visual grammar as a toast or a
 *       snackbar — so it invited dismissal. The verdict is not a notification
 *       about the message; it is part of what the message <em>is</em>, and it
 *       has to still be there tomorrow.</li>
 *   <li>It had nowhere to put a choice. A floating plaque with two buttons on
 *       it is a dialog in disguise, and the safe option ends up as a way to
 *       clear the plaque rather than a decision about the file.</li>
 * </ul>
 *
 * So the verdict is laid out in the bubble, below the file row and above the
 * timestamp, separated by a hairline: icon, status, one sentence of plain
 * language, and — only when the file is in quarantine — the two buttons.
 *
 * <h3>Why a renderer rather than a View</h3>
 *
 * {@code ChatMessageCell} is a single custom View that draws every part of a
 * message onto one Canvas. Adding a child View inside it is not a thing that
 * tree supports. So the host cell owns this object and forwards three calls:
 * {@link #measure} from {@code onMeasure}, {@link #draw} from {@code onDraw},
 * {@link #onTouchEvent} from its touch handling.
 *
 * <h3>Contract with the host cell</h3>
 *
 * All coordinates passed in are the block's top-left corner inside the cell.
 * The block never reads its position from anywhere else, so a cell that reuses
 * it for a different message at a different offset cannot draw stale geometry.
 */
public final class ScanVerdictBlock {

    /**
     * The host cell. Every method is called on the UI thread.
     *
     * {@code onKeepSafe} and {@code onOpenAnyway} are what the two buttons do;
     * neither performs the action itself, because releasing a file from
     * quarantine needs an Activity to host the second confirmation and this
     * class has only a Context.
     */
    public interface Listener {
        void onKeepSafe(File file);

        void onOpenAnyway(File file);

        /** Ask the host to invalidate; used by the scanning animation. */
        void onNeedsRedraw();
    }

    private static final int ICON_DP = 16;
    private static final int BUTTON_HEIGHT_DP = 34;
    private static final int BUTTON_RADIUS_DP = 8;
    private static final int SPINNER_PERIOD_MS = 900;
    private static final int PROGRESS_HEIGHT_DP = 3;
    /** Slower than the spinner: two things at the same tempo read as one. */
    private static final int PROGRESS_PERIOD_MS = 1400;

    private final Context context;
    private final Listener listener;

    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint buttonPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF iconBounds = new RectF();
    private final RectF safeButton = new RectF();
    private final RectF openButton = new RectF();
    private final RectF scratch = new RectF();
    private final RectF overlayBounds = new RectF();
    private final TextPaint overlayTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint overlayBodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private File file;
    private ScanUi.Presentation presentation;
    private boolean scanning;

    private StaticLayout titleLayout;
    private StaticLayout bodyLayout;
    private String safeLabel;
    private String openLabel;

    private int width;
    private int height;
    private int measuredForWidth = -1;

    /** 0 = none, 1 = "keep me safe", 2 = "open anyway". */
    private int pressedButton;

    public ScanVerdictBlock(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;

        titlePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titlePaint.setTextSize(JacTheme.sp(context, 14f));
        bodyPaint.setTextSize(JacTheme.sp(context, 13.5f));
        buttonPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        buttonPaint.setTextSize(JacTheme.sp(context, 13.5f));
        iconPaint.setStyle(Paint.Style.STROKE);
        overlayTitlePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        overlayTitlePaint.setTextSize(JacTheme.sp(context, 19f));
        overlayBodyPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        overlayBodyPaint.setTextSize(JacTheme.sp(context, 14f));

        safeLabel = JacStrings.get(context, R.string.jac_quarantine_keep_safe);
        openLabel = JacStrings.get(context, R.string.jac_quarantine_open_anyway);
    }

    /**
     * Point the block at a file and its state.
     *
     * Returns whether anything should be drawn at all. A file with no state —
     * a sticker, a thumbnail, an outgoing photo that was never scanned — must
     * add nothing to the bubble, not even blank space, or every message in the
     * chat grows a gap.
     */
    public boolean bind(File file, ScanStateStore.State state) {
        this.file = file;
        this.presentation = ScanUi.present(context, state);
        this.scanning = state != null && state.scanning;
        this.measuredForWidth = -1;
        this.pressedButton = 0;
        return presentation != null;
    }

    public boolean isVisible() {
        return presentation != null;
    }

    public int getHeight() {
        return presentation == null ? 0 : height;
    }

    /**
     * Lay out at the given width and return the height needed.
     *
     * Cheap to call repeatedly: a message list re-measures constantly, and
     * building two StaticLayouts per cell per pass would show up on the
     * scrolling path, so the result is cached until the width or the state
     * changes.
     */
    public int measure(int maxWidth) {
        if (presentation == null || maxWidth <= 0) {
            height = 0;
            return 0;
        }
        if (measuredForWidth == maxWidth) {
            return height;
        }
        measuredForWidth = maxWidth;
        width = maxWidth;

        int icon = JacTheme.dp(context, ICON_DP);
        int gutter = icon + JacTheme.dp(context, 8);
        int titleWidth = Math.max(1, maxWidth - gutter);

        titlePaint.setColor(presentation.colour);
        titleLayout = buildLayout(presentation.title, titlePaint, titleWidth);

        bodyPaint.setColor(JacTheme.textMuted(context));
        bodyLayout = presentation.body == null || presentation.body.isEmpty()
                ? null
                : buildLayout(presentation.body, bodyPaint, maxWidth);

        int y = JacTheme.dp(context, 10);              // gap under the hairline
        y += Math.max(icon, titleLayout.getHeight());
        if (bodyLayout != null) {
            y += JacTheme.dp(context, 5) + bodyLayout.getHeight();
        }
        if (presentation.actionable) {
            y += JacTheme.dp(context, 10) + JacTheme.dp(context, BUTTON_HEIGHT_DP);
        }
        y += JacTheme.dp(context, 8);                   // gap above the timestamp

        height = y;
        return height;
    }

    public void draw(Canvas canvas, float left, float top) {
        if (presentation == null || height == 0) {
            return;
        }

        int saved = canvas.save();
        canvas.translate(left, top);

        // Hairline, not a filled panel. The verdict belongs to this message, so
        // it shares the bubble's background; a second surface inside a bubble
        // reads as a quoted message and invites a tap that goes nowhere.
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(JacTheme.wash(JacTheme.textMuted(context), 22));
        canvas.drawRect(0, 0, width, Math.max(1, JacTheme.dp(context, 0.5f)), fillPaint);

        int icon = JacTheme.dp(context, ICON_DP);
        float y = JacTheme.dp(context, 10);

        iconBounds.set(0, y, icon, y + icon);
        iconPaint.setColor(presentation.colour);
        if (scanning) {
            // Rotation is derived from the clock rather than accumulated per
            // frame, so a cell recycled mid-scan picks up the animation at the
            // right angle instead of restarting it.
            float angle = (SystemClock.elapsedRealtime() % SPINNER_PERIOD_MS) * 360f / SPINNER_PERIOD_MS;
            int spin = canvas.save();
            canvas.rotate(angle, iconBounds.centerX(), iconBounds.centerY());
            JacIcons.draw(canvas, presentation.glyph, iconBounds, iconPaint);
            canvas.restoreToCount(spin);
            listener.onNeedsRedraw();
        } else {
            JacIcons.draw(canvas, presentation.glyph, iconBounds, iconPaint);
        }

        int gutter = icon + JacTheme.dp(context, 8);
        int titleSaved = canvas.save();
        // Optically centre the first line of the title against the icon rather
        // than aligning the boxes: the text box carries font ascent the glyph
        // box does not, and box alignment leaves the title visibly high.
        float titleOffset = y + (icon - titleLayout.getLineBottom(0) + titleLayout.getLineTop(0)) / 2f;
        canvas.translate(gutter, Math.max(y, titleOffset));
        titleLayout.draw(canvas);
        canvas.restoreToCount(titleSaved);

        y += Math.max(icon, titleLayout.getHeight());

        // No progress bar. It was a second indeterminate animation directly
        // under the first one, saying the same thing the spinning glyph
        // already says, and it made the block read as a download in progress
        // -- which is the one thing the user must not confuse a scan with.

        if (bodyLayout != null) {
            y += JacTheme.dp(context, 5);
            int bodySaved = canvas.save();
            canvas.translate(0, y);
            bodyLayout.draw(canvas);
            canvas.restoreToCount(bodySaved);
            y += bodyLayout.getHeight();
        }

        if (presentation.actionable) {
            y += JacTheme.dp(context, 10);
            layoutButtons(y);
            drawButton(canvas, safeButton, safeLabel, true, pressedButton == 1);
            drawButton(canvas, openButton, openLabel, false, pressedButton == 2);
        }

        canvas.restoreToCount(saved);
    }

    /**
     * Does this verdict take over the whole bubble?
     *
     * True only for MALICIOUS. The loud treatment is rationed on purpose: it
     * covers the sender's own text, which on a real attack is the lure — "send
     * this to your family" — and covering that is the point. But the same
     * treatment on a legitimate APK covers a message the user wanted to read,
     * and two of those teach them to look past red screens. The scale only
     * works if the top of it is rare.
     */
    public boolean isOverlay() {
        return presentation != null && presentation.overlay;
    }

    /**
     * The full-bubble danger screen.
     *
     * Drawn by the host cell AFTER everything else, over the bubble's own
     * bounds, so it covers the file row, the caption and the timestamp.
     *
     * The scrim is heavy (92%) rather than a light tint. A translucent warning
     * over readable text is a warning the eye reads past to get to the text —
     * and the text under this one is written by someone trying to get the file
     * opened. If it is worth covering at all it is worth covering properly.
     *
     * @return the rectangle the buttons occupy, so the host can hit-test it
     */
    public void drawOverlay(Canvas canvas, float left, float top, float right, float bottom) {
        if (presentation == null) {
            return;
        }
        overlayBounds.set(left, top, right, bottom);

        int saved = canvas.save();
        canvas.clipRect(overlayBounds);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(JacTheme.wash(JacTheme.screen(context), 92));
        canvas.drawRect(overlayBounds, fillPaint);

        float pad = JacTheme.dp(context, 16);
        float innerWidth = Math.max(1f, overlayBounds.width() - pad * 2);
        float cx = overlayBounds.centerX();

        // Laid out from the top rather than centred vertically: a bubble whose
        // caption is three lines long is much taller than one without, and a
        // centred block would float in the middle of an otherwise empty red
        // rectangle. Anchoring to the top keeps it next to the file it is about.
        float y = overlayBounds.top + pad;

        int glyph = JacTheme.dp(context, 44);
        iconBounds.set(cx - glyph / 2f, y, cx + glyph / 2f, y + glyph);
        iconPaint.setColor(JacTheme.danger(context));
        iconPaint.setStyle(Paint.Style.STROKE);
        JacIcons.draw(canvas, JacIcons.Glyph.SHIELD, iconBounds, iconPaint);
        y += glyph + JacTheme.dp(context, 12);

        overlayTitlePaint.setColor(JacTheme.danger(context));
        StaticLayout title = buildLayout(presentation.title, overlayTitlePaint, (int) innerWidth,
                Layout.Alignment.ALIGN_CENTER);
        int titleSaved = canvas.save();
        canvas.translate(overlayBounds.left + pad, y);
        title.draw(canvas);
        canvas.restoreToCount(titleSaved);
        y += title.getHeight() + JacTheme.dp(context, 10);

        if (presentation.body != null && !presentation.body.isEmpty()) {
            overlayBodyPaint.setColor(JacTheme.danger(context));
            StaticLayout body = buildLayout(presentation.body, overlayBodyPaint, (int) innerWidth,
                    Layout.Alignment.ALIGN_CENTER);
            int bodySaved = canvas.save();
            canvas.translate(overlayBounds.left + pad, y);
            body.draw(canvas);
            canvas.restoreToCount(bodySaved);
            y += body.getHeight() + JacTheme.dp(context, 14);
        }

        if (presentation.actionable) {
            // Stacked, not side by side. The overlay is as wide as the bubble
            // and the safe action should be the full width of it - a pair of
            // half-width buttons reads as a choice between equals, which is
            // exactly what this screen is not.
            int h = JacTheme.dp(context, 44);
            float radius = JacTheme.dp(context, 10);

            safeButton.set(overlayBounds.left + pad, y, overlayBounds.right - pad, y + h);
            fillPaint.setColor(JacTheme.primary(context));
            canvas.drawRoundRect(safeButton, radius, radius, fillPaint);
            if (pressedButton == 1) {
                fillPaint.setColor(JacTheme.wash(0xFF000000, 18));
                canvas.drawRoundRect(safeButton, radius, radius, fillPaint);
            }
            drawCentredLabel(canvas, safeButton, safeLabel, JacTheme.onPrimary(context));
            y += h + JacTheme.dp(context, 8);

            openButton.set(overlayBounds.left + pad, y, overlayBounds.right - pad, y + h);
            if (pressedButton == 2) {
                fillPaint.setColor(JacTheme.wash(JacTheme.danger(context), 14));
                canvas.drawRoundRect(openButton, radius, radius, fillPaint);
            }
            drawCentredLabel(canvas, openButton, openLabel, JacTheme.wash(JacTheme.danger(context), 75));
        } else {
            safeButton.setEmpty();
            openButton.setEmpty();
        }

        canvas.restoreToCount(saved);
    }

    private void drawCentredLabel(Canvas canvas, RectF bounds, String label, int colour) {
        buttonPaint.setColor(colour);
        float available = Math.max(0f, bounds.width() - JacTheme.dp(context, 12));
        String text = TextUtils.ellipsize(label, buttonPaint, available, TextUtils.TruncateAt.END).toString();
        Paint.FontMetrics metrics = buttonPaint.getFontMetrics();
        canvas.drawText(text,
                bounds.centerX() - buttonPaint.measureText(text) / 2f,
                bounds.centerY() - (metrics.ascent + metrics.descent) / 2f,
                buttonPaint);
    }

    /**
     * Touch handling while the overlay is up.
     *
     * Everything inside the overlay is consumed, buttons or not: the whole
     * point is that no tap in that rectangle reaches the file underneath.
     */
    public boolean onOverlayTouchEvent(MotionEvent event) {
        if (presentation == null || !presentation.overlay) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        if (!overlayBounds.contains(x, y)) {
            return false;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                pressedButton = safeButton.contains(x, y) ? 1 : openButton.contains(x, y) ? 2 : 0;
                listener.onNeedsRedraw();
                return true;
            case MotionEvent.ACTION_UP:
                int released = pressedButton;
                pressedButton = 0;
                listener.onNeedsRedraw();
                if (released == 1 && safeButton.contains(x, y)) {
                    listener.onKeepSafe(file);
                } else if (released == 2 && openButton.contains(x, y)) {
                    listener.onOpenAnyway(file);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedButton = 0;
                listener.onNeedsRedraw();
                return true;
            default:
                return true;
        }
    }

    /**
     * The scanning progress bar, under the status line.
     *
     * <h3>Why indeterminate, and why it moves</h3>
     *
     * A scan has no honest percentage. Hashing is proportional to file size,
     * the structural checks are not, and a network lookup either answers in
     * 200 ms or times out — so a filling bar would be a lie that runs backwards
     * on the interesting files.
     *
     * A moving indeterminate bar is still worth its space. Without it the only
     * signal is a spinning 16 dp glyph, and the honest question a user asks of
     * a file that will not open is "is this app broken?". This says: something
     * is happening, wait. It is also the visual grammar the file row above it
     * already uses for downloading, which is the right association — the file
     * is not ready yet.
     *
     * The sweep is derived from the clock, not accumulated per frame, so a cell
     * recycled mid-scan joins the animation where it should be rather than
     * restarting it.
     */
    private void drawProgress(Canvas canvas, float y) {
        int height = JacTheme.dp(context, PROGRESS_HEIGHT_DP);
        float radius = height / 2f;

        scratch.set(0, y, width, y + height);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(JacTheme.wash(JacTheme.textMuted(context), 20));
        canvas.drawRoundRect(scratch, radius, radius, fillPaint);

        // A segment a third of the width, sweeping left to right and back. The
        // reversal matters: a segment that wraps around the end reads as a
        // stutter at the seam, and a stutter reads as a hang.
        float phase = (SystemClock.elapsedRealtime() % PROGRESS_PERIOD_MS) / (float) PROGRESS_PERIOD_MS;
        float travel = phase < 0.5f ? phase * 2f : (1f - phase) * 2f;
        float segment = width / 3f;
        float start = travel * (width - segment);

        int clipped = canvas.save();
        canvas.clipRect(scratch);
        scratch.set(start, y, start + segment, y + height);
        fillPaint.setColor(presentation.colour);
        canvas.drawRoundRect(scratch, radius, radius, fillPaint);
        canvas.restoreToCount(clipped);
    }

    /**
     * The safe button is first, filled, and sized to its text; the dangerous
     * one takes the remainder.
     *
     * Not a 50/50 split. Equal buttons are a coin toss, and the whole point of
     * this row is that one of the two options is the one we are recommending.
     */
    private void layoutButtons(float y) {
        int h = JacTheme.dp(context, BUTTON_HEIGHT_DP);
        int gap = JacTheme.dp(context, 8);
        int padding = JacTheme.dp(context, 14);

        float safeWidth = buttonPaint.measureText(safeLabel) + padding * 2f;
        float available = width - gap;
        // Never let the recommended button eat the row: if the label is long,
        // the pair falls back to an even split rather than pushing "Open
        // anyway" down to an unreadable stub.
        safeWidth = Math.min(safeWidth, available * 0.62f);

        safeButton.set(0, y, safeWidth, y + h);
        openButton.set(safeWidth + gap, y, width, y + h);
    }

    private void drawButton(Canvas canvas, RectF bounds, String label, boolean primary, boolean pressed) {
        float radius = JacTheme.dp(context, BUTTON_RADIUS_DP);

        fillPaint.setStyle(Paint.Style.FILL);
        if (primary) {
            fillPaint.setColor(JacTheme.primary(context));
        } else {
            fillPaint.setColor(JacTheme.wash(JacTheme.textMuted(context), 20));
        }
        canvas.drawRoundRect(bounds, radius, radius, fillPaint);

        if (pressed) {
            fillPaint.setColor(JacTheme.wash(0xFF000000, 18));
            canvas.drawRoundRect(bounds, radius, radius, fillPaint);
        }

        buttonPaint.setColor(primary ? JacTheme.onPrimary(context) : JacTheme.text(context));
        // max(0, …): a very narrow bubble can leave less room than the padding,
        // and ellipsize with a negative width throws rather than truncating —
        // inside onDraw, on the scrolling path.
        float available = Math.max(0f, bounds.width() - JacTheme.dp(context, 12));
        String text = TextUtils.ellipsize(label, buttonPaint, available, TextUtils.TruncateAt.END).toString();
        float textWidth = buttonPaint.measureText(text);
        Paint.FontMetrics metrics = buttonPaint.getFontMetrics();
        float baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(text, bounds.centerX() - textWidth / 2f, baseline, buttonPaint);
    }

    /**
     * Forward the cell's touch events here before it handles them itself.
     *
     * Returns true when the event was consumed, which the host must honour —
     * otherwise a tap on "Keep me safe" also opens the message, which is
     * exactly the file we were being asked not to open.
     */
    public boolean onTouchEvent(MotionEvent event, float left, float top) {
        if (presentation == null || !presentation.actionable) {
            return false;
        }
        float x = event.getX() - left;
        float y = event.getY() - top;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                pressedButton = hit(x, y);
                if (pressedButton != 0) {
                    listener.onNeedsRedraw();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                // A finger that slides off the button cancels it, the way every
                // other button on the platform behaves.
                if (pressedButton != 0 && hit(x, y) != pressedButton) {
                    pressedButton = 0;
                    listener.onNeedsRedraw();
                }
                return pressedButton != 0;

            case MotionEvent.ACTION_UP:
                int released = pressedButton;
                pressedButton = 0;
                if (released == 0) {
                    return false;
                }
                listener.onNeedsRedraw();
                if (released == hit(x, y)) {
                    if (released == 1) {
                        listener.onKeepSafe(file);
                    } else {
                        listener.onOpenAnyway(file);
                    }
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
            default:
                if (pressedButton != 0) {
                    pressedButton = 0;
                    listener.onNeedsRedraw();
                }
                return false;
        }
    }

    private int hit(float x, float y) {
        if (safeButton.contains(x, y)) {
            return 1;
        }
        if (openButton.contains(x, y)) {
            return 2;
        }
        return 0;
    }

    /**
     * True when the touch landed anywhere in the block.
     *
     * The host uses this to suppress its own "open the file" handler across the
     * whole verdict area, not just the buttons — text explaining why a file is
     * dangerous must not itself be a tap target that opens it.
     */
    public boolean containsTouch(MotionEvent event, float left, float top) {
        if (presentation == null) {
            return false;
        }
        scratch.set(left, top, left + width, top + height);
        return scratch.contains(event.getX(), event.getY());
    }

    private StaticLayout buildLayout(String text, TextPaint paint, int layoutWidth) {
        return buildLayout(text, paint, layoutWidth, Layout.Alignment.ALIGN_NORMAL);
    }

    @SuppressWarnings("deprecation")
    private StaticLayout buildLayout(String text, TextPaint paint, int layoutWidth, Layout.Alignment alignment) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, layoutWidth)
                    .setAlignment(alignment)
                    .setIncludePad(false)
                    .build();
        }
        return new StaticLayout(text, paint, layoutWidth, alignment, 1f, 0f, false);
    }
}
