package uz.jac.secure.android;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;

/**
 * The dialogs that stand between a quarantined file and being opened.
 *
 * <h3>Built from Telegram's own dialog</h3>
 *
 * These used to be a hand-rolled {@code android.app.Dialog} with its own card,
 * corner radius, button stack and checkbox. Per the house rule they are now
 * upstream {@link AlertDialog}s: the same component every confirmation in the
 * app uses, so they inherit its typography, its ripple, its theming and its
 * button behaviour, and they read as part of the app rather than a thing
 * pasted over it. The only fork-drawn pixels left are the verdict glyph in the
 * top band — {@link JacIcons} geometry on the fixed verdict red, which is
 * deliberately outside the theme engine so no downloaded theme can dress a
 * warning up as something friendly.
 *
 * <h3>Why two steps, and why they do not look alike</h3>
 *
 * One confirmation does not work. Dismissing a dialog is a reflex — same
 * position, same motion, several times a day — and the tap lands before the
 * text is read. A second dialog only helps if it breaks that reflex:
 *
 * <ul>
 *   <li><b>Different words.</b> Step one describes the file. Step two describes
 *       what happens to the person: someone reads their SMS codes and takes
 *       money out of their account.</li>
 *   <li><b>Different gesture.</b> Step two will not proceed on a tap at all.
 *       The dangerous button is inert until the checkbox is ticked.</li>
 *   <li><b>The numbering is honest.</b> "Step 1 of 2" says, at the moment of
 *       deciding, that this is not the last screen — and promises there is no
 *       third one.</li>
 * </ul>
 *
 * <h3>Contract</h3>
 *
 * {@code onReleaseConfirmed} runs only after both steps and the checkbox. The
 * caller releases the file via {@link ScanGate#releaseAfterOverride}, which
 * independently re-checks that both confirmations were given — this class is
 * the interface, not the enforcement.
 */
public final class QuarantineDialogs {

    /** Seconds both buttons stay inert on the virus warning. */
    private static final int COUNTDOWN_SECONDS = 3;

    public interface ReleaseListener {
        void onReleaseConfirmed(File file);
    }

    private QuarantineDialogs() {
    }

