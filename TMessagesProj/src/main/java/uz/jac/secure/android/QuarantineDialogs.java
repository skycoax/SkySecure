package uz.jac.secure.android;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

import org.telegram.messenger.R;

/**
 * The two dialogs that stand between a quarantined file and being opened.
 *
 * <h3>Why two, and why they do not look alike</h3>
 *
 * One confirmation does not work. Dismissing a dialog is a reflex — same
 * position, same motion, several times a day — and the tap lands before the
 * text is read. A second dialog only helps if it breaks that reflex, so these
 * two are built to be different objects rather than the same object twice:
 *
 * <ul>
 *   <li><b>Different words.</b> Step one describes the file. Step two describes
 *       what happens to the person: someone reads their SMS codes and takes
 *       money out of their account. Neither is a paraphrase of the other.</li>
 *   <li><b>Different gesture.</b> Step two will not proceed on a tap at all.
 *       The dangerous button is inert until the checkbox is ticked, which no
 *       amount of tapping in the remembered place will do.</li>
 *   <li><b>Different button positions.</b> The safe action is the filled,
 *       primary one on both screens; the dangerous one moves and changes
 *       shape.</li>
 * </ul>
 *
 * <h3>Why the steps are numbered</h3>
 *
 * "Step 1 of 2" tells the user, at the moment they are deciding, that this is
 * not the last thing between them and the file. Without it, the second dialog
 * reads as the app having failed to register the first tap — and the correct
 * response to a misfiring dialog is to tap harder, which is exactly the
 * behaviour the second step exists to prevent. The count is also honest: it
 * promises there is no third one.
 *
 * <h3>Contract</h3>
 *
 * {@code onRelease} runs only after both steps and the checkbox. It is the
 * caller's job to actually release the file, via
 * {@link ScanGate#releaseAfterOverride}, which independently re-checks that
 * both confirmations were given — this class is the interface, not the
 * enforcement.
 */
public final class QuarantineDialogs {

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
        Builder builder = new Builder(activity, 1);
        builder.setGlyph(JacIcons.Glyph.BLOCK, JacTheme.danger(activity));
        builder.setTitle(JacStrings.get(activity, R.string.jac_quarantine_title));
        builder.setBody(JacStrings.get(activity, R.string.jac_quarantine_body,
                explanation != null ? explanation : ""));

