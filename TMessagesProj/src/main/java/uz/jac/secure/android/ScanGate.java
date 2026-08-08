package uz.jac.secure.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import uz.jac.secure.core.engine.ScanEngine;
import uz.jac.secure.core.model.FileMeta;
import uz.jac.secure.core.model.ScanMode;
import uz.jac.secure.core.model.ScanResult;
import uz.jac.secure.core.model.Verdict;

/**
 * The interception point between Telegram's download machinery and the scanner.
 *
 * Called from the patched {@code FileLoader.didFinishLoadingFile} (see
 * patches/0001-jac-scan-gate.patch). The contract with the caller is small and
 * strict:
 *
 *   * {@link #onFileLoaded} ALWAYS invokes its continuation, exactly once. It
 *     delays the announcement of a finished download; it never drops it. A
 *     scanner that can silently swallow a file would break Telegram ToS 1.3
 *     ("all basic features must work exactly as in the official client") and,
 *     more practically, would look like a bug in the messenger.
 *
 *   * A MALICIOUS verdict moves the file into app-private quarantine BEFORE the
 *     continuation runs, and the path passed onward is the quarantined one, so
 *     no other component ever learns the original location or can export it.
 *
 *   * Secret chats take the offline path: {@link ScanMode#SECRET_CHAT} keeps
 *     every hash and URL on the device and writes nothing to the cache
 *     (ToS 1.4/1.5).
 *
 * Scanning runs on a small bounded executor, never on the file-loader thread —
 * blocking that thread would stall every other download in the queue.
 */
public final class ScanGate {

    /** Continuation supplied by the patched call site. */
    public interface Continuation {
        void onScanned(File file);
    }

    private static final Map<Integer, ScanGate> INSTANCES = new ConcurrentHashMap<>();

    private final ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScanStateStore stateStore;
    private final QuarantineStore quarantine;
    private final ScanEngineProvider engines;

    /**
     * Supplies a configured engine per scan mode. Injected rather than
     * constructed here so the gate can be unit-tested without an Android
     * context, and so a mode change cannot accidentally reuse a
     * network-enabled engine inside a secret chat.
     */
    public interface ScanEngineProvider {
        ScanEngine engineFor(ScanMode mode);
    }

