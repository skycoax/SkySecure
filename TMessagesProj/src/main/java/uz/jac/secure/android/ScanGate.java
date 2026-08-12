package uz.jac.secure.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import uz.jac.secure.core.engine.ScanEngine;
import uz.jac.secure.core.engine.ScanPolicy;
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
 *     every hash and URL on the device and writes nothing to the cache. Not a
 *     rule quoted from anywhere — the API Terms have no clause about secret
 *     chats. It follows from 1.1 ("guard their users' privacy with utmost
 *     care"), and from the plainer fact that a hash of a message the user
 *     believes is end-to-end is still a fingerprint of that message.
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

    /** Source label for a verdict reached from the filename and nothing else. */
    private static final String SOURCE_FILENAME = "local_filename";

    private static final byte[] EMPTY_HEAD = new byte[0];

    /** For entry points where nothing is waiting on the file. */
    private static final Continuation NO_CONTINUATION = file -> {
    };

    private final ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScanStateStore stateStore;
    private final QuarantineStore quarantine;
    private final ScanEngineProvider engines;

    /**
     * Paths a full scan has already been started for.
     *
     * The idempotence guard for {@link #ensureScanned}, whose caller is message
     * binding — a method that runs again on every scroll, every relayout and
     * every recycled cell. Without it a visible file would be re-hashed
     * continuously for as long as it stayed on screen.
     *
     * Deliberately NOT the state store. A scan that failed left a terminal
     * state behind, and treating "has a state" as "needs no scan" is right;
     * treating "has no state" as "scan it again" would retry a hopeless file on
     * every frame. This set answers the narrower question the guard actually
     * needs: have we tried yet.
     */
    private final Set<String> fullyScanned = ConcurrentHashMap.newKeySet();

    /** Paths a filename preview has already run for. See {@link #previewByName}. */
    private final Set<String> previewed = ConcurrentHashMap.newKeySet();

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

        // Claim the path before scanning. This scan is the authoritative one —
        // it has the streaming digest and the complete bytes — and the moment it
        // publishes, the cell rebinds and calls ensureScanned again. Without the
        // mark that call would see a file that now exists on disk and start a
        // second, redundant scan of it.
        fullyScanned.add(finalFile.getAbsolutePath());

        stateStore.setScanning(finalFile.getAbsolutePath(), dialogId);
        scanAndPublish(finalFile, displayName, declaredMime, mode, dialogId, streamingSha256, continuation);
    }

    /**
     * Scan a file that is ALREADY on disk, with no download to hang off.
     *
     * <h3>Why this exists</h3>
     *
     * {@link #onFileLoaded} fires exactly once per download, at the moment the
     * last byte lands. That covers a file the first time it arrives and never
     * again. Everything else was invisible:
     *
     * <ul>
     *   <li>A file downloaded before this build was installed, or before the
     *       scanner was switched on. It sits in the cache with no verdict and
     *       nothing will ever produce one.</li>
     *   <li>A file whose verdict was lost — the state store is rebuilt from disk
     *       at startup, and anything it fails to restore is simply gone.</li>
     *   <li>Any file the user downloaded by tapping it in an earlier session.
     *       Opening the chat again showed a bubble with no verdict, which reads
     *       as "checked, nothing found" and is the most dangerous thing a
     *       scanner can render.</li>
     * </ul>
     *
     * <h3>How it differs from the two existing entry points</h3>
     *
     * It quarantines, exactly like {@link #onFileLoaded} and unlike
     * {@link #onOutgoingFile}. The distinction is ownership, not direction: this
     * file is in Telegram's cache because <em>we</em> put it there, so moving it
     * destroys nothing the user chose to keep. {@code onOutgoingFile} refuses to
     * quarantine because that file is the user's own and may be their only copy.
     *
     * <p>There is no continuation, because nothing is waiting: the bytes are
     * already available to every other component. That is a real weakening
     * against the download path, where the file is not announced until it has a
     * verdict — here it can be opened during the second or two the scan takes.
     * {@link #isExportAllowed} returns false while {@code state.scanning}, which
     * is what closes that window at the call sites that consult it.
     *
     * <p>Idempotent, and it has to be: the call site is message binding, which
     * runs again on every scroll and every relayout.
     */
    public void ensureScanned(String fileName, File file, Object parentObject) {
        if (file == null || !file.exists() || file.length() == 0) {
            return;
        }
        // add() returns false when the path is already present, so the whole
        // method is a no-op from the second call onward. Not a substitute for
        // the state store: a scan that is still in flight has published a
        // "scanning" state, but a scan that FAILED published a terminal one, and
        // re-running it on every bind would retry a hopeless file forever.
        if (!fullyScanned.add(file.getAbsolutePath())) {
            return;
        }

        final ScanMode mode = TelegramContext.scanModeFor(parentObject);
        final String declaredMime = TelegramContext.declaredMimeOf(parentObject);
        final String displayName = fileName != null ? fileName : file.getName();
        final long dialogId = TelegramContext.dialogIdOf(parentObject);

        stateStore.setScanning(file.getAbsolutePath(), dialogId, false);
        // null digest: nothing streamed, so the engine hashes the finished file.
        // That is a second full pass over the bytes, which is why the download
        // path goes to the trouble of hashing on the way past.
        scanAndPublish(file, displayName, declaredMime, mode, dialogId, null, NO_CONTINUATION);
    }

    /**
     * Publish what the FILENAME alone says, for a file that is not on disk yet.
     *
     * <h3>Why a verdict before the bytes</h3>
     *
     * The name is the whole attack in the case this product exists for.
     * {@code hisobot<U+202E>gpj.apk} is stored as an APK and renders as
     * {@code hisobotkpa.jpg}; a double extension, a right-to-left override and a
     * dangerous extension are all decidable with zero bytes downloaded. Waiting
     * for the download to say so means the user stares at an unlabelled bubble
     * for as long as the file takes to arrive — and on a large file that is
     * exactly the window in which they decide to tap it.
     *
     * <p>So this publishes twice. First a plain "scanning" state, synchronously,
     * so the bubble stops looking unexamined the instant the chat opens. Then,
     * off-thread, the name analysis — and only if it found something worth
     * saying. A quiet result deliberately leaves the "scanning" state alone
     * rather than replacing it with a verdict: local heuristics are not allowed
     * to say CLEAN (see {@code ScanPolicy}), and "we read the name and it looked
     * ordinary" must never render as a green tick on a file nobody has opened.
     *
     * <p>Whatever this concludes is provisional. {@link #onFileLoaded} clears the
     * mark and overwrites the state when the bytes land.
     *
     * @param scanWillFollow whether a download has actually been started, so
     *        the bytes really are on their way.
     *
     *        <p>False when {@link ScanSettings} said no — mobile data, or a file
     *        over the fetch cap. The spinner is then a lie with no expiry: it
     *        says work is in progress when nothing is, and it never resolves,
     *        because the thing that would resolve it is the download we just
     *        decided not to start. A 65 MB file sat at "checking..." for as long
     *        as the chat stayed open.
     *
     *        <p>So on false this publishes nothing unless the name itself is
     *        damning. A blank bubble is honest — we have no opinion yet — and
     *        the user's own tap on download is what starts the real scan.
     */
    public void previewByName(String fileName, File plannedFile, Object parentObject, boolean scanWillFollow) {
        if (plannedFile == null || fileName == null || fileName.isEmpty()) {
            return;
        }
        final String path = plannedFile.getAbsolutePath();
        if (!previewed.add(path)) {
            return;
        }
        // A guess from the filename must never displace a verdict reached from
        // the actual bytes — including the quarantine case, where the file has
        // been moved away and its absence must not read as "not downloaded yet".
        if (stateStore.get(path) != null) {
            return;
        }

        final ScanMode mode = TelegramContext.scanModeFor(parentObject);
        final String declaredMime = TelegramContext.declaredMimeOf(parentObject);
        final long dialogId = TelegramContext.dialogIdOf(parentObject);

        // Only claim to be checking when something is actually going to check.
        if (scanWillFollow) {
            stateStore.setScanning(path, dialogId, false);
        }

        executor.execute(() -> {
            try {
                // An empty head is not a degraded call: MagicBytes.check returns
                // UNKNOWN when it cannot sniff anything, so inspectHead reduces
                // cleanly to the filename analysis and reports only name signals.
                FileMeta meta = new FileMeta(fileName, declaredMime, 0L);
                ScanEngine.EarlyWarning early = engines.engineFor(mode).inspectHead(EMPTY_HEAD, meta);
                if (early.getSignals().isEmpty()) {
                    return;
                }
                ScanPolicy.Decision decision = ScanPolicy.INSTANCE.evaluate(early.getSignals());
                if (decision.getVerdict() == Verdict.UNKNOWN) {
                    // Nothing decisive. With a download on its way that means
                    // leaving the "scanning" state in place — see the note
                    // above on why silence must not become a tick. With no
                    // download coming there is no state to leave, and none is
                    // published: the bubble stays blank rather than spinning
                    // for a verdict that was never going to arrive.
                    return;
                }
                stateStore.setResult(
                        path,
                        new ScanResult(decision.getVerdict(), SOURCE_FILENAME, early.getSignals(), null,
                                decision.getDetail()),
                        false);
            } catch (Throwable t) {
                // A failed preview is not worth reporting: the real scan is
                // already on its way and will publish the authoritative answer.
                android.util.Log.d("jac", "name preview failed for " + fileName + ": " + t);
            }
        });
    }

    /**
     * The shared tail of every scan: run the engine, quarantine if the verdict
     * demands it, publish, then hand the file onward.
     *
     * Callers publish the "scanning" state themselves before calling, because
     * only they know whether a preview state is being replaced.
     */
    private void scanAndPublish(
            File file,
            String displayName,
            String declaredMime,
            ScanMode mode,
            long dialogId,
            String streamingSha256,
            Continuation continuation
    ) {
        executor.execute(() -> {
            File result = file;
            ScanResult scan;
            try {
                FileMeta meta = new FileMeta(displayName, declaredMime, file.length());
                scan = engines.engineFor(mode).scan(file, meta, streamingSha256);
            } catch (Throwable t) {
                // The scanner must never be able to lose a download. Any failure
                // degrades to "unknown" and the file is released — the alternative
                // is a messenger where files sometimes silently never arrive.
                scan = ScanResult.Companion.unknown("scan failed: " + t.getClass().getSimpleName());
            }

            if (scan.getVerdict() == Verdict.MALICIOUS) {
                result = quarantine.quarantine(file, scan.getSha256());
            }

            stateStore.setResult(result.getAbsolutePath(), scan, quarantine.isQuarantined(result));
            if (!result.getAbsolutePath().equals(file.getAbsolutePath())) {
                stateStore.repoint(dialogId, file.getAbsolutePath(), result.getAbsolutePath());
                // Keep the ORIGINAL path resolving too. It used to be forgotten
                // here, which read as tidy and silently broke the only case that
                // matters: quarantine moves the file, but nothing writes the new
                // location back into the message, so FileLoader.getPathToMessage()
                // still answers with the original cache path — the only key the
                // bubble ever has. Dropping it meant a MALICIOUS file showed no
                // verdict at all while clean and suspicious ones showed theirs.
                stateStore.alias(file.getAbsolutePath(), result.getAbsolutePath());
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

    /**
     * Accept the risk on a file that was warned about but never quarantined.
     *
     * <h3>Why this is separate from {@link #releaseAfterOverride}</h3>
     *
     * Quarantine is reserved for the unambiguous. A SUSPICIOUS verdict leaves
     * the file exactly where it was — {@code ScanPolicy} deliberately does not
     * take custody of a file it is not certain about — so there is nothing to
     * move and {@code releaseAfterOverride} would be asked to release a file
     * that was never held. It would throw, and the user would press the button
     * that says "install anyway" and watch nothing happen.
     *
     * <p>What still has to happen is the bookkeeping: the state is marked
     * overridden, so the red mark and the interception stop. Not the verdict —
     * that stays, and the bubble goes on saying what the file is.
     *
     * <p>Takes both confirmations for the same reason its sibling does: the
     * signature is the enforcement. A single-argument version of this method
     * would be usable from a single tap.
     */
    public void acceptRisk(File file, boolean firstConfirm, boolean secondConfirm) {
        if (!firstConfirm || !secondConfirm) {
            throw new IllegalArgumentException("both confirmation steps are required to accept the risk");
        }
        if (file == null) {
            return;
        }
        stateStore.markOverridden(file.getAbsolutePath());
    }

    /**
     * Withdraw a provisional state for a file that is never going to arrive.
     *
     * A file we started downloading ourselves publishes "scanning…" the moment
     * it is requested, because from the user's point of view the check has
     * begun. If that download then fails — no network, a dead file reference,
     * the user clearing the queue — the verdict it was standing in for can
     * never be published, and without this the bubble spins forever. A
     * permanent "checking…" is worse than a blank one: it claims work is
     * happening, so the user waits instead of deciding.
     *
     * <p>Refuses to touch a state that has already been decided. By the time a
     * failure is reported the scan may have completed from another path, and
     * erasing a real verdict here would be a silent downgrade.
     */
    public void forgetPreview(File plannedFile) {
        if (plannedFile == null) {
            return;
        }
        final String path = plannedFile.getAbsolutePath();
        ScanStateStore.State state = stateStore.get(path);
        if (state == null || !state.scanning) {
            return;
        }
        stateStore.retract(path);
        // Both guards released, so a later attempt is free to try again.
        previewed.remove(path);
        fullyScanned.remove(path);
    }

    public ScanStateStore getStateStore() {
        return stateStore;
    }

    public QuarantineStore getQuarantine() {
        return quarantine;
    }
}
