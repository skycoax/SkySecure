package uz.jac.secure.android;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.R;

/**
 * The About screen.
 *
 * <h3>This screen is a compliance artifact, not a courtesy</h3>
 *
 * Three separate obligations land here, and all three are conditions of being
 * allowed to ship at all:
 *
 * <ul>
 *   <li><b>Telegram API Terms.</b> An unofficial client must state, in the app
 *       itself and not only in a store listing, that it uses the Telegram API
 *       and is not operated by or affiliated with Telegram. That is
 *       {@code jac_about_telegram_api}, and it is the first thing on the
 *       screen rather than a line at the bottom.</li>
 *   <li><b>GPLv3, §6.</b> Distributing a binary of a Telegram-Android
 *       derivative obliges us to offer the complete corresponding source of
 *       <em>our</em> modified version — the scanner included. The link here is
 *       that offer, so it must resolve to the actual published fork and not to
 *       a placeholder. {@code tools/preflight.mjs} fails the release if it is
 *       still the default.</li>
 *   <li><b>Our own privacy claim.</b> The app tells people it sends only a
 *       fingerprint and never a filename. A claim made in marketing and not
 *       repeated where the user can find it is not a claim they can hold us
 *       to.</li>
 * </ul>
 *
 * Wire it into Settings, and show it once on first run — the ToS requirement is
 * for an intro screen, which means it has to be seen rather than merely
 * present.
 */
public final class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = JacTheme.dp(this, 24);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        content.setBackgroundColor(JacTheme.screen(this));

        content.addView(buildMark(), centred(JacTheme.dp(this, 64), JacTheme.dp(this, 64), JacTheme.dp(this, 16)));

        TextView name = new TextView(this);
        name.setText(R.string.app_name);
        name.setTextSize(24f);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setTextColor(JacTheme.text(this));
        name.setGravity(Gravity.CENTER);
        content.addView(name, row(JacTheme.dp(this, 14)));

        TextView version = new TextView(this);
        version.setText(getString(R.string.jac_about_version, versionName(), versionCode()));
        version.setTextSize(13f);
        version.setTextColor(JacTheme.textMuted(this));
        version.setGravity(Gravity.CENTER);
        content.addView(version, row(JacTheme.dp(this, 4)));

        // First, and unconditionally. Telegram's terms are about what the user
        // is told, so this cannot be behind a "more" affordance.
        content.addView(paragraph(getString(R.string.jac_about_telegram_api)), row(JacTheme.dp(this, 28)));

        content.addView(heading(getString(R.string.jac_about_scanner_heading)), row(JacTheme.dp(this, 28)));
        content.addView(paragraph(getString(R.string.jac_about_scanner_body)), row(JacTheme.dp(this, 8)));

        content.addView(heading(getString(R.string.jac_about_licence_heading)), row(JacTheme.dp(this, 28)));
        String sourceUrl = getString(R.string.jac_source_url);
        content.addView(paragraph(getString(R.string.jac_about_source, sourceUrl)), row(JacTheme.dp(this, 8)));
        content.addView(link(getString(R.string.jac_about_open_source), sourceUrl), row(JacTheme.dp(this, 12)));
        content.addView(link(getString(R.string.jac_about_privacy), getString(R.string.jac_privacy_url)),
                row(JacTheme.dp(this, 4)));

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(JacTheme.screen(this));
        scroller.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroller);
    }

    /** The shield, in the brand colour — the same glyph the warnings use. */
    private View buildMark() {
        return new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF bounds = new RectF();

            @Override
            protected void onDraw(Canvas canvas) {
                bounds.set(0, 0, getWidth(), getHeight());
                paint.setColor(JacTheme.primary(getContext()));
                JacIcons.draw(canvas, JacIcons.Glyph.SHIELD, bounds, paint);
            }
        };
    }

    private TextView heading(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13f);
        view.setAllCaps(true);
        view.setLetterSpacing(0.1f);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setTextColor(JacTheme.textMuted(this));
        return view;
    }

    private TextView paragraph(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15f);
        view.setLineSpacing(JacTheme.dp(this, 4), 1f);
        view.setTextColor(JacTheme.textMuted(this));
        return view;
    }

    private TextView link(String label, String url) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(15f);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setTextColor(JacTheme.primary(this));
        view.setPadding(0, JacTheme.dp(this, 10), 0, JacTheme.dp(this, 10));
        view.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
                // No browser installed. Nothing useful to say, and crashing the
                // About screen over a missing browser would be absurd — the URL
                // is printed in full above this button either way.
            }
        });
        return view;
    }

    private String versionName() {
        PackageInfo info = packageInfo();
        return info != null && info.versionName != null ? info.versionName : "—";
    }

    private String versionCode() {
        PackageInfo info = packageInfo();
        if (info == null) {
            return "—";
        }
        long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode()
                : info.versionCode;
        return String.valueOf(code);
    }

    private PackageInfo packageInfo() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private LinearLayout.LayoutParams row(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private LinearLayout.LayoutParams centred(int width, int height, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = topMargin;
        params.gravity = Gravity.CENTER_HORIZONTAL;
        return params;
    }
}