    ScanGate(QuarantineStore quarantine, ScanStateStore stateStore, ScanEngineProvider engines) {
        this.quarantine = quarantine;
        this.stateStore = stateStore;
        this.engines = engines;
        this.executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "jac-scan");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                t.setDaemon(true);
                return t;
            }
        });
    }

    public static ScanGate getInstance(int account) {
        ScanGate gate = INSTANCES.get(account);
        if (gate == null) {
            throw new IllegalStateException(
                    "ScanGate not initialised for account " + account + "; call ScanGate.install() from ApplicationLoader");
        }
        return gate;
    }

    /** Wire up from {@code ApplicationLoader.onCreate}, once per account. */
    public static void install(int account, Context context, ScanEngineProvider engines) {
        INSTANCES.put(account, new ScanGate(new QuarantineStore(context), new ScanStateStore(), engines));
    }

    /**
     * Entry point from the patched FileLoader.
     *
     * @param streamingSha256 digest accumulated during download, or null if the
     *                        writes were not sequential and the file must be
     *                        hashed from disk instead.
     */
    public void onFileLoaded(
            String fileName,
            File finalFile,
            Object parentObject,
            int fileType,
            String streamingSha256,
            Continuation continuation
    ) {
        // Unconditional: the first question when the scanner appears to do
        // nothing is whether this method was reached at all. Note that a file
        // sent FROM this device never appears here — it does not pass through
        // FileLoader — and is scanned by onOutgoingFile instead.
        android.util.Log.d("jac", "onFileLoaded name=" + fileName
                + " exists=" + (finalFile != null && finalFile.exists())
                + " streamingSha=" + (streamingSha256 != null));

        if (finalFile == null || !finalFile.exists()) {
            continuation.onScanned(finalFile);
            return;
        }

        final ScanMode mode = TelegramContext.scanModeFor(parentObject);
        final String declaredMime = TelegramContext.declaredMimeOf(parentObject);
        final String displayName = fileName != null ? fileName : finalFile.getName();
        final long dialogId = TelegramContext.dialogIdOf(parentObject);

        stateStore.setScanning(finalFile.getAbsolutePath(), dialogId);

        executor.execute(() -> {
            File result = finalFile;
            ScanResult scan;
            try {
                FileMeta meta = new FileMeta(displayName, declaredMime, finalFile.length());
                scan = engines.engineFor(mode).scan(finalFile, meta, streamingSha256);
            } catch (Throwable t) {
                // The scanner must never be able to lose a download. Any failure
                // degrades to "unknown" and the file is released — the alternative
                // is a messenger where files sometimes silently never arrive.
                scan = ScanResult.Companion.unknown("scan failed: " + t.getClass().getSimpleName());
            }

            if (scan.getVerdict() == Verdict.MALICIOUS) {
                result = quarantine.quarantine(finalFile, scan.getSha256());
            }

            stateStore.setResult(result.getAbsolutePath(), scan, quarantine.isQuarantined(result));
            if (!result.getAbsolutePath().equals(finalFile.getAbsolutePath())) {
                stateStore.repoint(dialogId, finalFile.getAbsolutePath(), result.getAbsolutePath());
                // Keep the ORIGINAL path resolving too. It used to be forgotten
                // here, which read as tidy and silently broke the only case that
                // matters: quarantine moves the file, but nothing writes the new
                // location back into the message, so FileLoader.getPathToMessage()
                // still answers with the original cache path — the only key the
                // bubble ever has. Dropping it meant a MALICIOUS file showed no
                // verdict at all while clean and suspicious ones showed theirs.
                stateStore.alias(finalFile.getAbsolutePath(), result.getAbsolutePath());
            }

            final File delivered = result;
            mainHandler.post(() -> continuation.onScanned(delivered));
        });
    }

    /**
     * Entry point for a file being sent FROM this device.
     *
     * <h3>Why this exists</h3>
     *
     * {@link #onFileLoaded} sits in {@code FileLoader}, which only ever runs
     * for downloads. A file the user attaches from their gallery or file
     * manager already exists on disk, so it never passes through that path and
     * was never scanned. The gap was invisible in testing, because the natural
     * way to test is to receive a file.
     *
     * Two things came through it, and the second is the one that matters:
     *
     * <ul>
     *   <li>A file that arrived by Bluetooth, WhatsApp or a browser download,
     *       then forwarded on from the gallery. It is not a Telegram download
     *       at any point, so nothing else in this app will ever look at it.</li>
     *   <li><b>Re-sharing.</b> This is how the fake-bank campaigns actually
     *       spread here: a victim is told to forward the installer to their
     *       contacts, and they do, from a real account their contacts trust. A
     *       scanner that checks only what you receive warns the first person in
     *       every chain and nobody after them.</li>
     * </ul>
     *
     * <h3>How it differs from the download path</h3>
     *
     * <b>It never quarantines.</b> The download path moves a malicious file
     * into app-private storage, which is safe because we put it on disk in the
     * first place. This file is the user's own — it may be their only copy, it
     * may be in a directory another app is watching, and it may not be malware
     * at all but a sample someone is deliberately carrying. Moving or deleting
     * it would be us destroying a user's data on a heuristic. So this path
     * reports, and the decision about whether to send is theirs.
     *
     * <b>It never blocks the send.</b> The continuation always runs, exactly as
     * on the download path and for the same ToS 1.3 reason.
     *
     * @param dialogId conversation the file is being sent to; decides the scan
     *                 mode, so a file sent into a secret chat is hashed on the
     *                 device and nowhere else
     */
    public void onOutgoingFile(
            String fileName,
            File file,
            long dialogId,
            String declaredMime,
            Continuation continuation
    ) {
        if (file == null || !file.exists()) {
            continuation.onScanned(file);
            return;
        }

        final ScanMode mode = TelegramContext.scanModeForDialog(dialogId);
        final String displayName = fileName != null ? fileName : file.getName();
        final String path = file.getAbsolutePath();

        stateStore.setScanning(path, dialogId);

        executor.execute(() -> {
            ScanResult scan;
            try {
                FileMeta meta = new FileMeta(displayName, declaredMime, file.length());
                // No streaming digest: nothing streamed. The engine hashes the
                // finished file, which is what the null third argument means.
                scan = engines.engineFor(mode).scan(file, meta, null);
            } catch (Throwable t) {
                scan = ScanResult.Companion.unknown("scan failed: " + t.getClass().getSimpleName());
            }

            // quarantined=false, unconditionally. See above: this file is the
            // user's, and the verdict is advice about it, not custody of it.
            stateStore.setResult(path, scan, false);

            mainHandler.post(() -> continuation.onScanned(file));
        });
    }

    /**
     * May this file be opened, shared, or copied out to MediaStore?
     *
     * Consulted from the share sheet, the "save to gallery" path and the
     * intent-launching code. Returns false while a scan is in flight and for
     * anything still in quarantine — the override goes through
     * {@link #releaseAfterOverride}, not through this method.
     */
    public boolean isExportAllowed(File file) {
        if (file == null) {
            return false;
        }
        if (quarantine.isQuarantined(file)) {
            return false;
        }
        ScanStateStore.State state = stateStore.get(file.getAbsolutePath());
        if (state == null) {
            return true; // not a scanned download (outgoing file, thumbnail, sticker)
        }
        if (state.scanning) {
            return false;
        }
        // An overridden file stays MALICIOUS on purpose - ScanStateStore keeps
        // the verdict so the bubble goes on warning about it. So the override
        // flag, not the verdict, is what decides access here: testing the
        // verdict alone would leave the file blocked forever and send the user
        // back into the same warning dialog they just passed twice.
        return state.overridden || state.verdict != Verdict.MALICIOUS;
    }

    /**
     * Release a quarantined file after the user has passed BOTH steps of the
     * warning dialog.
     *
     * The two-step requirement is not decoration. A single tap is something
     * people do reflexively to dismiss a dialog; the second, differently-worded
     * confirmation is what makes the decision deliberate. Callers must not
     * invoke this from a single tap.
     */
    public File releaseAfterOverride(File quarantined, File destination, boolean firstConfirm, boolean secondConfirm) {
        if (!firstConfirm || !secondConfirm) {
            throw new IllegalArgumentException("both confirmation steps are required to leave quarantine");
        }
        File released = quarantine.release(quarantined, destination);
        stateStore.markOverridden(released.getAbsolutePath());
        return released;
    }

    public ScanStateStore getStateStore() {
        return stateStore;
    }

    public QuarantineStore getQuarantine() {
        return quarantine;
    }
}
