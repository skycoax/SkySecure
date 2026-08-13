package uz.jac.secure.android;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starts a scan for a document the user is looking at, without waiting to be
 * asked.
 *
 * <h3>The gap this closes</h3>
 *
 * Until this existed, {@link ScanGate#onFileLoaded} was the only way a file
 * ever got scanned, and it fires at exactly one moment: the last byte of a
 * download landing. Telegram does not auto-download documents, so for the file
 * types this product is actually about — an APK claiming to be a bank, an
 * archive, a PDF — the sequence was:
 *
 * <ol>
 *   <li>The file arrives in the chat. Nothing is downloaded, so nothing is
 *       scanned, so the bubble shows no verdict.</li>
 *   <li>The user reads a bubble with no warning on it and taps download.</li>
 *   <li>The scan finally runs.</li>
 * </ol>
 *
 * Step 2 is the whole problem. An unlabelled bubble does not read as "not yet
 * checked"; it reads as "checked, nothing found". The scanner was silent at the
 * only moment the user was deciding.
 *
 * <h3>What it does</h3>
 *
 * On binding a document message it takes one of two paths, and both publish a
 * state immediately so the bubble stops being blank:
 *
 * <ul>
 *   <li><b>Bytes already on disk</b> — from an earlier session, an earlier
 *       build, or a manual download — go straight to
 *       {@link ScanGate#ensureScanned}. No network at all.</li>
 *   <li><b>Nothing on disk yet</b>: publish what the filename alone says
 *       ({@link ScanGate#previewByName}, which catches the right-to-left
 *       override and double-extension tricks with zero bytes), then start the
 *       download so the full scan can run.</li>
 * </ul>
 *
 * <h3>Why it hangs off message binding</h3>
 *
 * Binding is the one event that means "the user can see this". It fires when a
 * chat opens, when the list scrolls, and when a message arrives, so a single
 * hook covers every way a document can come into view — no separate sweep to
 * keep in step with the message list, and nothing scanned for a chat nobody
 * opened.
 *
 * The cost of that choice is that binding runs constantly, so every method here
 * must be cheap and idempotent on repeat calls. It is: two set lookups on the
 * common path, and the work itself is posted off the layout pass.
 */
public final class ScanAutoStart {

    /**
     * Documents a download has been requested for.
     *
     * FileLoader tolerates a duplicate request, but binding fires many times per
     * second while scrolling and each call would walk its operation tables.
     * Process-lifetime only, deliberately: a fresh start should be willing to
     * retry a download that died with the last one.
     */
    private static final Set<String> requestedDownload = ConcurrentHashMap.newKeySet();

    /**
     * Documents whose auto-download failed. Not tried again this session.
     *
     * This is a cycle breaker, not a policy. {@link #onDownloadFailed} retracts
     * the provisional state so the spinner stops, and retracting it makes the
     * cell rebind — which lands straight back in {@link #ensure} with no state
     * on record and no file on disk, the exact condition that starts a
     * download. Without a memory of the failure that is a loop that re-requests
     * a dead file for as long as the chat is open.
     *
     * <p>Process-lifetime, like {@link #requestedDownload}: the usual cause is
     * the network, and a restart is a reasonable moment to assume it came back.
     * The user tapping download themselves is unaffected — that path never
     * comes through here, and it scans normally when the bytes land.
     */
    private static final Set<String> failedDownload = ConcurrentHashMap.newKeySet();

    private ScanAutoStart() {
    }

    /**
     * @param plannedFile where this message's document lives or WILL live —
     *                    {@code FileLoader.getPathToMessage} answers with the
     *                    computed cache path whether or not the bytes are there
     *                    yet, which is what makes it usable as a key before the
     *                    download starts.
     */
    public static void ensure(int account, MessageObject message, File plannedFile) {
        if (!ScannerBootstrap.isInstalled() || message == null || plannedFile == null) {
            return;
        }
        TLRPC.Document document = message.getDocument();
        if (document == null) {
            return;
        }
        // Self-destructing media is left alone. Pulling it down on our own
        // initiative would spend the user's one viewing on a scan they did not
        // ask for, and the file is unavailable afterwards either way.
        if (message.isSecretMedia() || (message.messageOwner != null && message.messageOwner.destroyTime != 0)) {
            return;
        }
        // Still uploading: the bytes on disk are the user's own source file, and
        // ScanGate.onOutgoingFile has already looked at it.
        if (message.isSending()) {
            return;
        }

        final String displayName = name(message, document);

        // Off the layout pass. ensure() is called from setMessageContent, and
        // starting a download inside a measure/layout traversal is how a frame
        // drop becomes a stutter on every scroll past a document.
        AndroidUtilities.runOnUIThread(() -> {
            try {
                ScanGate gate = ScanGate.getInstance(account);

                // Anything already on record is left alone, and this guard is
                // load-bearing rather than an optimisation.
                //
                // Quarantine MOVES a malicious file out of the cache. So the
                // sequence "scan finds malware -> file is moved -> verdict
                // published -> cell rebinds -> we run again" arrives here with a
                // planned path that no longer exists — which looks exactly like
                // a file that was never downloaded. Without this check the next
                // two lines would overwrite the MALICIOUS verdict with a fresh
                // "scanning" state and re-download the very file we had just
                // taken away from the user.
                // "Not checked yet" is the one state that must NOT block what
                // comes next. It is a placeholder meaning we know nothing, and
                // it is written for every installer the moment it is seen -- so
                // treating it like a verdict left the file marked amber
                // forever: the bytes arrived, the cell rebound, and this guard
                // sent it straight back out before ensureScanned could run. The
                // file downloaded and was never checked.
                uz.jac.secure.android.ScanStateStore.State known =
                        gate.getStateStore().get(plannedFile.getAbsolutePath());
                if (known != null && !known.unchecked) {
                    return;
                }
                if (failedDownload.contains(account + ":" + plannedFile.getAbsolutePath())) {
                    return;
                }

                if (plannedFile.exists() && plannedFile.length() > 0) {
                    if (known != null && known.unchecked) {
                        // The bytes have arrived for a file we had marked
                        // "not checked". This transition must be forced, not
                        // requested: ensureScanned() is guarded by a
                        // process-wide set of paths it has already looked at,
                        // and that set does not care that the earlier attempt
                        // happened when there was no file on disk. So the file
                        // downloaded, the guard said "already handled", and the
                        // bubble sat on "not checked yet" forever -- the exact
                        // thing the amber marker was supposed to prevent.
                        gate.rescan(displayName, plannedFile, message);
                    } else {
                        gate.ensureScanned(displayName, plannedFile, message);
                    }
                    return;
                }

                // Nothing on disk. Mark an installer NOW, before deciding
                // whether we are allowed to fetch it.
                //
                // This is the case the product exists for and the one it was
                // getting wrong: an APK arrives, nothing is downloaded, so
                // nothing was said -- and an unmarked bubble reads as "checked,
                // fine". The user installs it. Whatever the network policy, the
                // fact that this file can install an app is known from its name
                // alone and costs nothing to say.
                if (isInstaller(displayName, message)) {
                    gate.markUnchecked(plannedFile, message);
                }

                // The download, unlike the name check, spends the user's money.
                // Whether that is allowed right now is ScanSettings' question,
                // not ours — and it has to be asked BEFORE the name preview,
                // because the answer decides whether the preview is allowed to
                // put a spinner on the bubble.
                final boolean willFetch = ScanSettings.mayFetchForScan(message);

                // Always, whatever the answer. Reading the name costs no
                // network and no bytes, so there is no setting that should be
                // able to switch it off — and it is the check that catches the
                // right-to-left override, which is the whole attack in the case
                // this product exists for. What the answer changes is only
                // whether a quiet result leaves "checking..." on screen.
                gate.previewByName(displayName, plannedFile, message, willFetch);

                if (!willFetch) {
                    return;
                }

                if (requestedDownload.add(account + ":" + plannedFile.getAbsolutePath())) {
                    // PRIORITY_LOW: this download is our idea, not the user's.
                    // Anything they tapped themselves must not queue behind it.
                    FileLoader.getInstance(account).loadFile(document, message, FileLoader.PRIORITY_LOW, 0);
                }
            } catch (Throwable t) {
                // Same rule as everywhere else in this layer: a scanner that
                // cannot start is an app without a scanner, not a crash.
                if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
                    android.util.Log.d("jac", "auto-start failed for " + displayName + ": " + t);
                }
            }
        });
    }

    /**
     * A download reported failure. If it was one of ours, undo the optimism.
     *
     * {@link #ensure} publishes "scanning…" as soon as it asks for the bytes,
     * which is honest while they are on their way and a lie the moment the
     * transfer dies. Clearing the request mark as well as the state is what
     * makes the next bind willing to try again — a failure here is usually the
     * network, and the network comes back.
     *
     * <p>Called for every failed download, including the many this class never
     * asked for; {@code requestedDownload.remove} returning false is how those
     * are recognised and ignored.
     */
    public static void onDownloadFailed(int account, File plannedFile) {
        if (!ScannerBootstrap.isInstalled() || plannedFile == null) {
            return;
        }
        final String key = account + ":" + plannedFile.getAbsolutePath();
        if (!requestedDownload.remove(key)) {
            return;
        }
        // Recorded BEFORE the retraction. Retracting makes the cell rebind, and
        // that rebind must find the failure already on record or it will start
        // the same download again — see failedDownload.
        failedDownload.add(key);
        try {
            ScanGate.getInstance(account).forgetPreview(plannedFile);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Can this file install an app, judged from the name and declared type?
     *
     * <p>Deliberately crude and deliberately offline. It runs before a byte is
     * fetched, so there is nothing to inspect but the name and the MIME type
     * the sender declared -- and both are attacker-controlled. That is fine
     * here: a false positive costs an amber marker on a harmless file, and the
     * miss it is guarding against costs the user their bank account.
     *
     * <p>Split APK bundles count. They are how a real installer is delivered
     * when the app is large, and they install exactly the same way.
     */
    private static boolean isInstaller(String fileName, MessageObject message) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        if (name.endsWith(".apk") || name.endsWith(".apks")
                || name.endsWith(".xapk") || name.endsWith(".apkm")) {
            return true;
        }
        TLRPC.Document document = message == null ? null : message.getDocument();
        String mime = document == null || document.mime_type == null
                ? "" : document.mime_type.toLowerCase();
        return mime.contains("android.package-archive");
    }

    private static String name(MessageObject message, TLRPC.Document document) {
        String name = message.getDocumentName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        // No filename attribute. Falling back to the cache name keeps the
        // extension, which is the part the filename heuristics need.
        return FileLoader.getAttachFileName(document);
    }
}
