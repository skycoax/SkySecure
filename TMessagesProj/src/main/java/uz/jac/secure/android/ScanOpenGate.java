package uz.jac.secure.android;

import android.app.Activity;

import org.telegram.messenger.R;

import uz.jac.secure.core.model.Verdict;

import java.io.File;

/**
 * The one place an installer is stopped on its way to the system installer.
 *
 * <h3>Why this exists where {@code ChatMessageCell} already intercepts</h3>
 *
 * The message cell intercepts a tap on a document, but that is one door of
 * many. A file the user has downloaded can be opened again from the Downloads
 * tab, the shared-media "Files" grid, global search, the photo viewer, the
 * cache screen, a bot's downloads — none of which go through the cell. Every
 * one of them, though, and the cell too, ends at the same two static methods
 * in {@code AndroidUtilities} that build the {@code ACTION_VIEW} intent. So the
 * gate belongs there: one check on the single road out to the OS, rather than
 * a guard on each of the eleven doors and a twelfth shipped next release with
 * none.
 *
 * <h3>Default-deny</h3>
 *
 * An installer with no verdict on record is treated as dangerous, not as fine.
 * That is the whole rule of this product inverted from the usual default: a
 * file reached by a path the scanner never watched (saved, forwarded, opened
 * from another app's share) has no state, and "no state" for an APK means
 * "nobody has checked this" — which is exactly the condition a warning is for.
 * The only things that open without a warning are a non-installer, and an
 * installer the user has already taken through the two-step override.
 *
 * <p>Local-only scanning means an installer's verdict never becomes CLEAN, so
 * in practice every APK warns until the user overrides it once. That is the
 * intended posture, not a limitation: on this product an APK from a chat is
 * guilty until the person chooses, deliberately, to trust it.
 */
public final class ScanOpenGate {

    private ScanOpenGate() {
    }

    /**
     * Decide whether an about-to-open file needs a warning first.
     *
     * @param proceed what "open it anyway" does — the caller's own intent
     *                launch, which the gate runs after the two-step override.
     * @return true if a warning was shown and the open must stop here; false
     *         if the file is clear to open now.
     */
    public static boolean guard(Activity activity, File file, String fileName, String mime,
                                Runnable proceed) {
        try {
            if (activity == null || file == null || proceed == null) {
                return false;
            }
            if (!isInstaller(fileName, mime)) {
                return false;
            }
            if (!ScannerBootstrap.isInstalled()) {
                // No scanner in this build: do not invent a block it cannot back
                // up, and do not swallow the open. Upstream behaviour stands.
                return false;
            }

            final String path = file.getAbsolutePath();
            ScanStateStore.State state = ScanGate.findState(path);

            // Already waved through once, or actually cleared: open, no drama.
            if (state != null && (state.overridden || state.verdict == Verdict.CLEAN)) {
                return false;
            }

            // Everything else — no record, still scanning, unchecked, suspicious
            // or malicious — is stopped. The words track how much is known:
            // a plain installer gets its verdict's wording where there is one,
            // and the default "this is a virus" only where the verdict earns it.
            String title = null;
            String body = null;
            if (state != null && state.verdict != Verdict.MALICIOUS) {
                ScanUi.Presentation p = ScanUi.present(activity, state);
                if (p != null) {
                    title = p.title;
                    body = p.body;
                }
            }
            if (title == null) {
                // No state at all: name the risk plainly rather than asserting a
                // specific infection we have not proven.
                title = JacStrings.get(activity, R.string.jac_unchecked_title);
                body = JacStrings.get(activity, R.string.jac_unchecked_body);
            }

            QuarantineDialogs.showVirusWarning(activity, file, title, body, confirmed -> {
                // The confirm callback hands back the file; record the override
                // so a second tap does not re-litigate it, then run the open.
                ScanGate.markOverriddenAnywhere(confirmed != null
                        ? confirmed.getAbsolutePath() : path);
                proceed.run();
            });
            return true;
        } catch (Throwable t) {
            // A gate that throws must not become a gate that also blocks the
            // messenger. On any failure, fall back to upstream behaviour.
            return false;
        }
    }

    /**
     * The verdict colour for an installer's icon, or 0 for none.
     *
     * The shared-media file list draws its own icon, far from the chat cell's
     * {@code jacMarkColour}, so the same two-level rule lives here too: red for
     * danger (malicious, or an installer with no verdict yet), amber for
     * suspicion, nothing for cleared, clean, or non-installer files. Kept beside
     * the gate because both answer the same question — "how bad is this file" —
     * from the same state store.
     */
    public static int markColour(android.content.Context context, java.io.File file,
                                 String fileName, String mime) {
        try {
            if (context == null || !isInstaller(fileName, mime)) {
                return 0;
            }
            int danger = JacTheme.danger(context);
            int warning = JacTheme.warning(context);
            ScanStateStore.State state = ScanGate.findState(file != null ? file.getAbsolutePath() : null);
            if (state != null && (state.overridden || state.verdict == Verdict.CLEAN)) {
                return 0;
            }
            if (state == null) {
                return danger;
            }
            ScanUi.Presentation p = ScanUi.present(context, state);
            if (p != null && (p.colour == danger || p.colour == warning)) {
                return p.colour;
            }
            return danger;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Installer by name or declared type. Same test as the message cell's, kept
     * here too because this gate runs where the cell's fields are out of reach.
     */
    private static boolean isInstaller(String fileName, String mime) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        if (name.endsWith(".apk") || name.endsWith(".apks")
                || name.endsWith(".xapk") || name.endsWith(".apkm")) {
            return true;
        }
        String m = mime == null ? "" : mime.toLowerCase();
        return m.contains("android.package-archive");
    }
}
