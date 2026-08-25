# SkySecure / Humogram

A fork of Telegram for Android. Our own code lives in `uz.jac.secure.android`
(and `jacsecure-core`); everything else under `org.telegram` is upstream and is
patched in place.

## The house rule: build with Telegram's parts, don't rebuild them

**Before writing any UI, find the upstream component that already does it and
use that.** A screen, a row, a dialog or a colour we invent ourselves will look
like a different app bolted onto this one — because that is what it is. Users
notice immediately: the back arrow does not ripple, swipe-back is gone, rows sit
at the wrong height, the radio does not animate, the page pushes onto the task
stack instead of sliding in, and none of it follows a theme change.

This is not a style preference. Upstream is ~1.5M lines that have already solved
RTL, theming, night mode, blur, adaptive action bars, dividers, insets and
accessibility for every one of these widgets. Reimplementing one means
reimplementing all of that, badly, and then keeping it in step forever.

### What to reach for

| Need | Use | Not |
| --- | --- | --- |
| A settings screen | `UniversalFragment` + `UItem`s (see `WebBrowserSettings`, `SettingsActivity`) | `android.app.Activity` with a hand-built `LinearLayout` |
| Any screen at all | `BaseFragment` + `presentFragment(...)` | `Activity` + `startActivity(Intent)` |
| Title bar / back button | `ActionBar`, `BackDrawable`, `actionBar.setAdaptiveBackground(listView)` | a `TextView` containing `"←"` |
| A list row | `TextCell`, `TextCheckCell`, `DialogRadioCell`, `HeaderCell`, `TextInfoPrivacyCell`, `ShadowSectionCell` | a `LinearLayout` with a `GradientDrawable` background |
| Rounded section cards | `listView.setSections()` + `adapter.setApplyBackground(false)` | drawing your own round rects |
| Colours | `Theme.getColor(Theme.key_...)` / `getThemedColor(...)` | hard-coded hex, `JacTheme` in a Telegram-hosted view |
| Sizes | `AndroidUtilities.dp(...)`, `LayoutHelper.createFrame(...)` | manual density maths |
| Alerts | `AlertDialog.Builder`, `AlertsCreator`, `BulletinFactory` | a custom dialog layout |
| Strings shown in-app | `LocaleController.getString(...)` for upstream strings; `JacStrings.get(...)` for ours (see the class doc for why) | `context.getString(...)` directly |

### The narrow exceptions

Write a custom `View` only for something genuinely ours that upstream has no
equivalent of — a scan verdict block, an ornament band. Even then:

- put it **inside** an upstream container (a `UItem.asCustom(view)` cell, a
  `BaseFragment`), never around one;
- take every colour from `Theme`, every size from `AndroidUtilities.dp`;
- keep the drawing in one class and let the settings page and the real screen
  both call it, so a preview can never disagree with the thing it previews.

`AboutActivity` and `LinkInterstitialActivity` are plain `Activity`s because they
are launched from outside the fragment stack. That is the reason to be one — not
"it was quicker".

### When you patch upstream

Keep the diff minimal and mark it: a `// Humogram:` comment saying *why*, in the
style of the surrounding file. A three-line insert into `DialogsActivity` is
maintainable across a rebase; a rewritten `DialogsActivity` is not.

## Building

```
./gradlew :TMessagesProj:compileDebugJavaWithJavac   # fast syntax/type check
./gradlew :TMessagesProj_App:assembleAfatDebug       # installable APK
```
