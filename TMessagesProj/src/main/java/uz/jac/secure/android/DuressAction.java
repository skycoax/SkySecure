package uz.jac.secure.android;

import android.app.ActivityManager;
import android.content.Context;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

/**
 * What the duress passcode does once {@link DuressConfig} has recognised it.
 *
 * <h3>Why this uses the OS wipe, not Telegram's logout</h3>
 *
 * The obvious implementation — log every account out and delete the caches —
 * does not actually protect anyone, and the reason is subtle enough to be worth
 * writing down. Every teardown path Telegram exposes to the Java layer is
 * asynchronous: {@code ConnectionsManager.cleanup()} only <em>enqueues</em> the
 * auth-key erasure onto the network thread, {@code MessagesStorage.cleanup()}
 * posts to the storage thread, {@code performLogout} sends a network request.
 * None of them have finished when the method returns. The moment this defends
 * against — a phone taken and powered off a second later — is exactly the moment
 * those queued tasks never get to run, so the MTProto key in {@code tgnet.dat}
 * and the messages in {@code cache4.db} survive on disk and the account is
 * trivially resumed. An anti-coercion wipe that races a power button and loses
 * is worse than none, because it sells a safety it does not deliver.
 *
 * <p>So the wipe is {@link ActivityManager#clearApplicationUserData()}: the
 * platform erases the entire app data directory — every database, every cached
 * photo and document, {@code tgnet.dat}, the shared-preferences (including the
 * {@code jac_duress} secret itself), all of it — and kills the process. It is
 * the same thing "Clear storage" in Android's app settings does. It is owned by
 * the OS, so it does not depend on the network, on a background thread draining
 * its queue, or on the app living long enough to finish; and it is complete, so
 * there is nothing left on the device to recover. The app that relaunches is a
 * fresh install: a logged-out Telegram, which the setup screen promises plainly.
 *
 * <h3>The server session is a best effort, and honestly so</h3>
 *
 * Before the wipe we fire {@code auth.logOut} for each account so that, when
 * there <em>is</em> a signal, the session also dies server-side. But the OS
 * wipe kills the process moments later, so this may not reach the wire — and we
 * do not pretend otherwise. The guarantee this feature makes is about the
 * device in the adversary's hand, which the local wipe keeps completely; a
 * session that outlives it server-side is reachable only with a token that is
 * no longer anywhere on this phone.
 *
 * <h3>Unstoppable and total</h3>
 *
 * Called from the lock screen the instant the duress code is recognised. It
 * must not throw before it reaches the wipe — a crash halfway would be the one
 * outcome worse than no wipe — so the best-effort logout is fully guarded.
 */
public final class DuressAction {

    private DuressAction() {
    }

    public static void run(Context context, int action) {
        // Only one action today; the switch is here so adding another is a
        // case, not a rewrite.
        switch (action) {
            case DuressConfig.ACTION_LOGOUT_ALL:
            default:
                wipeEverything(context);
                break;
        }
    }

    private static void wipeEverything(Context context) {
        // Best effort, before the wipe: tell the server to drop each session.
        // Sent while the account is still logged in so the request is not
        // parked waiting for a login; it may not flush before the process dies,
        // which is why the local wipe below is the actual guarantee.
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    ConnectionsManager.getInstance(a).sendRequest(
                            new TLRPC.TL_auth_logOut(), (response, error) -> {});
                }
            } catch (Throwable ignored) {
            }
        }

        // The guarantee: the OS erases the whole app data directory — every DB,
        // cached file, tgnet.dat and shared-prefs file (the duress secret with
        // them) — and kills the process. Works offline, needs no thread to
        // drain, and cannot be pre-empted by a power-off because the system,
        // not this process, carries it out.
        try {
            ActivityManager am = (ActivityManager)
                    context.getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.clearApplicationUserData()) {
                return;
            }
        } catch (Throwable ignored) {
        }

        // clearApplicationUserData is documented as always available since API
        // 19 (minSdk here is 21), but if it ever refuses, fall back to
        // Telegram's own logout so the wipe is degraded, never skipped.
        fallbackLogout(context);
    }

    private static void fallbackLogout(Context context) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    MessagesController.getInstance(a).performLogout(1);
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            DuressConfig.clear(context);
        } catch (Throwable ignored) {
        }
    }
}
