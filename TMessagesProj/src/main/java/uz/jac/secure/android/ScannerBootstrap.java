package uz.jac.secure.android;

import android.content.Context;
import android.content.res.AssetManager;

import org.telegram.messenger.UserConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import uz.jac.secure.core.engine.EngineFactory;

/**
 * One-line entry point from {@code ApplicationLoader}.
 *
 * <pre>ScannerBootstrap.install(this);</pre>
 *
 * Everything the scanner needs to exist is assembled here rather than in
 * ApplicationLoader, and that is the same rule the download hook follows: the
 * diff against upstream stays one line, so rebasing onto a new Telegram
 * release does not mean re-reading a page of our initialisation logic wedged
 * into the middle of theirs.
 *
 * <h3>Failing to start must not break the messenger</h3>
 *
 * If the reference data will not parse — a corrupt asset, a bad merge — the
 * right outcome is an app with no scanner, not an app that will not launch.
 * A messenger that fails to start is a total loss for the user; a messenger
 * without a scanner is the messenger they had before we shipped. So install()
 * swallows everything and records that it failed, and {@link #isInstalled}
 * lets the UI ask.
 */
public final class ScannerBootstrap {

    private static volatile boolean installed;
    private static volatile String failure;

    private ScannerBootstrap() {
    }

    public static void install(Context context) {
        try {
            AssetManager assets = context.getAssets();
            // Parsed once for every account and every mode: the brand index and
            // the magic-byte table cost real time to build and depend on
            // neither.
            EngineFactory.ReferenceData data = EngineFactory.prepare(
                    asset(assets, "magic-bytes.json"),
                    asset(assets, "psl-subset.txt"),
                    asset(assets, "uz-brand-allowlist.json"));

            // Local-only, by decision, not by accident. No base URL and no
            // token source are handed to the factory, which makes the backend
            // branch in EngineFactory unreachable: no device registration, no
            // /v1/verdict lookup, no hash of any file ever leaving the phone.
            // Every verdict below comes from the on-device analysers alone.
            // The consequence is accepted product behaviour: the local policy
            // never says CLEAN about an installer, so an APK stays red for
            // good and installing one always takes the deliberate two-step
            // override.

            // One gate per account. Telegram supports several signed-in
            // accounts at once, and a verdict cache shared between them would
            // leak the fact that a file was seen on one into the other.
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                ScanGate.install(account, context, mode ->
                        EngineFactory.engineFor(data, mode));
                LinkGate.install(account, mode ->
                        EngineFactory.linkScannerFor(data, mode));
            }

            installed = true;
        } catch (Throwable t) {
            // Deliberately broad. Whatever went wrong, the messenger keeps
            // working — see the class comment.
            installed = false;
            failure = t.getClass().getSimpleName() + ": " + t.getMessage();
            android.util.Log.e("jac", "scanner failed to start: " + failure, t);
        }
    }

    public static boolean isInstalled() {
        return installed;
    }

    /** Why the scanner is not running, or null. For the settings screen. */
    public static String getFailure() {
        return failure;
    }

    private static String asset(AssetManager assets, String name) throws Exception {
        InputStream stream = assets.open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            stream.close();
        }
    }

}
