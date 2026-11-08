# KidSafe — free parental-safety app (Android, Kotlin)

Two apps, one Firebase project (free Spark plan is enough for family use):

- `child/`  — installed on the child's phone
- `parent/` — installed on the parent's phone
- `core/`   — shared plain-Kotlin logic (collection names, pairing code, location policy, DNS packets,
              alert wording, battery / screen-time rules). No Android classes, so it is unit-testable
- `ui/`     — shared look & feel (Compose theme + components) used by both apps

## Phase 1 (this scaffold)
- Parent: email sign-in, "Add child" → 8-character pairing code (valid 15 min, single use)
- Child: enter code → plain-language disclosure → one-time permissions → done
- Continuous location sharing (foreground service, always-visible notification,
  balanced-power + 50 m distance filter so it is battery-friendly), restarts after reboot
- Battery % sent with every update → if the phone dies, the parent still sees
  the last known place + battery
- Parent is notified when the child grants permission
- SOS button on the child phone → parent sees alert + location
- Firestore security rules (`firestore.rules`)

## Phase 2: app usage report
- Child turns on "Usage access" once (home screen, with a plain-language explanation first)
- Reports app name + minutes per day (no content), every ~24 hours via WorkManager
- Parent: child card -> "App usage" -> last 7 days, bar per app
- Firestore rules gained a `usage` block: re-publish `firestore.rules`
- APKs are now signed with the fixed `debug.keystore`, so later updates install over the old app

## Phase 3: safe places (geofence) + background alerts
- Parent: child card -> "Safe places" -> add Home/School (paste coordinates from Google Maps or use the
  child's last location), radius 150/300/500 m
- Child: places become Google geofences (battery-friendly); enter/exit events go to the parent
- Parent app keeps a small "Safety alerts are on" notification and shows alerts for
  SOS, arrived and left, even when the app is closed (no paid push service needed)
- Firestore rules gained a `places` block: re-publish `firestore.rules`

## Phase 4: location history
- Parent: child card -> "History" -> pick a day (last 7), see the route drawing, a timeline of
  stops ("stayed 08:10-12:40") and open the whole route in Google Maps
- Parent-app only change; no Firestore rule change needed

## Phase 5: web content filter
- Parent: child card -> "Web filter" -> switch on; optional extra blocked sites; list of blocked attempts
- Child: a DNS-only VPN (only website-name lookups pass through; nothing else is read).
  The child taps "Turn on web protection" once and accepts Android's VPN prompt
- Blocks adult + malware sites via Cloudflare family DNS (1.1.1.3) and the parent's own list
- Parent gets an alert for blocked attempts and if the filter is switched off
- Tip: on the child phone, Settings -> Network -> VPN -> (gear next to KidSafe) -> "Always-on VPN"
- Limits: a custom Private DNS / DoH setting can bypass a DNS filter (the parent screen warns about
  Private DNS). Google Play requires a VpnService declaration before publishing.
- Firestore rules gained `settings` and `blocked` blocks: re-publish `firestore.rules`

## Phase 6: app icon
- Launcher icon on both apps is the full KidSafe badge image the user supplied, as an Android
  adaptive icon (`drawable/ic_launcher_background.xml` + `mipmap-*/ic_launcher_foreground.png`)
- Small-size preview: `branding/icon-preview-small-fulllogo.png` (fine detail/text is not legible at
  launcher size, by the user's choice; a simpler shield-only crop is kept as a fallback at
  `branding/icon-preview-small.png` / `icon-preview-512.png` if they want to switch back)
- Icon-only change, no Firestore rules to update

## Phase 8: child-initiated screen share + "I need to talk"
- Child: "Share my screen" (5 min to 24 hours presets; default = 1440 min / 24 hours, which is also the hard cap) and "I need to talk" on the home
  screen. The parent app has no start/stop control for screen share anywhere -- only the child's
  phone can begin or end a session, and Android's own system permission + capture indicator cannot
  be bypassed or hidden
- Not real-time video: a low-resolution still image (no audio) is uploaded every ~2 seconds via
  Firestore, kept free-tier friendly; the session always ends on its own at the chosen time limit
- Parent: child card -> "Screen share" to view while a session is active; "I need to talk" shows as
  an alert like SOS
- Firestore rules gained a `screenshare` block: re-publish `firestore.rules`

## Phase 9: notification history (parent app)
- Parent home -> "Notification history": every alert this phone received since the app was installed
  (SOS, arrived, left, blocked site, web protection turned off, "I need to talk", screen share started),
  newest first, grouped by day, with a filter per child
- Saved in a small database on the parent's phone (nothing extra is uploaded), and saved even if
  Android notifications are switched off for the app
- Keeps everything; the screen shows the latest 2000
- Parent-app only change; no Firestore rule change needed

## Phase 10: parent <-> child chat
- Child home -> "Chat with your parent", parent's child card -> "Chat": a two-way chat screen for
  that one child, WhatsApp-style bubbles (their messages on one side, yours on the other)
- Quick-reply options on both sides are real one-tap buttons, not a text field you have to type into
- Stored in `children/{childId}/messages` (already covered by firestore.rules from an earlier phase)
- A message from the child also raises the normal event: the parent gets a push notification and it
  is saved to Notification history, the same way SOS / "I need to talk" already work
- A message from the parent is delivered live while the child's location service is running (v2 shows a
  notification on the child's phone; there is still no Firebase Cloud Messaging in this project)
- NOT built: reading or copying messages from WhatsApp or any other third-party app. There is no
  public WhatsApp API for this; the only way to do it is Accessibility-Service screen scraping,
  the same technique stalkerware uses, and it would also capture the child's friends' messages
  without their consent. Skipped on purpose.

## Phase 11: v2 rebuild — new look, better location, new features
Every feature above still works the same way. Nothing was removed and no setting changed meaning.
Package names, Firestore data shapes, SharedPreferences names, the local notification database and the
signing key are unchanged, so v2 installs *over* v1 and keeps the pairing, places, history and alerts.

**New look (both apps)** — one shared `ui/` module: new theme (light + dark), cards, status pills,
avatar + battery chips, empty states. Parent app now has bottom tabs: Family / Alerts / Account, and a
dashboard screen per child.

**Better location tracking (child)**
- Two modes: *moving* (high accuracy, every ~1 min) and *stationary* (balanced power, every ~5 min).
  The phone switches by itself after it has been still for two readings -> much less battery.
- Bad fixes (accuracy worse than 150 m) are ignored unless nothing better arrived for 15 minutes.
- History points are only saved when the phone moved 40 m+, or every 20 min while still,
  so the route and timeline stay clean and Firestore writes drop a lot.
- A light heartbeat (`lastSeen`) every ~10 min, so the parent can tell "phone is still, but online"
  from "phone is off / offline".
- Unit-tested in `core/` (`TrackingPolicy`, `RouteStops`).

**New features**
1. **Locate now** (parent) — asks the child's phone for a fresh location. The child's phone shows a
   notice ("Your parent asked where you are") and the parent sees Asking… -> Fresh location received.
2. **"I'm safe" check-in** (child) — one tap sends the current location + "checked in" to the parent.
3. **Low-battery alert** — parent is told once when the child's battery drops to 15% (re-arms at 25%
   or when charging).
