package uz.jac.secure.android;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.TwoStepVerificationSetupActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * The security checkup screen: the local findings from
 * {@link SecurityCheckup}, plus the one account-side check — does this
 * account have a cloud password (2FA)?
 *
 * <p>Rendered entirely with upstream parts, per the house rule: a
 * {@link UniversalFragment} whose rows are stock buttons, so it looks like any
 * other Telegram settings page. A row's value column says OK / attention /
 * risk; tapping a row explains the check in an {@link AlertDialog} and, when
 * something is wrong, offers the one action that fixes it — the relevant
 * system settings screen, or Telegram's own 2FA setup.
 *
 * <p>The 2FA check is the only thing here that talks to a server, and it is a
 * standard {@code account.getPassword} status request to Telegram itself —
 * the same call the official Privacy settings page makes. The row shows an
 * ellipsis until the answer arrives; if the request fails it reports
 * "couldn't check" and tapping it retries, never a guess.
 *
 * <h3>Why the checks re-run on resume</h3>
 *
 * Every fix this screen offers happens somewhere else — in system settings, or
 * in Telegram's 2FA wizard — and the user comes straight back here afterwards.
 * A page that still said "Risk" after the thing was fixed would teach people
 * that the checkup does not know what it is talking about, so the local checks
 * run again on every resume and the account check is re-requested.
 */
public class SecurityCheckupActivity extends UniversalFragment {

    /** Account-side check id, out of the way of SecurityCheckup.CHECK_*. */
    private static final int CHECK_2FA = 100;

    private List<SecurityCheckup.Finding> findings;
    private TL_account.Password password; // null while the request is in flight
    private boolean check2faFailed;

    /** Live request id, so the response cannot arrive after this screen is gone. */
    private int passwordReqId = -1;