    /**
     * Step 1 of 2 — what the file is.
     *
     * @param explanation the localised, one-sentence reason from {@link ScanUi}
     */
    public static void showBlocked(Activity activity, File file, String explanation, ReleaseListener listener) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTopImage(new GlyphDrawable(JacIcons.Glyph.BLOCK), JacTheme.danger(activity))
                .setTitle(JacStrings.get(activity, R.string.jac_quarantine_title))
                .setSubtitle(JacStrings.get(activity, R.string.jac_quarantine_step, 1, 2))
                .setMessage(JacStrings.get(activity, R.string.jac_quarantine_body,
                        explanation != null ? explanation : ""))
                .setPositiveButton(JacStrings.get(activity, R.string.jac_quarantine_keep_safe), null)
                .setNegativeButton(JacStrings.get(activity, R.string.jac_quarantine_open_anyway),
                        (d, which) -> showConfirm(activity, file, listener))
                .create();
        // The siren belongs to every screen that stands in front of a
        // dangerous file, not only the tap-interception one — this path used
        // to be the silent way in.
        JacAlarm.sound(activity);
        dialog.show();
        // The way through is legal but must not be the loud thing on the
        // screen: the safe button keeps the accent, the dangerous one goes red
        // — the same red every destructive confirmation in the app wears.
        tint(dialog, DialogInterface.BUTTON_NEGATIVE, Theme.getColor(Theme.key_text_RedBold));
    }

    /**
     * What the user gets instead of the file opening.
     *
     * <h3>Why this is not step one of the pair above</h3>
     *
     * The pair above stands between the user and a file they already decided to
     * open. This one interrupts an ordinary tap on an ordinary-looking file
     * row: the person behind it is not making a decision yet, they are opening
     * an attachment, which is a reflex.
     *
     * <h3>The three seconds</h3>
     *
     * Both buttons are inert while the countdown runs, so a tap already in
     * flight — the second half of the double-tap that opened this dialog —
     * cannot land on a button. The counter runs on the SAFE button: it is the
     * one the user should end up pressing, so it is the one that explains the
     * wait; a countdown on "install anyway" would be a three-second trailer
     * for the escape hatch.
     *
     * @param title verdict-specific title, or null for the generic one. The
     *              words come from the verdict, not from this screen: a
     *              SUSPICIOUS file must not be called a virus (that claim,
     *              made about a clean third-party app, is both untrue and a
     *              Play Misrepresentation problem).
     */
    public static void showVirusWarning(Activity activity, File file, String title, String body,
                                        ReleaseListener listener) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTopImage(new GlyphDrawable(JacIcons.Glyph.VIRUS), JacTheme.danger(activity))
                .setTitle(title != null ? title : JacStrings.get(activity, R.string.jac_virus_title))
                .setMessage(body != null ? body : JacStrings.get(activity, R.string.jac_virus_body))
                .setPositiveButton(JacStrings.get(activity, R.string.jac_virus_close), null)
                .setNegativeButton(JacStrings.get(activity, R.string.jac_virus_install_anyway),
                        (d, which) -> showConfirm(activity, file, listener))
                .create();
        // The siren, once, as the dialog lands; the countdown's soft ticks
        // continue underneath it. Sound is the channel that still works when
        // the tap is already in flight and the eyes have moved on.
        JacAlarm.sound(activity);
        dialog.show();
        // The escape hatch is allowed but not offered: muted grey, not red —
        // red is a warning colour, and putting it on the way out makes the way
        // out the loudest thing on the screen.
        tint(dialog, DialogInterface.BUTTON_NEGATIVE, Theme.getColor(Theme.key_dialogTextGray2));
        startCountdown(activity, dialog);
    }

    /**
     * Step 2 of 2 — what happens to you.
     *
     * Not public. There is no way to reach this screen except through step one,
     * because a caller that could open it directly would be a way to turn the
     * two-step gate into a one-step one.
     */
    private static void showConfirm(Activity activity, File file, ReleaseListener listener) {
        // The acknowledgement, as Telegram's own dialog checkbox — the same
        // cell every "don't ask again" in the app uses, configured the way
        // AlertsCreator configures it.
        CheckBoxCell acknowledge = new CheckBoxCell(activity, 1);
        acknowledge.setMultiline(true);
        acknowledge.getTextView().getLayoutParams().width = LayoutHelper.MATCH_PARENT;
        acknowledge.getTextView().setSingleLine(false);
        acknowledge.getTextView().setMaxLines(3);
        acknowledge.getTextView().setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        acknowledge.setText(JacStrings.get(activity, R.string.jac_quarantine_confirm_checkbox), "", false, false);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTopImage(new GlyphDrawable(JacIcons.Glyph.WARNING), JacTheme.danger(activity))
                .setTitle(JacStrings.get(activity, R.string.jac_quarantine_confirm_title))
                .setSubtitle(JacStrings.get(activity, R.string.jac_quarantine_step, 2, 2))
                .setMessage(JacStrings.get(activity, R.string.jac_quarantine_confirm_body))
                .setView(acknowledge)
                .setPositiveButton(JacStrings.get(activity, R.string.jac_quarantine_confirm_action),
                        (d, which) -> listener.onReleaseConfirmed(file))
                .setNegativeButton(JacStrings.get(activity, R.string.jac_quarantine_confirm_cancel), null)
                .create();
        dialog.show();

        // Disabled until acknowledged, and visibly so. A button that looks
        // ready and then does nothing reads as a bug, and the user's next move
        // is to tap it again rather than to read the line above it.
        final View danger = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        tint(dialog, DialogInterface.BUTTON_POSITIVE, Theme.getColor(Theme.key_text_RedBold));
        setEnabled(danger, false);
        acknowledge.setOnClickListener(v -> {
            acknowledge.setChecked(!acknowledge.isChecked(), true);
            setEnabled(danger, acknowledge.isChecked());
        });
    }

    private static void tint(AlertDialog dialog, int button, int color) {
        View view = dialog.getButton(button);
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(color);
        }
    }

    private static void setEnabled(View button, boolean enabled) {
        if (button != null) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.4f);
        }
    }

    /**
     * Hold both buttons inert for {@link #COUNTDOWN_SECONDS}, counting down on
     * the safe one, with a soft tick each second and a different tone when the
     * buttons go live — without it the silence is ambiguous: "still waiting"
     * and "you may press now" look the same from across a room.
     */
    private static void startCountdown(Activity activity, AlertDialog dialog) {
        final View close = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        final View proceed = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (!(close instanceof TextView)) {
            return; // no buttons, nothing to hold
        }
        final TextView safe = (TextView) close;
        final String label = safe.getText().toString();
        final int[] remaining = {COUNTDOWN_SECONDS};

        setEnabled(close, false);
        setEnabled(proceed, false);
        safe.setText(label + "  " + remaining[0]);

        final Alarm alarm = new Alarm(activity);
        // Released on dismissal however the dialog goes away — the button, the
        // caller, or the activity dying under it. A ToneGenerator holds an
        // AudioTrack, and one leaked per warning would eventually take the
        // device's audio out.
        dialog.setOnDismissListener(d -> alarm.release());

        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            // The dialog can be gone: dismissed by the caller, or the activity
            // torn down under it. Touching the views then is a leak at best,
            // so the countdown checks before every step and simply stops.
            if (!dialog.isShowing()) {
                alarm.release();
                return;
            }
            remaining[0]--;
            if (remaining[0] > 0) {
                safe.setText(label + "  " + remaining[0]);
                alarm.beep();
                safe.postDelayed(tick[0], 1000L);
            } else {
                safe.setText(label);
                setEnabled(close, true);
                setEnabled(proceed, true);
                alarm.finish();
                // The generator is only needed for the countdown. Release it
                // now, just after the final tone — the dismiss listener frees
                // it on an early exit, but on Android a Dialog is not
                // auto-dismissed when its Activity is destroyed, so waiting for
                // dismissal alone can leak the AudioTrack indefinitely. Freeing
                // it here caps the hold at the three-second countdown.
                safe.postDelayed(alarm::release, 300L);
            }
        };
        safe.postDelayed(tick[0], 1000L);
    }

    /**
     * The verdict glyph for the dialog's top band: {@link JacIcons} geometry,
     * white, on the fixed verdict red the builder paints behind it. Sized like
     * upstream's own top-image drawables so {@link AlertDialog} centres it in
     * the band without special-casing.
     */
    private static final class GlyphDrawable extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final JacIcons.Glyph glyph;

        GlyphDrawable(JacIcons.Glyph glyph) {
            this.glyph = glyph;
            paint.setColor(0xFFFFFFFF);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            box.set(getBounds());
            JacIcons.draw(canvas, glyph, box, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return AndroidUtilities.dp(52);
        }

        @Override
        public int getIntrinsicHeight() {
            return AndroidUtilities.dp(52);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * The warning tone that runs under the countdown.
     *
     * <h3>Why a tone and not a bundled sound</h3>
     *
     * {@link ToneGenerator} is in the platform, so this adds nothing to the
     * APK and needs no permission. It also cannot fail into silence the way a
     * missing asset would.
     *
     * <h3>Why the alarm stream</h3>
     *
     * The notification stream is where a phone in a meeting is already muted,
     * and this is the one sound in the app that has to arrive. The alarm stream
     * is the loudest honest choice: it is what the platform reserves for
     * "something needs you now".
     *
     * <h3>What it will not do</h3>
     *
     * Nothing at all in silent mode. A phone set to silent is a deliberate
     * instruction from its owner, and an app that overrides it teaches people
     * to distrust the app rather than the file. The countdown, the glyph and
     * the text all still work without a sound.
     *
     * <p>Every method is safe to call on a broken or absent generator: audio
     * routing is one of the flakiest surfaces on Android, and a warning dialog
     * that crashes instead of warning would be the worst possible trade.
     */
    private static final class Alarm {

        private ToneGenerator generator;

        Alarm(Activity activity) {
            try {
                AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                if (audio != null && audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) {
                    return;
                }
                generator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            } catch (Throwable ignored) {
                generator = null;
            }
        }

        /** One second of the countdown. */
        void beep() {
            play(ToneGenerator.TONE_CDMA_ABBR_ALERT, 350);
        }

        /** The countdown is over and the buttons are live. */
        void finish() {
            play(ToneGenerator.TONE_PROP_ACK, 200);
        }

        void release() {
            ToneGenerator local = generator;
            generator = null;
            if (local != null) {
                try {
                    local.release();
                } catch (Throwable ignored) {
                }
            }
        }

        private void play(int tone, int durationMs) {
            ToneGenerator local = generator;
            if (local == null) {
                return;
            }
            try {
                local.startTone(tone, durationMs);
            } catch (Throwable ignored) {
            }
        }
    }
}
