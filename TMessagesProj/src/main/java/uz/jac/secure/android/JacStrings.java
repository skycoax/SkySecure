package uz.jac.secure.android;

import android.content.Context;
import android.content.res.Configuration;

import org.telegram.messenger.LocaleController;

import java.util.Locale;

/**
 * Our strings, in the language the user actually chose.
 *
 * <h3>The bug this exists to fix</h3>
 *
 * Telegram does not use Android's resource localisation for its own UI. It has
 * {@link LocaleController}, which loads language packs at runtime and is set
 * from a picker inside the app — completely independent of the device's system
 * locale.
 *
 * Our strings, by contrast, live in {@code values-uz/strings.xml} and are
 * resolved by {@code Context.getString()}, which follows the SYSTEM locale.
 *
 * So on a phone whose system language is English but whose Telegram is set to
 * Uzbek — the normal configuration for this product's users, since the app is
 * the thing they set to their language and the phone often came in English —
 * the entire messenger appeared in Uzbek while every scanner verdict appeared
 * in English. The one sentence telling someone their file is dangerous was in
 * the one language they were least likely to read.
 *
 * <h3>The fix</h3>
 *
 * Resolve our resources against {@code LocaleController.getCurrentLocale()}
 * instead. The localised Context is cached and rebuilt only when the language
 * changes, because creating one per string would mean an allocation per draw
 * on the message list's scrolling path.
 *
 * Falls back to the plain Context if anything goes wrong. English copy is a
 * poor outcome; a crash while drawing a message is a worse one.
 */
public final class JacStrings {

    private static volatile Context cached;
    private static volatile Locale cachedFor;

    private JacStrings() {
    }

    public static String get(Context context, int resId) {
        return localized(context).getString(resId);
    }

    public static String get(Context context, int resId, Object... formatArgs) {
        return localized(context).getString(resId, formatArgs);
    }

    /**
     * A Context whose resources speak the language Telegram is set to.
     *
     * Public because the Activities build whole screens and would otherwise
     * have to route every single call through {@link #get}.
     */
    public static Context localized(Context context) {
        Locale locale;
        try {
            locale = LocaleController.getInstance().getCurrentLocale();
        } catch (Throwable t) {
            return context;
        }
        if (locale == null) {
            return context;
        }

        Context localized = cached;
        if (localized != null && locale.equals(cachedFor)) {
            return localized;
        }

        try {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.setLocale(locale);
            localized = context.getApplicationContext().createConfigurationContext(configuration);
            // Written after the context is built, so a reader either sees a
            // fully-built context with its matching locale or misses the cache
            // and rebuilds — never a context paired with the wrong locale.
            cached = localized;
            cachedFor = locale;
            return localized;
        } catch (Throwable t) {
            return context;
        }
    }

    /**
     * Drop the cache. Call when the user changes the app language.
     *
     * Without this the scanner keeps speaking the previous language until the
     * process restarts — which looks exactly like the bug this class fixed.
     */
    public static void invalidate() {
        cached = null;
        cachedFor = null;
    }
}