    @Override
    public View createView(Context context) {
        findings = SecurityCheckup.runLocal(context);
        HumogramConfig.setCheckupAt(context, System.currentTimeMillis());
        load2fa();

        fragmentView = super.createView(context);
        listView.adapter.setApplyBackground(false);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-check after a trip to system settings or the 2FA wizard.
        if (findings == null || getContext() == null) {
            return;
        }
        findings = SecurityCheckup.runLocal(getContext());
        HumogramConfig.setCheckupAt(getContext(), System.currentTimeMillis());
        if (passwordReqId == -1) {
            load2fa();
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onFragmentDestroy() {
        cancelPasswordRequest();
        super.onFragmentDestroy();
    }

    private void cancelPasswordRequest() {
        if (passwordReqId != -1) {
            getConnectionsManager().cancelRequest(passwordReqId, true);
            passwordReqId = -1;
        }
    }

    private void load2fa() {
        cancelPasswordRequest();
        check2faFailed = false;
        final TL_account.getPassword req = new TL_account.getPassword();
        passwordReqId = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            passwordReqId = -1;
            // The response can outlive the screen; fillItems would then run
            // with a null context and take the whole app down with it.
            if (isFinishing() || getContext() == null) {
                return;
            }
            if (response instanceof TL_account.Password) {
                password = (TL_account.Password) response;
            } else {
                check2faFailed = true;
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagWithoutLogin);
    }

    @Override
    protected CharSequence getTitle() {
        return JacStrings.get(getContext(), R.string.jac_checkup);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final Context context = getContext();
        items.add(UItem.asHeader(JacStrings.get(context, R.string.jac_checkup_results)));
        for (SecurityCheckup.Finding finding : findings) {
            items.add(UItem.asButton(finding.id,
                    JacStrings.get(context, titleFor(finding.id)),
                    statusText(context, finding.severity)));
        }
        final CharSequence status2fa;
        if (password == null) {
            status2fa = check2faFailed
                    ? JacStrings.get(context, R.string.jac_checkup_unknown)
                    : "…";
        } else {
            status2fa = statusText(context,
                    password.has_password ? SecurityCheckup.OK : SecurityCheckup.WARN);
        }
        items.add(UItem.asButton(CHECK_2FA,
                JacStrings.get(context, R.string.jac_checkup_2fa), status2fa));
        items.add(UItem.asShadow(JacStrings.get(context, R.string.jac_checkup_note)));
    }

    private static int titleFor(int id) {
        switch (id) {
            case SecurityCheckup.CHECK_SCREEN_LOCK: return R.string.jac_checkup_screen_lock;
            case SecurityCheckup.CHECK_ROOT: return R.string.jac_checkup_root;
            case SecurityCheckup.CHECK_ADB: return R.string.jac_checkup_adb;
            case SecurityCheckup.CHECK_PATCH: return R.string.jac_checkup_patch;
            default: return R.string.jac_checkup;
        }
    }

    private static int adviceFor(int id) {
        switch (id) {
            case SecurityCheckup.CHECK_SCREEN_LOCK: return R.string.jac_checkup_screen_lock_advice;
            case SecurityCheckup.CHECK_ROOT: return R.string.jac_checkup_root_advice;
            case SecurityCheckup.CHECK_ADB: return R.string.jac_checkup_adb_advice;
            case SecurityCheckup.CHECK_PATCH: return R.string.jac_checkup_patch_advice;
            default: return R.string.jac_checkup_2fa_advice;
        }
    }

    /**
     * The severity word for the value column.
     *
     * <p>Coloured with a span rather than {@code UItem.red()}: red() recolours
     * a TextCell's title and icon, and {@code setTextAndValue} resets the value
     * colour on every bind — so the one word that carries the verdict is
     * exactly the part red() cannot reach.
     */
    private CharSequence statusText(Context context, int severity) {
        if (severity == SecurityCheckup.OK) {
            return JacStrings.get(context, R.string.jac_checkup_ok);
        }
        final String text = JacStrings.get(context, severity == SecurityCheckup.DANGER
                ? R.string.jac_checkup_risk : R.string.jac_checkup_warn);
        final SpannableString span = new SpannableString(text);
        span.setSpan(new ForegroundColorSpan(Theme.getColor(severity == SecurityCheckup.DANGER
                        ? Theme.key_text_RedRegular : Theme.key_color_orange)),
                0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        final Context context = getContext();
        if (item.id == CHECK_2FA) {
            if (password == null) {
                if (check2faFailed) {
                    load2fa(); // the row said "couldn't check" — try again
                    listView.adapter.update(true);
                }
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity())
                    .setTitle(JacStrings.get(context, R.string.jac_checkup_2fa))
                    .setMessage(JacStrings.get(context, R.string.jac_checkup_2fa_advice));
            if (!password.has_password) {
                builder.setPositiveButton(JacStrings.get(context, R.string.jac_checkup_set_up),
                        (dialog, which) -> presentFragment(setupFragment()));
                builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            } else {
                builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            }
            showDialog(builder.create());
            return;
        }
        SecurityCheckup.Finding finding = null;
        for (SecurityCheckup.Finding f : findings) {
            if (f.id == item.id) {
                finding = f;
                break;
            }
        }
        if (finding == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity())
                .setTitle(JacStrings.get(context, titleFor(finding.id)))
                .setMessage(JacStrings.get(context, adviceFor(finding.id)));
        final String settingsAction = settingsActionFor(finding.id);
        if (finding.severity != SecurityCheckup.OK && settingsAction != null) {
            builder.setPositiveButton(JacStrings.get(context, R.string.jac_checkup_open_settings),
                    (dialog, which) -> {
                        try {
                            getParentActivity().startActivity(new Intent(settingsAction));
                        } catch (Throwable ignored) {
                            // No such settings screen on this device; the advice text stands.
                        }
                    });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        } else {
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        }
        showDialog(builder.create());
    }

    /**
     * The screen that actually sets a cloud password up.
     *
     * <p>Same choice upstream's Privacy settings makes: the intro wizard for a
     * fresh account, the email-code screen for one already mid-setup. The
     * {@link TL_account.Password} fetched for the row is handed straight over,
     * so the wizard does not re-request it and open on a spinner.
     */
    private TwoStepVerificationSetupActivity setupFragment() {
        final int type = password != null && password.email_unconfirmed_pattern != null
                && !password.email_unconfirmed_pattern.isEmpty()
                ? TwoStepVerificationSetupActivity.TYPE_EMAIL_CONFIRM
                : TwoStepVerificationSetupActivity.TYPE_INTRO;
        return new TwoStepVerificationSetupActivity(currentAccount, type, password);
    }

    /** The system settings screen that fixes a finding, or null when only advice helps. */
    private static String settingsActionFor(int id) {
        switch (id) {
            case SecurityCheckup.CHECK_SCREEN_LOCK: return Settings.ACTION_SECURITY_SETTINGS;
            case SecurityCheckup.CHECK_ADB: return Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS;
            case SecurityCheckup.CHECK_PATCH: return "android.settings.SYSTEM_UPDATE_SETTINGS";
            default: return null; // root: there is no settings page out of root
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
