# Adding the kiosk to an app

Handoff for whoever is wiring `com.mk:kiosk` into an existing Android app.
Self-contained: you should not need the library's own README to follow it.

The library locks a device into a single app — Home and Recents blocked, status
bar and keyguard hidden, app becomes Home so a reboot lands back in it, and the
system told not to force-stop the package. It brings its own settings panel,
master password, and a battery/Wi-Fi strip.

The status strip takes its colours from your app's theme by default, so it
looks like part of the app. See `statusOverlayStyle` in the API table.

**It does not touch your app's UI.** No activity you see, no theme, no layout,
and it never wraps or replaces your views. Its entire visible footprint is
dialogs (only when someone opens the controls) and one small corner strip you
can turn off. Works with views, Compose or a WebView alike.

---

## 1. Depend on it

Source: https://github.com/kh-menachem/AndroidKiosk (private)

Pick whichever suits the project:

```bash
# local Maven — best if several apps will use it
gradle :kiosk:publishToMavenLocal
```

```groovy
repositories { mavenLocal() }
dependencies { implementation 'com.mk:kiosk:1.2.0' }
```

Or build `kiosk/build/outputs/aar/kiosk-release.aar` and drop it in `libs/`:

```groovy
dependencies { implementation files('libs/kiosk-release.aar') }
```

Requires `minSdk 26`. Built against `compileSdk 34`. No transitive dependencies —
it is plain framework code, so it will not drag anything into the app.

---

## 2. Manifest

The library declares its own service, job service, admin receiver, boot
receiver, permissions, and the two provisioning activities Android 11+ demands.
The manifest merger folds them into the app. **Do not redeclare any of them.**

The app adds exactly one thing: an `activity-alias` that makes it a Home app.

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>

<!-- CATEGORY_HOME goes here, NOT on the activity. An intent filter cannot be
     revoked at runtime but a component can be disabled, which is what makes
     "become Home" a switchable setting rather than a permanent one.
     The name is fixed: the library looks for <package>.KioskHomeAlias -->
<activity-alias
    android:name=".KioskHomeAlias"
    android:targetActivity=".MainActivity"
    android:exported="true"
    android:enabled="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity-alias>
```

`launchMode="singleTask"` matters: without it, Home can stack a second copy of
the activity.

If the app should never be Home, omit the alias entirely. Everything else still
works; only reboot-recovery is lost.

---

## 3. Code

One line, in the `onCreate` of the activity that should own the device:

```java
@Override
protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    setContentView(R.layout.main);

    Kiosk.attach(this);
}
```

That is the whole integration. `attach` registers its own lifecycle callbacks,
so nothing goes in `onResume` or `onStop`. It also installs the gesture that
opens the controls, and on first run prompts for a master password.

Optional, if the app uses the keep-awake setting:

```java
@Override
protected void onResume() {
    super.onResume();
    Kiosk.applyKeepScreenOn(this);
}
```

### Reaching the controls

Seven taps in the top-left corner within three seconds, then the master
password. Same in every app using the library. Configure before `attach`:

```java
KioskGesture.configure(new KioskGesture.Config()
        .taps(5)
        .corner(KioskGesture.Corner.BOTTOM_RIGHT)
        .within(5000)
        .size(0.2f));
```

`KioskGesture.describe()` returns it in words for a help screen.
`KioskGesture.openPanel(activity)` opens the panel from your own trigger.

---

## 4. Provisioning

Android will not let an app make itself device owner. It is granted once, on a
factory-fresh device with **no account signed in**. Until then `Kiosk.attach` is
harmless — the app runs normally and `status().isDeviceOwner` is false.

Build a release APK, then from `tools/`:

```powershell
.\serve-apk.ps1        # in its own terminal; leave running

.\make-provisioning-qr.ps1 `
    -Apk path\to\yourapp-release.apk `
    -Url http://<this-machine-ip>:8000/yourapp.apk `
    -Component com.example.yourapp/com.mk.kiosk.KioskAdminReceiver
