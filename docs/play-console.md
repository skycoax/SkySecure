# Play Console declarations

What we told Google about Humogram (`uz.humogram.app`) in Play Console, and why.
Play Console is the source of truth; this file is the record kept next to the
code, so a change to the app can be compared with what we declared.
`Tools/play_policy_check.py` reads the `iarc.*` and `audience.*` lines below and
fails when the code ships a feature a declaration denies. Every release build
runs it (see `build.gradle`).

## Why this file exists

In September 2026 three policy issues had one cause: the declarations and the
code drifted apart, and nothing compared them.

- **Content rating**, rejected 2026-09-25. The August questionnaire described
  a communication app for people you already know, with moderated chat, and
  rated it ESRB Everyone. Groups of 200 000, channels, 18+ media and gift
  crafting had been in the code all along. Re-taken on 2026-09-25; the result
  now matches the official Telegram app (US Mature 17+, DE USK 18, generic 12+).
- **Billing Library 8.0.0** and **target API 36**, enforced 2026-08-31. Fixed
  in 69939, but 69929 (closed testing) and 69919 (internal testing) kept both
  warnings open: Play checks every track you publish to, not only production.

## Content rating (IARC questionnaire)

Policy and programs > App content > Content ratings > Start new questionnaire.
Re-take it whenever a line below stops being true, and change the line in the
same commit.

```
iarc.category        = social   # groups up to 200 000, channels, public search
iarc.dating          = no
iarc.nudity          = yes      # public channels; 18+ media behind "Show 18+ Content"
iarc.nudity_primary  = no
iarc.violence        = no       # Telegram's terms forbid promoting violence in public
iarc.location        = yes      # location and live-location sharing
iarc.digital_goods   = yes      # Premium, Stars and gifts through Google Play Billing
iarc.random_items    = yes      # gift upgrades (random attributes), gift crafting ("success chance")
iarc.block           = yes
iarc.report          = yes
iarc.moderation      = no       # nobody moderates private chats and groups
iarc.friends_only    = no

audience.min_age     = 13       # target audience 13-15, 16-17, 18+; privacy policy "Children"
```

Result on 2026-09-25: ESRB Mature 17+, PEGI Parental guidance, USK 18,
ClassInd 18, IARC generic, Russia and South Korea 12+.

`violence = yes` was tried and not submitted: it rates the app 18+ everywhere,
Uzbekistan included, where Telegram itself is 12+.

## Data safety: the scanner

Data safety, the privacy policy and the About screen (`jac_about_scanner_body`)
all say the scanner runs on the device and sends nothing, which is true of
69939: no shipped code reads `jac_api_base_url`.

```
data.scanner_sends   = no       # no scanner data leaves the phone
```

The device scanner's cloud check (`DeviceScanNetwork`, in progress) sends SHA-256
fingerprints of some installed APKs to our backend once the user consents. Before
a build with it goes to Play: declare installed-app information in Data safety,
rewrite the scanner parts of the privacy policy (site and `docs/`) and the
About text, then set the line above to `yes`. Until then the release build stops.

## Target audience and age

Target audience is 13-15, 16-17 and 18+. The privacy policy (`docs/privacy-policy.md`
and https://hg.skycoax.uz/privacy) states the same minimum, 13, and that 18+
media stays hidden until the user confirms they are 18. Change all three
together.

## Target API and Billing Library

Google raises both every year on 31 August and warns in Policy status only
weeks before. The rows live in `PLAY_REQUIREMENTS` in
`Tools/play_policy_check.py`: a row warns 180 days before its date and fails the
release build once the date has passed.

| From       | Target API | Billing Library | Source                          |
|------------|------------|-----------------|---------------------------------|
| 2026-08-31 | 36         | 8.0.0           | enforced (Policy status)        |
| 2027-08-31 | 37         | 9.0.0           | expected; confirm in the console |

Billing 9 declares minSdk 23 and we ship minSdk 21, so moving to it means raising
minSdk, as upstream will have to.

## Tracks

Every track with a release serves its build to users, and Play checks it. After
a production release is approved, promote the same build to internal and closed
testing (or stop using those tracks); otherwise the old bundles keep the policy
warnings open.

## Sign-in details (App access)

Login is by one-time code. Reviewers get the test number and a private relay
page that shows the latest code (`tg-relay` on the VPS). The relay must stay up
for the whole review. Its address is in Play Console only; never commit it.
