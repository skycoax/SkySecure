package uz.jac.secure.android;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import uz.jac.secure.core.model.ScanResult;
import uz.jac.secure.core.model.Verdict;

/**
 * Per-file scan state, and the notifications the message bubble listens to.
 *
 * Drives the bubble states from the spec:
 *   scanning…  -> {@link State#scanning}
 *   clean      -> verdict CLEAN
 *   suspicious -> verdict SUSPICIOUS
 *   dangerous  -> verdict MALICIOUS and {@link State#quarantined}
 *
 * UNKNOWN is deliberately its own state rather than being folded into "clean".
 * It means no source had an opinion, and a green tick there would be a claim we
 * cannot support — in a secret chat, where only local heuristics run, it is the
 * normal outcome for every safe file, and the UI shows a neutral "checked on
 * this device" marker instead.
 *
 * Keyed by absolute path, which is stable for the lifetime of a downloaded
 * file and is what the UI already has in hand.
 */
public final class ScanStateStore {

    public static final class State {
        public final boolean scanning;
        public final Verdict verdict;
        /**
         * The scanner's English explanation. A fallback only — see
         * {@link ScanFinding}. The UI localises from {@link #findings} and
         * reaches for this string only when no template matches.
         */
        public final String detail;
        /**
         * Which layer produced the verdict: {@code local_only} in a secret
         * chat, {@code local_heuristics}, or the name of a reputation source.
         *
         * The UI needs this to tell two identical-looking verdicts apart.
         * UNKNOWN from a secret chat means "we checked here and asked nobody",
         * which is the normal, expected outcome for a safe file and should read
         * as reassurance; UNKNOWN from a normal chat means a reputation source
         * was asked and had nothing to say, which is genuinely less certain.
         */
        public final String source;
        public final boolean quarantined;
        public final boolean overridden;
        public final List<ScanFinding> findings;

        /**
         * Where the file actually is now.
         *
         * Not the same as the key it was looked up by. A quarantined file is
         * reachable under its original path (see {@link #alias}) because that
         * is the only path a message can produce, but releasing it needs the
         * path it was MOVED to. Without this the "Open anyway" button hands
         * {@code releaseAfterOverride} the original path — a file that is no
         * longer there — and the release silently does nothing, which is the
         * one outcome worse than not offering the button at all.
         */
        public final String filePath;

        State(boolean scanning, Verdict verdict, String detail, String source,
              boolean quarantined, boolean overridden, List<ScanFinding> findings, String filePath) {
            this.scanning = scanning;
            this.verdict = verdict;
            this.detail = detail;
            this.source = source;
            this.quarantined = quarantined;
            this.overridden = overridden;
            this.findings = findings != null ? findings : Collections.<ScanFinding>emptyList();
            this.filePath = filePath;
        }

        public boolean has(String signalCode) {
            for (ScanFinding finding : findings) {
                if (finding.code.equals(signalCode)) {
                    return true;
                }
            }
            return false;
        }

        public ScanFinding find(String signalCode) {
            for (ScanFinding finding : findings) {
                if (finding.code.equals(signalCode)) {
                    return finding;
                }
            }
            return null;
        }
    }

    public interface Listener {
        void onScanStateChanged(String path, State state);
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /**
     * Most recently scanned file per dialog, so the chat list can find a
     * verdict without walking messages.
     *
     * DialogCell redraws on every list scroll and has no file path in hand —
     * only a dialog id and a cached preview string. Resolving "does this
     * conversation contain something blocked?" by searching {@link #states} on
     * each bind would be a scan of the whole map per row per frame. One extra
     * index makes it a lookup.
     *
     * Deliberately keyed to the LAST scanned file rather than the worst one.
     * The chat list mirrors what the preview line shows, which is the latest
     * message; a row that reported an older, worse file would point at a
     * message the user cannot see.
     */
    private final Map<Long, String> latestByDialog = new ConcurrentHashMap<>();

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public State get(String path) {
        return states.get(path);
    }

