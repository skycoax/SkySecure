#!/usr/bin/env python3
"""
Store-readiness checks for Humogram.

WHY THIS EXISTS

A Play policy review found sixteen blocking problems in this fork, and every
one of them was invisible to the compiler. The build was green while the chat
list was headed by Telegram's registered wordmark, while READ_CALL_LOG shipped
in the release manifest, and while the screen carrying the unofficial-app
disclosure, the GPL source offer and the privacy policy was declared and never
opened from anywhere.

That class of defect does not come back through testing, because there is
nothing to test: the app behaves exactly as written. It comes back through a
merge, an upstream rebase, or somebody restoring a file. So the findings are
written down here as executable assertions.

WHAT IT IS NOT

Not a substitute for reading the policies. It encodes the specific mistakes
this codebase actually made, which is a much narrower thing than "the app
complies with Google Play". A green run means these particular regressions have
not returned.

RUN

    python tools/play_policy_check.py

Exit code 0 if every check passes, 1 otherwise. No dependencies, so it runs in
CI, in a git hook, or by hand before tagging a release.
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'TMessagesProj', 'src', 'main', 'res')
JAVA = os.path.join(ROOT, 'TMessagesProj', 'src', 'main', 'java')
MANIFEST = os.path.join(ROOT, 'TMessagesProj', 'src', 'main', 'AndroidManifest.xml')

failures = []
checks_run = 0


def check(name, condition, detail=''):
    global checks_run
    checks_run += 1
    if condition:
        print('  ok   %s' % name)
    else:
        print('  FAIL %s' % name)
        if detail:
            print('       %s' % detail)
        failures.append(name)


def read(path):
    with open(path, encoding='utf-8', errors='replace') as handle:
        return handle.read()


def read_bytes(path):
    with open(path, 'rb') as handle:
        return handle.read()


def java_files():
    for base, _, names in os.walk(JAVA):
        for name in names:
            if name.endswith('.java'):
                yield os.path.join(base, name)


def value_files():
    for name in sorted(os.listdir(RES)):
        if name == 'values' or name.startswith('values-'):
            path = os.path.join(RES, name, 'strings.xml')
            if os.path.exists(path):
                yield path


def string_value(path, key):
    match = re.search(r'<string name="%s">(.*?)</string>' % re.escape(key), read(path), re.S)
    return match.group(1) if match else None


# ---------------------------------------------------------------- impersonation

def check_no_telegram_wordmark_drawables():
    """Telegram's wordmark must not be drawn as this app's identity.

    The drawables may still sit in res/ (upstream ships them and deleting them
    breaks unrelated layouts); what must not exist is a reference from code.
    """
    hits = []
    for path in java_files():
        body = read(path)
        for line_no, line in enumerate(body.splitlines(), 1):
            if 'R.drawable.telegram_logo' in line and not line.strip().startswith('//'):
                hits.append('%s:%d' % (os.path.relpath(path, ROOT), line_no))
    check('no code draws Telegram\'s wordmark', not hits, ', '.join(hits))


def check_app_name_is_ours():
    for path in value_files():
        for key in ('AppName', 'AppNameBeta', 'app_name', 'Page1Title'):
            value = string_value(path, key)
            if value is None:
                continue
            rel = os.path.relpath(path, ROOT)
            check('%s in %s is not Telegram' % (key, os.path.basename(os.path.dirname(path))),
                  'telegram' not in value.lower(),
                  '%s says %r' % (rel, value))


def check_single_launcher_identity():
    """One launcher alias. Alternative icons were Telegram's plane in liveries.

    A third-party app that can dress itself as Telegram is the Impersonation
    policy squarely, and the enum in LauncherIconController must stay in step
    with the manifest -- setIcon() calls setComponentEnabledSetting on every
    value, so an enum entry without an alias throws.
    """
    manifest = read(MANIFEST)
    # Only aliases that put an entry in the launcher count. CallsActivity is an
    # activity-alias too, and counting it made this check fail on a correct
    # manifest -- a check that cries wolf gets switched off, so it has to be
    # exact about what it is looking for.
    aliases = [
        re.search(r'android:name="([^"]+)"', block).group(1)
        for block in re.findall(r'<activity-alias\b.*?</activity-alias>', manifest, re.S)
        if 'android.intent.category.LAUNCHER' in block and re.search(r'android:name="([^"]+)"', block)
    ]
    check('exactly one launcher activity-alias', len(aliases) == 1, 'found: %r' % (aliases,))

    controller = os.path.join(JAVA, 'org', 'telegram', 'ui', 'LauncherIconController.java')
    if os.path.exists(controller):
        body = read(controller)
        enum_block = re.search(r'public enum LauncherIcon \{(.*?)\}', body, re.S)
        entries = re.findall(r'^\s{8}([A-Z_]+)\(', enum_block.group(1), re.M) if enum_block else []
        check('LauncherIcon enum matches the manifest', len(entries) == len(aliases),
              'enum %r vs aliases %r' % (entries, aliases))


def check_splash_is_ours():
    for name in os.listdir(RES):
        path = os.path.join(RES, name, 'styles.xml')
        if not os.path.exists(path):
            continue
        body = read(path)
        check('splash icon in %s is not Telegram\'s' % name,
              'tg_splash' not in body,
              '%s references tg_splash' % os.path.relpath(path, ROOT))


def check_notification_icon_replaced():
    """The status-bar icon is the most-seen asset in the app.

    Checked by size rather than by name: the file keeps upstream's name, so the
    only evidence that it is ours is that the bytes changed. A stale copy of
    Telegram's plane would sail through a name check.
    """
    for density in ('mdpi', 'hdpi', 'xhdpi', 'xxhdpi'):
        path = os.path.join(RES, 'drawable-%s' % density, 'notification.webp')
        if not os.path.exists(path):
            continue
        head = read_bytes(path)[:16]
        check('notification.webp (%s) is a real image' % density,
              head[:4] == b'RIFF' and head[8:12] == b'WEBP',
              '%s is not a WebP file' % os.path.relpath(path, ROOT))


# ------------------------------------------------------------------ permissions

RESTRICTED = [
    ('android.permission.READ_CALL_LOG',
     'Play restricts Call Log access to default handlers; it was used only to '
     'auto-read an SMS-verification call and login works without it.'),
]


def check_restricted_permissions():
    manifests = [MANIFEST]
    config = os.path.join(ROOT, 'TMessagesProj', 'config')
    for base, _, names in os.walk(config):
        manifests += [os.path.join(base, n) for n in names if n.endswith('.xml')]
    for permission, why in RESTRICTED:
        offenders = [os.path.relpath(p, ROOT) for p in manifests
                     if permission in read(p)]
        check('%s is not declared' % permission.rsplit('.', 1)[-1], not offenders,
              '%s -- %s' % (', '.join(offenders), why))


# --------------------------------------------------------------------- honesty

def check_no_unevidenced_virus_claim():
    """A heuristic may not assert infection.

    jac_scan_suspicious_installer is shown for a SUSPICIOUS verdict on any
    installable package. It said "This file has a virus", which is a claim
    about somebody else's software that this app cannot support.
    """
    for path in value_files():
        value = string_value(path, 'jac_scan_suspicious_installer')
        if value is None:
            continue
        lowered = value.lower()
        claims = any(word in lowered for word in ('virus', 'вирус', 'virusi'))
        check('suspicious-installer wording is factual (%s)' % os.path.basename(os.path.dirname(path)),
              not claims, '%s says %r' % (os.path.relpath(path, ROOT), value))


def check_disclosure_reachable():
    """The About screen carries three obligations, so it must be openable.

    It was declared in the manifest and started from nowhere: the unofficial
    disclosure, the GPLv3 source offer and the privacy policy all shipped as
    dead code.
    """
    starts = []
    for path in java_files():
        if path.endswith('AboutActivity.java'):
            continue
        if 'AboutActivity.class' in read(path):
            starts.append(os.path.relpath(path, ROOT))
    check('About screen is started from somewhere', starts,
          'nothing calls startActivity(..., AboutActivity.class)')


def check_privacy_policy_names_this_app():
    policy = os.path.join(ROOT, 'docs', 'privacy-policy.md')
    if not os.path.exists(policy):
        check('privacy policy exists', False, 'docs/privacy-policy.md is missing')
        return
    body = read(policy)
    check('privacy policy names this app', 'Humogram' in body)
    check('privacy policy does not name the old brand', 'SkySecure' not in body)


def check_no_user_content_in_release_logs():
    """Logging a filename is a disclosure the privacy policy does not make.

    Guarded by BuildVars.LOGS_ENABLED rather than deleted: the diagnostic is
    worth having while developing.
    """
    scanner = os.path.join(JAVA, 'uz', 'jac', 'secure', 'android')
    unguarded = []
    for base, _, names in os.walk(scanner):
        for name in names:
            if not name.endswith('.java'):
                continue
            path = os.path.join(base, name)
            lines = read(path).splitlines()
            for line_no, line in enumerate(lines, 1):
                if 'Log.d(' not in line:
                    continue
                window = '\n'.join(lines[max(0, line_no - 6):line_no])
                if 'LOGS_ENABLED' not in window and 'DEBUG_VERSION' not in window:
                    unguarded.append('%s:%d' % (os.path.relpath(path, ROOT), line_no))
    check('scanner logging is debug-only', not unguarded, ', '.join(unguarded))


# ------------------------------------------------------------------- packaging

def check_release_ships_every_abi():
    """A single-architecture release installs on the phone you tested with.

    build.gradle carries a task-graph guard; this asserts the guard is still
    there, because losing it costs a release nobody can install.
    """
    body = read(os.path.join(ROOT, 'build.gradle'))
    check('release ABI guard is present',
          'jacAbi' in body and 'GradleException' in body,
          'the -PjacAbi release guard is gone from build.gradle')


def check_target_sdk():
    body = read(os.path.join(ROOT, 'TMessagesProj', 'build.gradle'))
    match = re.search(r'targetSdkVersion\s+(\d+)', body)
    check('targetSdkVersion is 35 or newer', match and int(match.group(1)) >= 35,
          'found %s' % (match.group(1) if match else 'nothing'))


def check_no_brand_leftovers():
    """The old brand must not survive anywhere a user can read it."""
    offenders = []
    for path in value_files():
        body = read(path)
        for line_no, line in enumerate(body.splitlines(), 1):
            if 'SkySecure' in line and 'github.com' not in line:
                offenders.append('%s:%d' % (os.path.relpath(path, ROOT), line_no))
    check('no SkySecure strings remain', not offenders, ', '.join(offenders))


def main():
    print('Humogram store-readiness checks\n')
    for group, fn in [
        ('impersonation', check_no_telegram_wordmark_drawables),
        ('impersonation', check_app_name_is_ours),
        ('impersonation', check_single_launcher_identity),
        ('impersonation', check_splash_is_ours),
        ('impersonation', check_notification_icon_replaced),
        ('permissions', check_restricted_permissions),
        ('honesty', check_no_unevidenced_virus_claim),
        ('disclosure', check_disclosure_reachable),
        ('disclosure', check_privacy_policy_names_this_app),
        ('privacy', check_no_user_content_in_release_logs),
        ('packaging', check_release_ships_every_abi),
        ('packaging', check_target_sdk),
        ('branding', check_no_brand_leftovers),
    ]:
        fn()

    print('\n%d checks, %d failed' % (checks_run, len(failures)))
    if failures:
        print('\nFailed:')
        for name in failures:
            print('  - %s' % name)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