4. **Daily screen-time goal** — parent picks Off / 30m / 1h / 90m / 2h / 3h / 4h / 6h in "App usage".
   The child sees today's total with a progress bar; both get one alert when the goal is reached.
   It is a gentle reminder only — nothing is blocked.
5. **Phone-quiet warning** — a child card turns amber if there has been no sign from the phone for
   30+ minutes (switched off, offline or out of battery).
6. **Parent messages reach the child** — a notification on the child's phone when the parent writes
   (tap it to open the chat). Before, messages showed only while the chat screen was open.
7. **Today's screen time on the family card**, accuracy ("accurate to about 20 m") and "Moving" tag.
8. **Forgot password** on the parent sign-in screen, "Share code" for the pairing code, and a
   notification-permission warning in the Account tab.

Transparency stays the rule: location sharing has a permanent notification, screen sharing is only
started by the child, and everything the parent triggers (Locate now, messages) shows on the child's phone.

Version: parent `2.0.0` (versionCode 11), child `2.0.0` (versionCode 10).
Firestore rules: **no change needed** (re-publishing `firestore.rules` is harmless).

## Setup
1. Create a Firebase project → enable **Authentication**: Email/Password **and** Anonymous.
2. Create **Firestore** (production mode), then paste `firestore.rules` into Rules → Publish.
3. Register two Android apps in Firebase:
   - `com.example.kidsafe.child`  → download `google-services.json` into `child/`
   - `com.example.kidsafe.parent` → download `google-services.json` into `parent/`
   (Change the package names everywhere first if you want your own.)
4. Open this folder in Android Studio (Ladybug or newer), let Gradle sync, run each module.

## Before publishing on Google Play
- Background location: fill the Play Console "Location permissions" declaration and use the
  in-app disclosure screen (already included) in your review video.
- `targetSdk`: raise it to whatever Play currently requires.
- Keep the app visible on the child's phone (launcher icon + notification). Don't hide it.
- Add a privacy policy URL.

## Next phases
2. Screen time limits + app block (UsageStats + Accessibility/Device Admin)
3. App usage report
4. Geofence alerts + push notifications (FCM) for SOS
5. Web content filter + blocked-attempt alerts
6. Scheduled app lock (study / bedtime)
7. Location history screen, TTL cleanup of old points

Not included on purpose: remote camera, remote microphone, live screen viewing.
