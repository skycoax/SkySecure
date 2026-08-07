package uz.jac.secure.android;

import android.os.Handler;
import android.os.Looper;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import uz.jac.secure.core.engine.LinkScanner;
import uz.jac.secure.core.model.ScanMode;

/**
 * The interception point for a tapped link — and the place the chat's identity
 * finally reaches.
 *
 * <h3>The bug this class is the fix for</h3>
 *
 * Link scanning was reached from the tap handler, which knew a URL and nothing
 * else. {@link TelegramContext#scanModeFor} fails closed by design: no message
 * context means secret chat, which means offline-only. Correct rule, but with
 * no context ever being passed, <em>every</em> link took the offline path.
 *
 * The visible symptom was nothing at all — no error, no log line. Links in
 * ordinary cloud chats were still checked for homographs, typosquats and
 * confusables, so the feature looked like it worked. What silently never ran
 * was everything that needs the server: shortener expansion, the blocklist,
 * VirusTotal. A `t.me`-shortened link to a known phishing domain sailed
 * through, because expanding it is a network operation and the network was
 * switched off by a default meant for secret chats.
 *
 * A fail-closed default is right, and it is exactly the kind of default that
 * hides its own misfiring — the safe branch is also the quiet one. So the
 * context is now a required argument of {@link #check}: there is no overload
 * that omits it, and a call site that has no chat has to say so in writing.
 *
 * <h3>What the modes actually are</h3>
 *
 * One {@link LinkScanner} per mode, built once, held for the life of the
 * process. The secret-chat scanner is constructed with no backend at all — not
 * a backend told to stay quiet — so no edit to this class can turn a
 * secret-chat link into a network request. Same rule as {@code ScanGate}, for
 * the same reason.
 */
public final class LinkGate {

    /** Delivered on the UI thread. */
    public interface Callback {
        void onLinkChecked(LinkScanner.LinkVerdict verdict);
    }

    private static final Map<Integer, LinkGate> INSTANCES = new ConcurrentHashMap<>();

    private final Map<ScanMode, LinkScanner> scanners = new EnumMap<>(ScanMode.class);
    private final ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Builds a scanner per mode; injected so tests need no Android context. */
    public interface LinkScannerProvider {
        LinkScanner scannerFor(ScanMode mode);
    }

