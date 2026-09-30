# Kiosk

Locks an Android device into a single app. Drop it into any app you build and
get the same behaviour every time, without rebuilding the mechanism.

```java
protected void onCreate(Bundle b) {
    super.onCreate(b);
    setContentView(R.layout.main);

    Kiosk.attach(this);      // that is the whole integration
}
```

From then on the device belongs to that app: Home and Recents stop working, the
status bar is gone, the lock screen is gone, the app is Home so a reboot lands
straight back in it, and the system is told not to force-stop it. The power menu
is deliberately left working, because without it the device cannot be shut down
or restarted at all.

The first time it runs on a device it asks you to set a master password. That
password is what opens the kiosk controls afterwards, and it is the only thing
standing between a curious person and the settings.

Minimum Android 8.0 (API 26). Built against API 34.

---

## Does it change my app?

No. Your app looks and behaves exactly as you built it.

The library owns the device, not the screen. It has no activity of its own that
you see, no theme, no layout, and it never wraps or replaces your views. What it
adds is:

- **Dialogs**, and only when someone opens the controls with the gesture.
- **A small status strip** in one corner, showing battery and Wi-Fi, because
  locking the device hides the system status bar. It floats above your app
  rather than taking part in layout, so it cannot push your views around, and
  it is not clickable so it cannot swallow a touch meant for your app. One call
  turns it off.

That is the whole visible footprint. It works the same whether your app is
views, Compose, or a WebView, because the touch watching goes through the window
callback rather than any view of its own.

---

## Using it in an app

Three ways in, all from the same build:

**The AAR.** Build it and copy the file:

```bash
gradle :kiosk:assembleRelease
# kiosk/build/outputs/aar/kiosk-release.aar -> your app's libs/
```

```groovy
dependencies { implementation files('libs/kiosk-release.aar') }
```

**Your machine's local Maven.** Better if you have several apps using it:

```bash
gradle :kiosk:publishToMavenLocal
```

```groovy
repositories { mavenLocal() }
dependencies { implementation 'com.mk:kiosk:1.0.0' }
```

**JitPack.** Push this repo to GitHub, tag a release, and JitPack runs the same
publication:

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.<you>:AndroidKiosk:1.0.0' }
```

Nothing goes in your manifest. The library declares its own service, job
service, admin receiver, boot receiver and permissions, and the manifest merger
folds them into yours. Verified: the demo app declares none of them and all four
components reach its merged manifest.

Your launcher activity does need one thing, so that the device returns to your
app after a reboot rather than to Android's launcher:

```xml
<intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.HOME" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

---

## Granting device owner

Android does not let an app make itself device owner. It is granted once per
device, and only while the device is factory-fresh with no account signed in.
Until it is granted, `Kiosk.attach` is harmless: the app runs normally and
`Kiosk.status().isDeviceOwner` is false.

There are three routes, and two of them need no cable.

### QR, no cable

The one to use on a device you are setting up away from a computer.

```powershell
cd tools
.\make-provisioning-qr.ps1 -Apk ..\demo\build\outputs\apk\release\demo-release.apk `
                           -Url http://192.168.1.50:8000/demo.apk `
                           -Wifi MyNetwork -WifiPassword secret
