package uz.jac.secure.android;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineEditText;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.PasscodeActivity;

import java.util.ArrayList;

/**
 * Sets up the false ("duress") passcode — see {@link DuressConfig}.
 *
 * <h3>The precondition, made unmissable</h3>
 *
 * A duress code is meaningless without a real one: if the app never locks,
 * there is no moment where a code is demanded. So when no app passcode is set
 * this screen shows nothing but an explanation and a shortcut to Telegram's own
 * passcode setup — it does not offer a duress field the user could fill in and
 * believe was protecting them while the app sat unlocked.
 *
 * <h3>The one refusal</h3>
 *
 * The duress code may not equal the real passcode. If it did, the owner's
 * everyday unlock would silently wipe the phone — a far more frequent disaster
 * than the coercion this defends against. The candidate is checked against the
 * real passcode with Telegram's own {@link SharedConfig#checkPasscode}; a match
 * is refused with an explanation, never stored.
 *
 * <p>Built from upstream parts per the house rule: a {@link UniversalFragment},
 * an {@link AlertDialog} for entry, {@link EditTextBoldCursor} for the field.
 */
public class DuressSetupActivity extends UniversalFragment {

    private static final int BUTTON_SET_PASSCODE = 1;
    private static final int BUTTON_SET_DURESS = 2;
    private static final int BUTTON_REMOVE_DURESS = 3;

    @Override
    public View createView(Context context) {
        fragmentView = super.createView(context);
        listView.adapter.setApplyBackground(false);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Coming back from Telegram's passcode setup, the precondition may have
        // flipped — re-fill so the field appears the moment a passcode exists.
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return JacStrings.get(getContext(), R.string.jac_duress);
    }

    private static boolean hasAppPasscode() {
        return !SharedConfig.passcodeHash.isEmpty();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final Context context = getContext();
        items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_duress_intro)));

        if (!hasAppPasscode()) {
            // No lock, no point: send the user to set one first.
            items.add(UItem.asButton(BUTTON_SET_PASSCODE,
                    JacStrings.get(context, R.string.jac_duress_need_passcode)).accent());
            items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_duress_need_passcode_info)));
            return;
        }

        final boolean on = DuressConfig.isEnabled(context);
        items.add(UItem.asButton(BUTTON_SET_DURESS, JacStrings.get(context,
                on ? R.string.jac_duress_change : R.string.jac_duress_set)).accent());
        if (on) {
            items.add(UItem.asButton(BUTTON_REMOVE_DURESS,
                    JacStrings.get(context, R.string.jac_duress_remove)).red());
        }
        items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_duress_action_info)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BUTTON_SET_PASSCODE) {
            presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE));
            return;
        }
        if (item.id == BUTTON_SET_DURESS) {
            promptForCode();
            return;
        }
        if (item.id == BUTTON_REMOVE_DURESS) {
            DuressConfig.clear(getContext());
            listView.adapter.update(true);
        }
    }

    /**
     * Ask for the duress code twice, in one dialog: a field and its confirm.
     * Numeric, because the real passcode this stands in for is a PIN.
     */
    private void promptForCode() {
        final Context context = getContext();

        // The duress code is entered on the very same lock screen as the real
        // passcode, so it must be shaped the same. A PIN lock auto-submits at
        // exactly four digits, so a longer duress code could never be typed
        // there — the field is capped to four in that mode, and validation
        // requires exactly four, so no unreachable code can ever be saved.
        final boolean pinMode = SharedConfig.passcodeType == SharedConfig.PASSCODE_TYPE_PIN;

        // Telegram's own outlined input with a floating label, the same field
        // its "new contact" and passcode screens use — so the two boxes read
        // as labelled inputs, not a pair of bare underlines.
        final OutlineEditText field = pinField(context, R.string.jac_duress_enter, pinMode);
        final OutlineEditText confirm = pinField(context, R.string.jac_duress_confirm, pinMode);

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        final int pad = AndroidUtilities.dp(20);
        container.setPadding(pad, AndroidUtilities.dp(4), pad, 0);
        container.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58));
        container.addView(confirm, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 0, 12, 0, 0));

        field.getEditText().setOnEditorActionListener((v, id, e) -> {
            confirm.getEditText().requestFocus();
            return true;
        });

        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(JacStrings.get(context, R.string.jac_duress_set))
                .setView(container)
                .setPositiveButton(JacStrings.get(context, R.string.jac_duress_save), null)
                .setNegativeButton(org.telegram.messenger.LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.setOnShowListener(d -> {
            field.getEditText().requestFocus();
            AndroidUtilities.showKeyboard(field.getEditText());
            // Validate on the button without letting a bad entry dismiss the
            // dialog — the stock AlertDialog dismisses on any button tap, so the
            // click is overridden after show().
            final View save = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            if (save != null) {
                save.setOnClickListener(v -> {
                    final String code = field.getEditText().getText().toString();
                    final String again = confirm.getEditText().getText().toString();
                    // Exactly four in PIN mode (the lock screen submits at four),
                    // at least four otherwise.
                    if (code.length() < 4 || (pinMode && code.length() != 4)) {
                        shake(field);
                        return;
                    }
                    if (!code.equals(again)) {
                        shake(confirm);
                        return;
                    }
                    // The real passcode always wins: a duress code equal to it
                    // would turn the owner's own unlock into a wipe.
                    if (SharedConfig.checkPasscode(code)) {
                        showError(R.string.jac_duress_same_as_real);
                        return;
                    }
                    DuressConfig.set(context, code, DuressConfig.ACTION_LOGOUT_ALL);
                    AndroidUtilities.hideKeyboard(field.getEditText());
                    dialog.dismiss();
                    listView.adapter.update(true);
                });
            }
        });
        showDialog(dialog);
    }

    private OutlineEditText pinField(Context context, int hintRes, boolean pinMode) {
        final OutlineEditText outline = new OutlineEditText(context);
        outline.setBackground(null);
        outline.setHint(JacStrings.get(context, hintRes));
        final EditTextBoldCursor edit = outline.getEditText();
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        edit.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        if (pinMode) {
            // A PIN lock reads exactly four digits, so never let a fifth be
            // typed here — a longer code would be unreachable at unlock.
            edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        }
        return outline;
    }

    private void showError(int messageRes) {
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(JacStrings.get(getContext(), R.string.jac_duress))
                .setMessage(JacStrings.get(getContext(), messageRes))
                .setPositiveButton(org.telegram.messenger.LocaleController.getString(R.string.OK), null)
                .create());
    }

    private void shake(View view) {
        AndroidUtilities.shakeView(view);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
