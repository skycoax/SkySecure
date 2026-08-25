package uz.jac.secure.android;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The on-device half of the security checkup: what state is this phone in?
 *
 * <p>A messenger that scans files and links is answering "is this thing I was
 * sent dangerous?". This class answers the question one level down — "is the
 * ground it all runs on solid?" — because a scanner on a rooted phone with no
 * screen lock is a seatbelt in a car with no brakes.
 *
 * <h3>What is checked, and what deliberately is not</h3>
 *
 * Every check here reads local device state and nothing else: a handful of
 * file-exists probes, two Settings values, one system service, one build
 * property. Nothing is sent anywhere — the checkup makes no network requests
 * at all from this class. The one account-side check (does the account have a
 * cloud password) is a Telegram API call and therefore lives with the screen
 * that shows it, not here.
 *
 * <p>Play Protect status and system-wide sideloading are not checked because
 * Android gives an ordinary app no honest way to read them; a check that
 * guesses would teach the user to distrust the ones that don't.
 *
 * <h3>Severity</h3>
 *
 * Two levels, not five. {@link #DANGER} means "someone with your phone in
 * their hand, or a malicious app, has a straight path" — no screen lock, root.
 * {@link #WARN} means "this widens the attack surface and most people have no
 * reason to have it on" — USB debugging, a security-patch level more than a
 * year stale. The distinction feeds the row colour and nothing else; both are
 * worth fixing.
 */
public final class SecurityCheckup {

    public static final int OK = 0;
    public static final int WARN = 1;
    public static final int DANGER = 2;

    /** Stable ids; the screen maps them to titles, advice and fix actions. */
    public static final int CHECK_SCREEN_LOCK = 1;
    public static final int CHECK_ROOT = 2;
    public static final int CHECK_ADB = 3;
    public static final int CHECK_PATCH = 4;

    /** How stale a security-patch level may be before it is worth a warning. */
    private static final long PATCH_STALE_MS = 365L * 24 * 60 * 60 * 1000;

    /** How often the reminder may fire, and how old a checkup may grow. */
    private static final long REMIND_EVERY_MS = 30L * 24 * 60 * 60 * 1000;

    public static final class Finding {
        public final int id;
        public final int severity;

        Finding(int id, int severity) {
            this.id = id;
            this.severity = severity;
        }
    }

    private SecurityCheckup() {
    }

    /** Run every local check. Cheap enough for the UI thread: a few file stats and settings reads. */
    public static List<Finding> runLocal(Context context) {
        List<Finding> findings = new ArrayList<>(4);
        findings.add(new Finding(CHECK_SCREEN_LOCK, hasScreenLock(context) ? OK : DANGER));
        findings.add(new Finding(CHECK_ROOT, looksRooted() ? DANGER : OK));
        findings.add(new Finding(CHECK_ADB, isAdbEnabled(context) ? WARN : OK));
        findings.add(new Finding(CHECK_PATCH, isPatchStale() ? WARN : OK));
        return findings;
    }

    public static int countIssues(List<Finding> findings) {
        int n = 0;
        for (Finding f : findings) {
            if (f.severity != OK) {
                n++;
            }
        }
        return n;
    }

    /**
     * The monthly nudge, called from the chat list's onResume.
     *
     * <p>Deliberately quiet: it fires only when the last checkup (or the last
     * nudge) is over a month old <em>and</em> a quick local scan actually finds
     * something — a phone that passes clean is never nagged. One Bulletin, the
     * same component upstream uses for its own gentle suggestions; anything
     * louder would spend the trust the scanner's real warnings depend on.
     */
    public static void maybeRemind(BaseFragment fragment) {
        try {
            if (fragment == null || fragment.getContext() == null || fragment.isInPreviewMode()) {
                return;
            }
            final Context context = fragment.getContext();
            final long now = System.currentTimeMillis();
            if (now - HumogramConfig.getCheckupAt(context) < REMIND_EVERY_MS
                    || now - HumogramConfig.getCheckupRemindAt(context) < REMIND_EVERY_MS) {
                return;
            }
            if (countIssues(runLocal(context)) == 0) {
                // A clean phone still counts as checked: quietly refresh the
                // clock so the next look is a month out, not every resume.
                HumogramConfig.setCheckupAt(context, now);
                return;
            }
            // Delayed, because onResume at cold start runs while the window is
            // still blank: a Bulletin shown there has expired before the chat
            // list has drawn its first frame and nobody ever sees it.
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (fragment.getParentActivity() == null || fragment.isPaused()) {
                        return;
                    }
                    BulletinFactory.of(fragment).createSimpleBulletin(
                            R.raw.chats_infotip,
                            JacStrings.get(context, R.string.jac_checkup_remind),
                            JacStrings.get(context, R.string.jac_checkup_remind_open),
                            () -> fragment.presentFragment(new SecurityCheckupActivity())
                    ).show();
                    // Stamped only now: a slot spent on a nudge nobody saw is
                    // a month of silence bought for nothing.
                    HumogramConfig.setCheckupRemindAt(context, System.currentTimeMillis());
                } catch (Throwable ignored) {
                }
            }, 2000);
        } catch (Throwable ignored) {
            // A reminder must never be able to break the chat list.
        }
    }

    private static boolean hasScreenLock(Context context) {
        try {
            KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null) {
                return true; // unknowable — do not accuse
            }
            // isDeviceSecure arrived in API 23; below that isKeyguardSecure is
            // the closest answer (it also counts a SIM lock, which overreports
            // slightly — the right way to be wrong for a warning like this).
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? km.isDeviceSecure()
                    : km.isKeyguardSecure();
        } catch (Throwable ignored) {
            return true; // unknowable — do not accuse
        }
    }

    private static boolean looksRooted() {
        try {
            String tags = Build.TAGS;
            if (tags != null && tags.contains("test-keys")) {
                return true;
            }
            final String[] paths = {
                    "/system/bin/su", "/system/xbin/su", "/sbin/su",
                    "/su/bin/su", "/system/app/Superuser.apk",
                    "/system/sd/xbin/su", "/data/local/xbin/su", "/data/local/bin/su"
            };
            for (String path : paths) {
                if (new File(path).exists()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isAdbEnabled(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isPatchStale() {
        try {
            String patch = Build.VERSION.SECURITY_PATCH;
            if (patch == null || patch.isEmpty()) {
                return false; // unknowable — do not accuse
            }
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch);
            return date != null && System.currentTimeMillis() - date.getTime() > PATCH_STALE_MS;
        } catch (ParseException | RuntimeException ignored) {
            return false;
        }
    }
}