```

Copy the APK to `tools/serve/` under the filename in the URL first.

On the device: factory reset, **tap the first setup screen six times**, connect
to Wi-Fi, scan the code.

Notes that cost time if missed:

- The receiver class stays `com.mk.kiosk.KioskAdminReceiver`. Only the package
  before the slash changes.
- The checksum is of the **signing certificate**, not the APK, so one QR keeps
  working across rebuilds. Re-generate when the signing key changes — a debug-
  signed QR will not match a release-signed APK.
- Add `-Wifi <ssid> -WifiPassword <pw>` to join Wi-Fi automatically. The
  password ends up in plain text in the generated `.json` and inside the QR
  image — do not commit or share either. Without it, connect by hand in the
  setup wizard before scanning.
- If it downloads and then says "Can't set up device", the DPC is not answering
  `GET_PROVISIONING_MODE`. The library handles this; a host that somehow
  overrides those activities will break it.
- `adb` alternative over a cable: `tools\provision.ps1 -Apk ... -Package ...`

Only this initial grant needs any of that. Everything afterwards is in-app.

---

## 5. Running it

### The three levels of "off"

| | reversible | survives reboot |
|---|---|---|
| `Kiosk.suspend(a)` — maintenance unlock | yes, auto-relocks in 15 min or on return | no |
| `Kiosk.disable(a)` — kiosk off | **yes**, `Kiosk.enable(a)` | yes |
| `Kiosk.release(a)` — hand back device owner | **no — factory reset only** | n/a |

**Use `disable`, not `release`.** Device owner can never be re-granted from the
device; Android requires a factory reset and fresh provisioning. `disable` keeps
device owner and makes the device behave completely normally until `enable`.

Only one app per device can be device owner. To move the kiosk to a different
app, `release` on the old one (or factory reset), then provision the new one.

### API

| | |
|---|---|
| `Kiosk.attach(activity)` | The whole integration. From `onCreate`. |
| `Kiosk.apply(activity)` | Re-applies everything. Idempotent. |
| `Kiosk.suspend` / `.resume` | Maintenance unlock, and end it. |
| `Kiosk.disable` / `.enable` | Kiosk off and on, keeping device owner. |
| `Kiosk.release(activity)` | Hands back device owner. One way. |
| `Kiosk.setBecomeHome(activity, on)` | Whether Home returns to this app. |
| `Kiosk.showStatusOverlay(activity, on)` | Battery/Wi-Fi strip. On by default. |
| `Kiosk.statusOverlayPosition(pos)` | Which corner. |
| `Kiosk.statusOverlayStyle(style)` | `AUTO` (follows your theme), `TRANSPARENT`, `DARK`, `LIGHT`. |
| `Kiosk.statusOverlayColours(fg, chip)` | Exact colours. A transparent chip means no chip. |
| `Kiosk.setWifiIconOpensSettings(activity, on)` | Tapping the Wi-Fi icon opens Wi-Fi settings. On by default, no password. |
| `Kiosk.keepScreenOn(activity, on)` | Remembered across restarts. |
| `Kiosk.openWifiSettings(activity)` | Android's Wi-Fi screen from inside the lock. |
| `Kiosk.status(context)` | `isDeviceOwner`, `isLocked`, `isEnabled`, `isSuspended`, `keepAwake`, `statusOverlay`, `becomeHome`. |
| `KioskPanel.open(activity)` | Settings panel, behind the password. |
| `KioskNetwork.reconnectIfStale(ctx, msg, cb)` | Self-healing Wi-Fi. |

Every device-owner call is attempted separately and swallows its own failure, so
an OEM refusing one policy does not cost the rest.

### Wi-Fi self-heal

A tablet was found holding full signal and a valid IP, reported as validated,
while resolving no hostname at all — the association had gone stale overnight
and nothing noticed. Bouncing the radio fixes it, and only a device owner may do
that on Android 10+.

```java
KioskNetwork.reconnectIfStale(context, failureMessage, (ok, msg) -> { ... });
KioskNetwork.noteHealthy();   // after a request that worked
```

Call it from the app's network error handler. It acts only on name-resolution
failures, and backs off to one attempt per five minutes, three in a row.

---

## 6. Things that will bite

**Tapping the Wi-Fi icon opens Wi-Fi settings, with no password.** That is
deliberate - a device off the network is useless until somebody reconnects it,
and that somebody is rarely the person holding the password. It does mean any
user can reach Android's Wi-Fi screen. Lock task still confines them to Settings
and this app, and returning re-locks, but if the deployment cannot tolerate that,
turn it off with `Kiosk.setWifiIconOpensSettings(activity, false)` or from the
panel.

**The master password cannot be recovered.** Set on first run, stored as a
PBKDF2 hash in the app's private storage. Forgotten means factory reset, or
`adb shell pm clear <package>` if USB debugging happens to be on. Record it
before a device goes out.

**It is per app.** Each app using the library has its own.

**Battery optimisation whitelist is device-level** and not in the APK. Re-apply
after any factory reset:

```bash
adb shell dumpsys deviceidle whitelist +com.example.yourapp
```

**The package is marked protected** while the kiosk is on
(`setUserControlDisabledPackages`, the Samsung force-stop fix). That also makes
the package manager refuse to enable or disable components in it. The library
lifts the protection for the instant it flips the Home alias and restores it —
but if the app does its own `setComponentEnabledSetting`, expect
`Cannot disable a protected package` and do the same lift-and-restore.

**Device owner needs a clean device.** Any account signed in and Android refuses
the grant, with an error that never mentions accounts.

---

## 7. State of the library

Proven on a Samsung Galaxy Tab Active3 (SM-T577U, Android 13): provisioned by
QR, locked in, status strip correct, device owner preserved across app upgrades,
and the gesture and panel working.

Not yet confirmed on hardware:

- **The Home toggle end to end.** Clearing the device-owner preference is
  verified, and both causes of it not being enough are understood and fixed
  (the manifest filter, now on a disableable alias; the protected-package
  conflict, now lifted and restored around the flip). The full press-Home-and-
  land-on-the-launcher test has not been run. Check this first.
- `disable` → reboot → `enable` round trip.
- NFC tag provisioning.
- The Wi-Fi self-heal firing against a genuinely stale link.