    /**
     * Record that a quarantined file used to live at {@code originalPath}.
     *
     * <h3>Why this exists, and why forgetting the old path was a bug</h3>
     *
     * Quarantine MOVES the file. The scan state was then filed under the new,
     * app-private path and the old key was dropped — which looked tidy and was
     * exactly wrong, because nothing writes the new path back into the message.
     * {@code FileLoader.getPathToMessage()} still answers with the original
     * cache path, so that is the only key the message bubble ever has.
     *
     * The consequence was the worst possible shape of bug: clean, suspicious
     * and still-scanning verdicts all rendered fine, and the MALICIOUS
     * case — the one the product exists for — rendered nothing at all. A
     * quarantined file simply refused to open, with no explanation anywhere.
     *
     * So the state stays reachable under both keys. The alias is not a copy:
     * both map to the same State instance, so an override published later is
     * visible through either path.
     */
    void alias(String originalPath, String quarantinedPath) {
        if (originalPath == null || quarantinedPath == null || originalPath.equals(quarantinedPath)) {
            return;
        }
        State state = states.get(quarantinedPath);
        if (state != null) {
            states.put(originalPath, state);
        }
    }

    /** State of the most recently scanned file in this conversation, or null. */
    public State forDialog(long dialogId) {
        String path = latestByDialog.get(dialogId);
        return path == null ? null : states.get(path);
    }

    /** Path behind {@link #forDialog}, for a row that needs to act on the file. */
    public String pathForDialog(long dialogId) {
        return latestByDialog.get(dialogId);
    }

    /**
     * Put back a state loaded from disk at startup.
     *
     * Without this every verdict died with the process, so reopening the app
     * re-scanned files it had already judged - wasted work, and worse, a
     * quarantined file briefly looked unexamined again. Deliberately does not
     * notify listeners: this is restoring what was already true, not news.
     */
    public void restore(String path, Verdict verdict, String detail, String source, boolean quarantined, boolean overridden) {
        if (path == null || path.isEmpty()) {
            return;
        }
        states.put(path, new State(false, verdict, detail, source, quarantined, overridden, null, path));
    }

    /** Snapshot for persisting; iteration order is unspecified. */
    public Map<String, State> snapshot() {
        return new java.util.HashMap<>(states);
    }

    /**
     * Cheap "is there anything to ask about at all?" check.
     *
     * The UI consults this on every bubble draw, and in the overwhelmingly
     * common case nothing has been scanned yet — so callers can bail out here
     * before doing the comparatively expensive work of resolving a message to
     * a file path.
     */
    public boolean isEmpty() {
        return states.isEmpty();
    }

    void setScanning(String path, long dialogId) {
        if (dialogId != 0) {
            latestByDialog.put(dialogId, path);
        }
        publish(path, new State(true, Verdict.UNKNOWN, null, null, false, false, null, path));
    }

    void setResult(String path, ScanResult result, boolean quarantined) {
        publish(path, new State(false, result.getVerdict(), result.getDetail(), result.getSource(),
                quarantined, false, ScanFinding.of(result), path));
    }

    void markOverridden(String path) {
        State previous = states.get(path);
        Verdict verdict = previous != null ? previous.verdict : Verdict.MALICIOUS;
        String detail = previous != null ? previous.detail : null;
        String source = previous != null ? previous.source : null;
        List<ScanFinding> findings = previous != null ? previous.findings : null;
        // The verdict is preserved after an override. The file is accessible,
        // but it is still known-bad and the bubble keeps saying so — hiding the
        // warning once the user has clicked past it would be the wrong lesson.
        State updated = new State(false, verdict, detail, source, false, true, findings,
                previous != null ? previous.filePath : path);

        // Every key that pointed at the old state, not just the one passed in.
        // A quarantined file is reachable under two paths (see alias()), and
        // updating only one would leave the bubble — which looks the file up by
        // the original path — still showing a live block on a file the user has
        // just been allowed to open, with a "Keep me safe" button that no longer
        // does anything.
        if (previous != null) {
            for (Map.Entry<String, State> entry : states.entrySet()) {
                if (entry.getValue() == previous) {
                    publish(entry.getKey(), updated);
                }
            }
        }
        publish(path, updated);
    }

    /**
     * Re-point a dialog at a file that has moved.
     *
     * Quarantine renames the file, so the path the dialog index recorded when
     * the scan started no longer exists. Without this the chat list would keep
     * resolving a blocked file to a state that had just been forgotten, and the
     * row would silently lose its marker at the exact moment it earned one.
     */
    void repoint(long dialogId, String oldPath, String newPath) {
        if (dialogId == 0 || newPath == null) {
            return;
        }
        if (oldPath == null || oldPath.equals(latestByDialog.get(dialogId))) {
            latestByDialog.put(dialogId, newPath);
        }
    }

    void forget(String path) {
        states.remove(path);
    }

    private void publish(String path, State state) {
        states.put(path, state);
        for (Listener listener : listeners) {
            listener.onScanStateChanged(path, state);
        }
    }
}
