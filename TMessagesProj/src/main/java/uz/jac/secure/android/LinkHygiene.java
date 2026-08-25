package uz.jac.secure.android;

import android.content.Context;
import android.net.Uri;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Strips tracking parameters from a link before it opens.
 *
 * <h3>What gets removed, and why exactly this list</h3>
 *
 * Only parameters that exist for the advertiser rather than the server:
 * campaign tags ({@code utm_*}), click identifiers ({@code fbclid},
 * {@code gclid}, {@code yclid}…) and mail-merge tokens ({@code mc_eid},
 * {@code mkt_tok}). Removing one of these never changes what page loads — the
 * page does not need it — it only changes what the destination learns about
 * where the click came from and, for per-recipient tokens, about <em>who</em>
 * clicked.
 *
 * <p>The list is deliberately conservative: every key on it is unambiguous.
 * Short, overloaded keys like {@code ref}, {@code source} or {@code si} are
 * left alone even though they often track, because on some sites they are
 * load-bearing and a link that stops working teaches the user to turn the
 * feature off. A missed tracker costs a little privacy; a broken link costs
 * the whole setting.
 *
 * <h3>Where this runs</h3>
 *
 * At the single funnel every open path goes through —
 * {@code Browser.openUrl}'s widest overload — so a link is cleaned the same
 * way whether it was tapped in a chat, opened from the interstitial's
 * "open anyway", or launched by a bot button. Only {@code http}/{@code https}
 * links are touched: {@code tg://} and {@code intent://} carry meaning in
 * their parameters and are none of this class's business.
 *
 * <p>The work is a raw string scan over the encoded query — no decode/re-encode
 * round-trip of the values, so a percent-encoded payload survives byte-for-byte
 * and a URL with no trackers is returned as the very same object.
 */
public final class LinkHygiene {

    /** Keys removed by exact (case-insensitive) match. */
    private static final Set<String> EXACT = new HashSet<>(Arrays.asList(
            // Facebook / Instagram
            "fbclid", "mibextid", "igshid", "igsh", "fb_ref", "fb_source",
            // Google Ads / Analytics / Search
            "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "srsltid",
            // Microsoft, Yandex, Twitter/X, TikTok, LinkedIn
            "msclkid", "yclid", "ysclid", "twclid", "ttclid", "li_fat_id",
            // Mail-merge and marketing-automation tokens (identify the recipient)
            "mc_eid", "mkt_tok", "vero_id", "oly_anon_id", "oly_enc_id",
            "_hsenc", "_hsmi"
    ));

    /** Key prefixes removed by (case-insensitive) match. */
    private static final String[] PREFIXES = {"utm_"};

    private LinkHygiene() {
    }

    /**
     * String form, for the open paths that carry a URL rather than a Uri.
     *
     * <p>Returns the same string when nothing changed, so a caller can pass
     * every URL through this without allocating on the common path.
     */
    public static String clean(Context context, String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        try {
            final Uri parsed = Uri.parse(url);
            final Uri cleaned = clean(context, parsed);
            return cleaned == parsed ? url : cleaned.toString();
        } catch (Throwable t) {
            return url;
        }
    }

    /**
     * The given link with tracking parameters removed, or the same object
     * untouched when there is nothing to remove or the feature is off.
     */
    public static Uri clean(Context context, Uri uri) {
        if (uri == null || context == null) {
            return uri;
        }
        try {
            if (!HumogramConfig.isLinkHygiene(context)) {
                return uri;
            }
            final String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return uri;
            }
            if (uri.isOpaque()) {
                return uri;
            }
            final String query = uri.getEncodedQuery();
            if (query == null || query.isEmpty()) {
                return uri;
            }
            final StringBuilder kept = new StringBuilder(query.length());
            boolean removedAny = false;
            for (int start = 0, n = query.length(); start <= n; ) {
                int end = query.indexOf('&', start);
                if (end < 0) {
                    end = n;
                }
                final String pair = query.substring(start, end);
                if (isTracker(pair)) {
                    removedAny = true;
                } else if (!pair.isEmpty()) {
                    if (kept.length() > 0) {
                        kept.append('&');
                    }
                    kept.append(pair);
                }
                start = end + 1;
            }
            if (!removedAny) {
                return uri;
            }
            return uri.buildUpon()
                    .encodedQuery(kept.length() == 0 ? null : kept.toString())
                    .build();
        } catch (Throwable t) {
            // A link must always open; cleaning is best-effort by design.
            return uri;
        }
    }

    /** Whether one raw {@code key=value} pair (value optional) is a tracker. */
    private static boolean isTracker(String pair) {
        int eq = pair.indexOf('=');
        String key = (eq >= 0 ? pair.substring(0, eq) : pair)
                .toLowerCase(Locale.ROOT);
        if (EXACT.contains(key)) {
            return true;
        }
        for (String prefix : PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