        Dialog dialog = builder.create();
        builder.addPrimaryAction(JacStrings.get(activity, R.string.jac_quarantine_keep_safe), v -> dialog.dismiss());
        builder.addDangerAction(JacStrings.get(activity, R.string.jac_quarantine_open_anyway), v -> {
            dialog.dismiss();
            showConfirm(activity, file, listener);
        });
        dialog.show();
    }

    /**
     * What the user gets instead of the file opening.
     *
     * <h3>Why this is not step one of the pair above</h3>
     *
     * Those two stand between the user and a file they have already decided to
     * open — they were reached by pressing "Open anyway" on the verdict block,
     * which is itself a deliberate act. This one interrupts an ordinary tap on
     * an ordinary-looking file row. The person on the other side of it is not
     * making a decision yet; they are opening an attachment, which is a reflex.
     *
     * So it says one thing, in the plainest words available: this is a virus,
     * and here is what it does to you. Not which malware family, not what the
     * scanner matched on — none of that changes the decision, and every clause
     * of it is a clause the user skims.
     *
     * <h3>The three seconds</h3>
     *
     * Both buttons are inert while the countdown runs. The point is not to make
     * the user wait; it is that a tap already in flight — the second half of the
     * double-tap that opened this dialog — must not land on a button. Three
     * seconds is long enough to break the motion and short enough not to be
     * read as the app hanging, which is why the counter is visible: a dead
     * button with no explanation is a bug, a dead button counting down is a
     * rule.
     *
     * <p>Afterwards the two actions are deliberately unequal. "Close" is the
     * filled, full-width, primary button. "Install anyway" is grey text — legal
     * to press, and it looks like what it is.
     */
    public static void showVirusWarning(Activity activity, File file, String title, String body,
                                        ReleaseListener listener) {
        Builder builder = new Builder(activity);
        builder.setGlyph(JacIcons.Glyph.VIRUS, JacTheme.danger(activity));
        // The words come from the verdict, not from this screen.
        //
        // It used to say "This is a virus" unconditionally. That dialog is
        // reached from any red verdict, and a SUSPICIOUS one covers an ordinary
        // sideloaded APK with broad permissions -- so the app was asserting
        // infection about files it had no evidence against. Under Play's
        // Misrepresentation policy that is a false claim about a third party's
        // software, and it is also just untrue.
        builder.setTitle(title != null ? title : JacStrings.get(activity, R.string.jac_virus_title));
        builder.setBody(body != null ? body : JacStrings.get(activity, R.string.jac_virus_body));

        Dialog dialog = builder.create();
        TextView close = builder.addPrimaryAction(
                JacStrings.get(activity, R.string.jac_virus_close), v -> dialog.dismiss());
        TextView proceed = builder.addMutedAction(
                JacStrings.get(activity, R.string.jac_virus_install_anyway), v -> {
                    dialog.dismiss();
                    // Straight into step 2 of the pair below rather than
                    // releasing here. This dialog delays a reflex; that one
                    // takes a decision, and the file does not move without it.
                    showConfirm(activity, file, listener);
                });

        builder.startCountdown(dialog, close, proceed);
        dialog.show();
    }

    /**
     * Step 2 of 2 — what happens to you.
     *
     * Not public. There is no way to reach this screen except through step one,
     * because a caller that could open it directly would be a way to turn the
     * two-step gate into a one-step one.
     */
    private static void showConfirm(Activity activity, File file, ReleaseListener listener) {
        Builder builder = new Builder(activity, 2);
        builder.setGlyph(JacIcons.Glyph.WARNING, JacTheme.danger(activity));
        builder.setTitle(JacStrings.get(activity, R.string.jac_quarantine_confirm_title));
        builder.setBody(JacStrings.get(activity, R.string.jac_quarantine_confirm_body));

        CheckBox acknowledge = builder.addCheckbox(
                JacStrings.get(activity, R.string.jac_quarantine_confirm_checkbox));

        Dialog dialog = builder.create();
        builder.addPrimaryAction(JacStrings.get(activity, R.string.jac_quarantine_confirm_cancel), v -> dialog.dismiss());

        TextView danger = builder.addDangerAction(
                JacStrings.get(activity, R.string.jac_quarantine_confirm_action), v -> {
                    if (!acknowledge.isChecked()) {
                        return;
                    }
                    dialog.dismiss();
                    listener.onReleaseConfirmed(file);
                });

        // Disabled until acknowledged, and visibly so. A button that looks
        // ready and then does nothing reads as a bug, and the user's next move
        // is to tap it again rather than to read the line above it.
        setDangerEnabled(activity, danger, false);
        acknowledge.setOnCheckedChangeListener((view, checked) -> setDangerEnabled(activity, danger, checked));

        dialog.show();
    }

    private static void setDangerEnabled(Activity activity, TextView button, boolean enabled) {
        button.setEnabled(enabled);
        int colour = JacTheme.danger(activity);
        button.setTextColor(enabled ? colour : JacTheme.wash(colour, 40));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.TRANSPARENT);
        background.setCornerRadius(JacTheme.dp(activity, 12));
        background.setStroke(JacTheme.dp(activity, 1.5f), enabled ? colour : JacTheme.wash(colour, 30));
        button.setBackground(background);
    }

    /**
     * Shared chrome for both steps: a card, an overline counter, a glyph, a
     * title, a body, and a vertical button stack.
     *
     * Built in code rather than inflated so the two steps cannot drift apart —
     * the counter, the card radius and the button order come from one place,
     * and adding a step means changing one number.
     */
    private static final class Builder {

        private static final int TOTAL_STEPS = 2;

        /** Seconds both buttons stay inert on the virus warning. */
        private static final int COUNTDOWN_SECONDS = 3;

        private final Activity activity;
        private final LinearLayout card;
        private final LinearLayout actions;
        private Dialog dialog;

        /** The virus warning: one screen, so no "step N of M" overline. */
        Builder(Activity activity) {
            this(activity, 0);
        }

        Builder(Activity activity, int step) {
            this.activity = activity;

            card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            int pad = JacTheme.dp(activity, 24);
            card.setPadding(pad, pad, pad, pad);

            GradientDrawable background = new GradientDrawable();
            background.setColor(JacTheme.card(activity));
            background.setCornerRadius(JacTheme.dp(activity, 16));
            card.setBackground(background);

            if (step > 0) {
                TextView counter = new TextView(activity);
                counter.setText(JacStrings.get(activity, R.string.jac_quarantine_step, step, TOTAL_STEPS));
                counter.setTextSize(11f);
                counter.setAllCaps(true);
                counter.setLetterSpacing(0.14f);
                counter.setTextColor(JacTheme.textMuted(activity));
                card.addView(counter);
            }

            actions = new LinearLayout(activity);
            actions.setOrientation(LinearLayout.VERTICAL);
        }

        void setGlyph(JacIcons.Glyph glyph, int colour) {
            View view = new View(activity) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                private final RectF bounds = new RectF();

                @Override
                protected void onDraw(Canvas canvas) {
                    bounds.set(0, 0, getWidth(), getHeight());
                    paint.setColor(colour);
                    JacIcons.draw(canvas, glyph, bounds, paint);
                }
            };
            int size = JacTheme.dp(activity, 26);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.topMargin = JacTheme.dp(activity, 14);
            card.addView(view, params);
        }

        void setTitle(String text) {
            TextView title = new TextView(activity);
            title.setText(text);
            title.setTextSize(19f);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            title.setTextColor(JacTheme.text(activity));
            LinearLayout.LayoutParams params = wrap();
            params.topMargin = JacTheme.dp(activity, 10);
            card.addView(title, params);
        }

        void setBody(String text) {
            if (TextUtils.isEmpty(text)) {
                return;
            }
            TextView body = new TextView(activity);
            body.setText(text.trim());
            body.setTextSize(15f);
            body.setLineSpacing(JacTheme.dp(activity, 4), 1f);
            body.setTextColor(JacTheme.textMuted(activity));
            LinearLayout.LayoutParams params = wrap();
            params.topMargin = JacTheme.dp(activity, 12);
            card.addView(body, params);
        }

        CheckBox addCheckbox(String text) {
            CheckBox box = new CheckBox(activity);
            box.setText(text);
            box.setTextSize(14.5f);
            box.setTextColor(JacTheme.text(activity));
            box.setPadding(JacTheme.dp(activity, 10), 0, 0, 0);
            // Generous vertical padding: this is the one control on the screen
            // that must be hit deliberately, so it gets a target that a thumb
            // cannot clip by accident on the way to the button below.
            LinearLayout.LayoutParams params = wrap();
            params.topMargin = JacTheme.dp(activity, 20);
            params.bottomMargin = JacTheme.dp(activity, 6);
            card.addView(box, params);
            return box;
        }

        Dialog create() {
            card.addView(actions, wrap());

            LinearLayout container = new LinearLayout(activity);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setGravity(Gravity.CENTER);
            int margin = JacTheme.dp(activity, 20);
            container.setPadding(margin, margin, margin, margin);
            container.addView(card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(container);
            // Not cancellable by tapping outside or by Back. Dismissal is not a
            // decision, and on this screen every exit has to be one — the safe
            // exit is right there, filled and full width.
            dialog.setCanceledOnTouchOutside(false);
            dialog.setCancelable(false);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(0xB3000000));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }
            return dialog;
        }

        TextView addPrimaryAction(String text, View.OnClickListener listener) {
            TextView button = button(text, JacTheme.onPrimary(activity));
            GradientDrawable background = new GradientDrawable();
            background.setColor(JacTheme.primary(activity));
            background.setCornerRadius(JacTheme.dp(activity, 12));
            button.setBackground(background);
            button.setOnClickListener(listener);
            actions.addView(button, buttonParams(JacTheme.dp(activity, 22)));
            return button;
        }

        /**
         * The way out that is allowed but not offered.
         *
         * No fill, no outline, no red — red is a warning colour and putting it
         * on the escape hatch makes the hatch the loudest thing on the screen.
         * Muted grey text at a smaller size reads as what it is: a link for
         * someone who has already decided, and nothing the eye lands on first.
         */
        TextView addMutedAction(String text, View.OnClickListener listener) {
            TextView button = button(text, JacTheme.textMuted(activity));
            button.setTextSize(15f);
            button.setTypeface(android.graphics.Typeface.DEFAULT);
            button.setBackground(null);
            button.setOnClickListener(listener);
            actions.addView(button, buttonParams(JacTheme.dp(activity, 4)));
            return button;
        }

        /**
         * Hold both buttons inert for {@link #COUNTDOWN_SECONDS}, counting down
         * on the primary one.
         *
         * The counter goes on the SAFE button on purpose. It is the one the
         * user should end up pressing, so it is the one that has to explain the
         * wait; a countdown on "Install anyway" would advertise the escape
         * hatch and turn the delay into a three-second trailer for it.
         */
        void startCountdown(Dialog dialog, TextView primary, TextView muted) {
            final String label = primary.getText().toString();
            final int[] remaining = {COUNTDOWN_SECONDS};

            setActionsEnabled(primary, muted, false);
            primary.setText(label + "  " + remaining[0]);

            final Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                // The dialog can be gone: dismissed by the caller, or the
                // activity torn down under it. Touching the views then is a
                // leak at best, so the countdown checks before every step and
                // simply stops.
                if (!dialog.isShowing()) {
                    return;
                }
                remaining[0]--;
                if (remaining[0] > 0) {
                    primary.setText(label + "  " + remaining[0]);
                    primary.postDelayed(tick[0], 1000L);
                } else {
                    primary.setText(label);
                    setActionsEnabled(primary, muted, true);
                }
            };
            primary.postDelayed(tick[0], 1000L);
        }

        private void setActionsEnabled(TextView primary, TextView muted, boolean enabled) {
            primary.setEnabled(enabled);
            muted.setEnabled(enabled);
            // Alpha rather than a colour swap: it dims the filled background
            // and the label together, so a disabled primary button still looks
            // like the same button rather than a different, greyer control.
            primary.setAlpha(enabled ? 1f : 0.45f);
            muted.setAlpha(enabled ? 1f : 0.25f);
        }

        TextView addDangerAction(String text, View.OnClickListener listener) {
            TextView button = button(text, JacTheme.danger(activity));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.TRANSPARENT);
            background.setCornerRadius(JacTheme.dp(activity, 12));
            background.setStroke(JacTheme.dp(activity, 1.5f), JacTheme.danger(activity));
            button.setBackground(background);
            button.setOnClickListener(listener);
            actions.addView(button, buttonParams(JacTheme.dp(activity, 10)));
            return button;
        }

        private TextView button(String text, int textColour) {
            TextView button = new TextView(activity);
            button.setText(text);
            button.setTextSize(16f);
            button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            button.setTextColor(textColour);
            button.setGravity(Gravity.CENTER);
            return button;
        }

        private LinearLayout.LayoutParams buttonParams(int topMargin) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, JacTheme.dp(activity, 52));
            params.topMargin = topMargin;
            return params;
        }

        private LinearLayout.LayoutParams wrap() {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