```

Serve the APK from the same machine (`python -m http.server 8000` in the folder
holding it), then on the device: factory reset, and **tap the very first setup
screen six times**. A QR scanner opens. Show it the code. Android joins the
Wi-Fi, downloads the APK, installs it and makes it device owner, all before the
setup wizard has finished.

The payload carries a checksum of your signing certificate, which is what stops
the device installing something else from that URL. It is taken from the
signature and not the file, so rebuilding the APK does not invalidate the QR —
confirmed here: the checksum was byte-identical across two rebuilds.

The APK itself still comes over the network. The QR carries the instructions,
not the app, so that host has to be reachable from the device.

### NFC, no cable

Same payload in the form a tag carries:

```powershell
.\make-provisioning-qr.ps1 ...     # works out the checksum, writes the JSON
.\make-provisioning-nfc.ps1        # converts it to properties text
```

Write that text to the tag as a MIME record of type
`application/com.android.managedprovisioning` — the free NFC Tools app does it.
Then factory reset the device and hold the tag against its back while the first
setup screen is showing. No tapping six times, no scanning.

**Which tag.** What matters is capacity and NDEF, not the radio type. NFC-A is
just the radio layer: NTAG21x and MIFARE Ultralight are all NFC-A, and most of
them are far too small. The script works the size out and tells you which tags
fit rather than guessing:

| | user memory | full payload (491 B) | with Wi-Fi (592 B) |
|---|---|---|---|
| MIFARE Ultralight | 48 B | no | no |
| Ultralight C / NTAG213 | 144 B | no | no |
| **NTAG215** | 504 B | **yes** | no |
| **NTAG216** | 888 B | **yes** | **yes** |

So an NTAG215 is enough if you connect the device to Wi-Fi by hand in the setup
wizard, and you want an NTAG216 if you put the Wi-Fi credentials in the payload.

Avoid MIFARE Classic. It is big enough, but only Android devices with an NXP
chipset read it, so a tag that works on one phone silently does nothing on the
next.

NFC provisioning is also more device-dependent than QR — some OEM setup wizards
handle the tag and some ignore it. Try QR first to confirm the payload itself is
good, then move to NFC if you are setting up devices repeatedly.

NFC is worth the trouble when you are provisioning devices repeatedly. For one
device, the QR is less work.

### adb, over a cable

```powershell
.\provision.ps1                                       # the demo
.\provision.ps1 -Apk ..\path\to\your.apk -Package com.example.app
```

It checks that exactly one device is attached, warns you about signed-in
accounts before Android refuses over them, installs, grants, and then reads the
state back from the device rather than trusting the success line.

The usual reason this fails is an account. Android refuses device owner while
any account exists on the device, and says so in a way that does not mention
accounts.

---

## Putting it in one of your own apps

Four steps, and only the last one is per-app work.

**1. Depend on it** — any of the three ways above.

**2. Add the HOME intent filter** to your launcher activity, so a reboot lands
back in your app instead of Android's launcher.

**3. Call it** from that activity's `onCreate`:

```java
Kiosk.attach(this);
```

Give yourself a way into `KioskPanel.open(this)` — a long press on a logo, a
hidden corner, whatever suits the app. The master password is the real gate; the
gesture just keeps it out of the way.

**4. Make a QR for that app.** This is the part that cannot be shared between
apps, because the payload names your package and carries a checksum of *your*
signing certificate:

```powershell
.\make-provisioning-qr.ps1 -Apk path\to\yourapp-release.apk `
                           -Url http://<your-ip>:8000/yourapp.apk `
                           -Component com.example.yourapp/com.mk.kiosk.KioskAdminReceiver
```

The receiver class stays `com.mk.kiosk.KioskAdminReceiver` — it comes from the
library. Only the package before the slash changes.

**Two things to know before you plan around this.**

Only one app per device can be device owner. A tablet locked to app A cannot
also host app B as owner; to switch, release device owner from inside app A (or
factory reset) and provision app B.

The checksum is of the signing key. The demo is signed with the debug key, so
its QR only matches debug-signed builds. When you sign a real app with a release
key, regenerate the QR — and after that it keeps working across rebuilds,
because the key does not change.

---

## Getting out again

**Seven taps in the top-left corner**, within three seconds. That asks for the
master password and opens the panel. It is the same in every app that uses this
library, on every screen, and `Kiosk.attach` installs it — there is nothing to
wire up and nothing app-specific to remember.

The gesture watches touches through the window callback rather than covering the
app with a view. Nothing is consumed and nothing is hidden, so it works whether
the app is views, Compose or a WebView, and it cannot interfere with what is on
screen. A small corner plus seven taps is well out of reach of accidental
discovery, while still being reachable from any screen — which matters when the
screen you need to leave is the one misbehaving.

### Changing the gesture

Set it before `Kiosk.attach`:

```java
KioskGesture.configure(new KioskGesture.Config()
        .taps(5)
        .corner(KioskGesture.Corner.BOTTOM_RIGHT)
        .within(5000)          // milliseconds for the whole run
        .size(0.2f));          // corner size, as a fraction of the screen

