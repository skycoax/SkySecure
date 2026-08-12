package uz.jac.secure.android;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.StatsController;
import org.telegram.tgnet.TLRPC;

/**
 * The user's answer to "may this app spend my data checking files I have not
 * opened?"
 *
 * <h3>Why a setting is required rather than nice to have</h3>
 *
 * {@link ScanAutoStart} downloads a document the moment it comes into view, so
 * that the verdict is on screen before the user decides whether to tap it. That
 * is the right behaviour for a scanner and it is also, in plain terms, the app
 * spending someone's mobile data on a transfer they never asked for. The
 * Telegram API Terms name that directly in 1.4 — "making actions on behalf of
 * the user without the user's knowledge and consent" is listed as interference
 * with basic functionality.
 *
 * <p>It is not only a rules question. Telegram's own auto-download settings
 * exist because mobile data is expensive here, and an app that quietly ignores
 * them is one the user is right to uninstall.
 *
 * <h3>What the default is, and why it is not "follow Telegram exactly"</h3>
 *
 * {@link #AUTO_CHECK_WIFI} — check automatically on Wi-Fi, never on mobile.
 *
 * <p>The stricter option was to defer entirely to
 * {@code DownloadController.canDownloadMedia}, so the scanner only ever
 * pre-fetches what Telegram would have fetched anyway. That is the safest
 * reading of 1.4 and it was rejected, because Telegram's document auto-download
 * is off by default for most chat kinds — including the "a stranger sent you a
 * file in a private chat" case this product exists for. Inheriting it would
 * have produced a scanner that is silent at precisely the moment it matters,
 * while appearing to work.
 *
 * <p>So the compromise is on the axis the user actually cares about: money. On
 * Wi-Fi the transfer costs nothing and the check happens. On mobile nothing is
 * fetched unless the user has explicitly asked for it. Either way the filename
 * analysis still runs — see {@link ScanGate#previewByName}, which catches the
 * right-to-left override and double-extension tricks with zero bytes
 * downloaded and no network at all.
 */
public final class ScanSettings {

    /** Never fetch a file on our own initiative. Filename checks still run. */
    public static final int AUTO_CHECK_NEVER = 0;
    /** Fetch on unmetered Wi-Fi only. The default. */
    public static final int AUTO_CHECK_WIFI = 1;
    /** Fetch on any connection, including mobile and roaming. */
    public static final int AUTO_CHECK_ALWAYS = 2;

    /**
     * Files above this are never fetched just to be scanned, on any setting.
     *
     * A cap rather than a preference because there is no honest way to present
     * the choice: the user cannot know that agreeing to "always" means a 700 MB
     * video is pulled down in the background. Malware in this market is an APK
     * of a few megabytes, so the detection lost at the top end is close to
     * nothing. A file over the cap is still scanned normally once the user
     * downloads it themselves.
     */
    private static final long MAX_AUTO_FETCH_BYTES = 64L * 1024 * 1024;

    private static final String KEY_AUTO_CHECK = "jac_auto_check";

    /**
     * Cached because the value is read on the message-binding path, which runs
     * on every scroll. Volatile rather than synchronised: a stale read costs
     * one file checked under the previous setting.
     */
    private static volatile int autoCheck = -1;

    private ScanSettings() {
    }

    public static int getAutoCheck() {
        int value = autoCheck;
        if (value == -1) {
            value = prefs().getInt(KEY_AUTO_CHECK, AUTO_CHECK_WIFI);
            autoCheck = value;
        }
        return value;
    }

    public static void setAutoCheck(int value) {
        autoCheck = value;
        prefs().edit().putInt(KEY_AUTO_CHECK, value).apply();
    }

    /**
     * May we start a download for this message purely in order to scan it?
     *
     * <p>Answers only the policy question. The caller still decides whether a
     * scan is wanted at all — this method knows nothing about what is already
     * on disk or already in flight.
     */
    public static boolean mayFetchForScan(MessageObject message) {
        final int policy = getAutoCheck();
        if (policy == AUTO_CHECK_NEVER) {
            return false;
        }
        if (sizeOf(message) > MAX_AUTO_FETCH_BYTES) {
            return false;
        }
        if (policy == AUTO_CHECK_ALWAYS) {
            return true;
        }
        // TYPE_WIFI here means "unmetered", not "the radio says Wi-Fi":
        // getAutodownloadNetworkType() reports a metered Wi-Fi hotspot as
        // TYPE_MOBILE, which is what a user tethering off their phone means by
        // "not on Wi-Fi". It also fails to TYPE_MOBILE on any error, so an
        // unknown network is treated as one that costs money.
        return ApplicationLoader.getAutodownloadNetworkType() == StatsController.TYPE_WIFI;
    }

    private static long sizeOf(MessageObject message) {
        if (message == null) {
            return 0;
        }
        TLRPC.Document document = message.getDocument();
        return document != null ? document.size : 0;
    }

    /**
     * Deliberately the global "mainconfig" file, not the scanner's own
     * {@code jac_secure} prefs — that one holds a device token and is cleared
     * when the token is rotated. This setting sits beside the auto-download
     * presets it exists to complement, and survives the same events they do.
     */
    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }
}
