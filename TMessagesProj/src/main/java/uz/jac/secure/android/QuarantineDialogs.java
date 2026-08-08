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

        private final Activity activity;
        private final LinearLayout card;
        private final LinearLayout actions;
        private Dialog dialog;

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

            TextView counter = new TextView(activity);
            counter.setText(JacStrings.get(activity, R.string.jac_quarantine_step, step, TOTAL_STEPS));
            counter.setTextSize(11f);
            counter.setAllCaps(true);
            counter.setLetterSpacing(0.14f);
            counter.setTextColor(JacTheme.textMuted(activity));
            card.addView(counter);

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

        void addPrimaryAction(String text, View.OnClickListener listener) {
            TextView button = button(text, JacTheme.onPrimary(activity));
            GradientDrawable background = new GradientDrawable();
            background.setColor(JacTheme.primary(activity));
            background.setCornerRadius(JacTheme.dp(activity, 12));
            button.setBackground(background);
            button.setOnClickListener(listener);
            actions.addView(button, buttonParams(JacTheme.dp(activity, 22)));
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