Kiosk.attach(this);
```

| | default | notes |
|---|---|---|
| `.taps(n)` | 7 | floor of 2 |
| `.corner(c)` | `TOP_LEFT` | also `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT`, `ANYWHERE` |
| `.within(ms)` | 3000 | floor of 500 |
| `.size(f)` | 0.15 | clamped to 0.05 – 0.5 |
| `.enabled(false)` | on | turns the built-in gesture off entirely |

Fewer taps or a bigger corner makes it easier to reach and easier to trip over
by accident; the defaults are picked so a user poking around will not find it.
`ANYWHERE` is convenient but means seven taps in a row anywhere on screen will
open the prompt, which a real user can hit by accident on a busy screen.

`KioskGesture.describe()` gives the current setting in plain words, for a help
screen. `KioskGesture.openPanel(activity)` opens the panel directly if you would
rather use a trigger of your own, and `KioskPanel.open(activity)` is the plain
entry point.

### The password is per app

It lives in that app's own private storage, so each app you build has its own,
set the first time it runs on a device. In practice that means one password per
device, because only one app per device can be device owner.

There is no recovery. Forgetting it means a factory reset, or `adb shell pm
clear <package>` if USB debugging happens to be on. Write it down somewhere
before you hand a device over.


Nothing here needs adb once the app is installed. Every control is in the app.

The panel shows whether device owner is granted and whether the device is
currently locked, and offers:

- **Unlock for maintenance** — Home and Recents work again so you can reach
  Android. It re-locks the moment you come back to the app, and expires on its
  own after fifteen minutes, so a device cannot be left open by forgetting.
- **Lock the device again** — ends the unlock now.
- **Stop Home opening this app** - see below.
- **Show / hide battery & Wi-Fi**
- **Keep the screen on / let it sleep**
- **Wi-Fi settings** — opens Android's own Wi-Fi screen from inside the lock, by
  letting Settings through the allowlist just long enough. Coming back closes it.
- **Reconnect Wi-Fi** — see below.
- **Change master password**
- **Release device owner** — hands the device back completely. This is the way
  out without a factory reset, and it cannot be undone from the app: granting
  device owner again means resetting and provisioning again.

The two destructive ones have to be held, not tapped. Leaving the kiosk is not
something to do by brushing the screen.

If you would rather build your own UI, `Kiosk.status()` gives you everything the
panel shows, and `suspend` / `resume` / `release` are public.

---

## The Home button

By default the app becomes Home. That is what makes a reboot land straight back
in the kiosk, and it means the Home button cannot be used to get out.

It is also the part people most often want off, because it changes the device
even when the kiosk is unlocked or switched off - press Home during maintenance
and you are back in the app rather than at the launcher.

Turn it off from the panel ("Stop Home opening this app") or in code:

```java
Kiosk.setBecomeHome(this, false);
```

With it off, the device keeps its normal launcher: Home leaves the app and a
reboot goes to the launcher. Lock task still blocks Home while the device is
actually locked, so the kiosk is not weakened while it is running - what changes
is what happens when it is unlocked, switched off, or freshly rebooted.

Leave it on for an unattended device. Turn it off for one that has another job
as well.

## The Wi-Fi fix

A tablet was found holding a strong signal and a valid IP address, with Android
reporting the network as validated, while resolving no hostname at all. The
association had gone stale overnight and nothing on the device noticed — the app
simply sat there offline until somebody turned Wi-Fi off and on by hand.

Turning the radio off and on clears it, and a device owner is still allowed to
do that. Ordinary apps on Android 10 and up are not, which makes this one of the
few things only this arrangement can fix.

```java
KioskNetwork.reconnectIfStale(context, failureMessage, (ok, msg) -> ...);
```

Call it from your error handler. It looks at the message, acts only on the
name-resolution failures that indicate a stale link rather than a real outage,
and backs itself off: at most one attempt in five minutes, three in a row.
`KioskNetwork.noteHealthy()` after a request that worked resets the count.
`KioskNetwork.reconnect` does it now, without the back-off, for a button.

---

## Staying alive

Samsung force-stops apps overnight, taking any keep-alive service with them.
Three things push back, and all three are automatic:

- A foreground service with a screen-on watcher.
- A `JobScheduler` watchdog that survives the process being killed.
- `setUserControlDisabledPackages`, which tells the system to leave the package
  running. Device owner only, Android 11 and up.

An overnight test on an unplugged tablet came back still locked in the app.

One thing is not in the APK and cannot be: the battery optimisation whitelist is
device-level state, applied once per device. If you factory reset, re-apply it:

```bash
adb shell dumpsys deviceidle whitelist +com.example.app
```

---

## API

| | |
|---|---|
| `Kiosk.attach(activity)` | The whole integration. Call from `onCreate`. |
| `Kiosk.apply(activity)` | Re-applies everything. Safe to call repeatedly. |
| `Kiosk.suspend(activity)` | Maintenance unlock, fifteen minutes. |
| `Kiosk.resume(activity)` | Ends the unlock now. |
| `Kiosk.release(activity)` | Gives device owner back. One way. |
| `Kiosk.openWifiSettings(activity)` | Android's Wi-Fi screen from inside the lock. |
| `Kiosk.disable(activity)` / `.enable(activity)` | Kiosk off and on, keeping device owner. Survives reboot. |
| `Kiosk.setBecomeHome(activity, on)` | Whether Home returns to this app. |
| `Kiosk.showStatusOverlay(activity, on)` | Battery and Wi-Fi strip. On by default. |
| `Kiosk.keepScreenOn(activity, on)` | Remembered across restarts. |
| `Kiosk.status(context)` | `isDeviceOwner`, `isLocked`, `isSuspended`, `keepAwake`, `provisionCommand`. |
| `KioskGesture.openPanel(activity)` | Opens the panel as the corner gesture does. |
| `KioskPanel.open(activity)` | Ready-made settings panel behind the password. |
| `KioskPassword.require(activity, then)` | Ask for the password, run `then` if right. |
| `KioskNetwork.reconnectIfStale(...)` | Self-healing Wi-Fi. |

`Kiosk.attach` registers its own lifecycle callbacks, so nothing goes in
`onResume` or `onStop`. The one optional extra is `Kiosk.applyKeepScreenOn(this)`
in `onResume` if you use that setting.

Every device-owner call is attempted separately and swallows its own failure, so
an OEM refusing one policy does not cost you the rest.

---

## State of it

Built and verified on a machine: the AAR and demo APK compile, the manifest
merge puts all four components into a host app that declares none of them, and
the QR and NFC payloads carry the right extras with a checksum that survives a
rebuild.

**Run on hardware.** Provisioned by QR onto a Samsung Galaxy Tab Active3
(SM-T577U, Android 13): it installed itself, locked in, showed the status strip,
and kept device owner across an app upgrade.

Still unproven: the NFC tag route, the reversible off/on across a reboot, and
the Wi-Fi self-heal firing against a genuinely stale link.

---

## Layout

```
kiosk/     the library
demo/      an app that does nothing but lock the device into itself
tools/     provision.ps1              adb, over a cable
           make-provisioning-qr.ps1   QR, no cable
           make-provisioning-nfc.ps1  NFC tag, no cable
```