    LinkGate(LinkScannerProvider provider) {
        for (ScanMode mode : ScanMode.values()) {
            scanners.put(mode, provider.scannerFor(mode));
        }
        this.executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "jac-link");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                t.setDaemon(true);
                return t;
            }
        });
    }

    public static void install(int account, LinkScannerProvider provider) {
        INSTANCES.put(account, new LinkGate(provider));
    }

    public static LinkGate getInstance(int account) {
        LinkGate gate = INSTANCES.get(account);
        if (gate == null) {
            throw new IllegalStateException(
                    "LinkGate not initialised for account " + account + "; call LinkGate.install() from ApplicationLoader");
        }
        return gate;
    }

    /**
     * Check a link the user is about to open.
     *
     * @param parentObject the {@code MessageObject} the link was tapped in.
     *                     Required, and required to be the real one: this is
     *                     the whole subject of this class. Pass null only from
     *                     a call site that genuinely has no message, and
     *                     understand that null means offline-only.
     * @param displayText  what the message showed as the link's text, when that
     *                     differs from the href. Carried through to the
     *                     interstitial, whose central claim is "the message said
     *                     X, this goes to Y" — a claim it cannot make without
     *                     the X.
     */
    public void check(String url, Object parentObject, String displayText, Callback callback) {
        checkInMode(url, TelegramContext.scanModeFor(parentObject), displayText, callback);
    }

    /**
     * Overload for a link with a conversation but no message — a chat's pinned
     * header, a bio, a link in a channel description.
     *
     * Weaker than {@link #check}: a dialog id cannot reveal that a particular
     * message holds self-destructing media, so prefer the message form whenever
     * one is available.
     */
    public void checkInDialog(String url, long dialogId, String displayText, Callback callback) {
        checkInMode(url, TelegramContext.scanModeForDialog(dialogId), displayText, callback);
    }

    private void checkInMode(String url, ScanMode mode, String displayText, Callback callback) {
        LinkScanner scanner = scanners.get(mode);
        executor.execute(() -> {
            LinkScanner.LinkVerdict verdict;
            try {
                verdict = scanner.scan(url, false, displayText);
            } catch (Throwable t) {
                // A link check that throws must not swallow the tap. The user
                // asked to open something; failing to reach a verdict is not
                // grounds for doing nothing, and a messenger whose links
                // sometimes just do not respond is a broken messenger.
                verdict = null;
            }
            final LinkScanner.LinkVerdict delivered = verdict;
            mainHandler.post(() -> callback.onLinkChecked(delivered));
        });
    }

    /**
     * Offline pre-screen of the links in an incoming message, for the
     * chat-list marker.
     *
     * <h3>Why this is a second, weaker entry point</h3>
     *
     * {@link #check} runs when a link is tapped, which is too late to put a
     * marker on a chat-list row: the row is drawn for conversations the user
     * has not opened, which is exactly when the warning is worth most.
     *
     * The obvious fix — check every incoming URL as it arrives — is not
     * available to us. That would send the address of every link anyone sends
     * the user to our server, unprompted, which reconstructs precisely the
     * browsing history {@code docs/PRIVACY.md} promises we do not keep. The
     * on-tap call is defensible because the user is about to visit the address
     * anyway; a background sweep is not.
     *
     * So the split is by capability, not by policy toggle: pre-screening runs
     * <b>only</b> the offline half — punycode, mixed scripts, confusable
     * folding, typosquat distance against the brand allowlist — and the
     * server-side half (shortener expansion, blocklist, VirusTotal) still
     * happens on tap and only on tap.
     *
     * It uses the secret-chat scanner to do it. Not as a trick: that instance
     * is constructed with no backend object at all, so "offline" here is a
     * structural property of the object rather than an argument some future
     * edit could get wrong.
     *
     * The badge this produces is therefore weaker than the interstitial's
     * verdict, and never says "known scam" — only "worth a look". A homograph
     * registered an hour ago is caught by this half, which is the case no
     * blocklist knows about yet.
     */
    public void prescreen(String url, long dialogId, String displayText) {
        if (url == null || dialogId == 0) {
            return;
        }
        LinkScanner offline = scanners.get(ScanMode.SECRET_CHAT);
        executor.execute(() -> {
            LinkScanner.LinkVerdict verdict;
            try {
                verdict = offline.scan(url, displayText != null, displayText);
            } catch (Throwable t) {
                return;
            }
            if (!verdict.getRequiresInterstitial()) {
                return;
            }
            // Worst-wins, and it never clears itself. A conversation that has
            // once carried a phishing link keeps the marker until the state is
            // dropped with the process: "the suspicious message scrolled out of
            // the preview" is not evidence the sender stopped.
            final LinkScanner.LinkVerdict candidate = verdict;
            worstLink.compute(dialogId, (id, previous) ->
                    previous == null || candidate.getVerdict().getSeverity() > previous.getVerdict().getSeverity()
                            ? candidate
                            : previous);
        });
    }

    private final Map<Long, LinkScanner.LinkVerdict> worstLink = new ConcurrentHashMap<>();

    /** Worst pre-screened link in this conversation, or null. For DialogCell. */
    public LinkScanner.LinkVerdict worstLinkFor(long dialogId) {
        return worstLink.get(dialogId);
    }

    /** True when nothing has been pre-screened; lets DialogCell bail out early. */
    public boolean hasNoLinkVerdicts() {
        return worstLink.isEmpty();
    }

    /**
     * Which mode a link in this message would be checked under.
     *
     * For the settings screen and for tests, so the "secret chats are checked
     * on this device only" promise can be asserted rather than trusted.
     */
    public static boolean usesNetwork(Object parentObject) {
        return TelegramContext.scanModeFor(parentObject).getAllowsNetwork();
    }
}
