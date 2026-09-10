# Building and deploying with Docker

The repo's own [`console/stride/run.sh`](../console/stride/run.sh) expects a
developer laptop: a JDK on the path, an Android SDK in `~/Library/Android/sdk`,
and `adb` installed. This is the other route — the toolchain lives in a
container, and the machine you run it from needs nothing but Docker.

That machine does not have to be the one you sit at. A headless Linux box on
the same network as the treadmill works fine, and is what this was written on.

**What you need**

* Docker, and an account in the `docker` group.
* About 3 GB of disk: ~1.5 GB image, ~1 GB of Gradle dependency cache.
* Network access to `dl.google.com`, `services.gradle.org` and
  `repo.maven.apache.org` the first time.
* The treadmill reachable over adb-over-Wi-Fi — see
  [INSTALL.md § 1.4](INSTALL.md#14-enable-adb-over-wi-fi).

---

## Quick start

```sh
tools/docker-build.sh            # builds the image if it is missing, then the APK
STRIDE_DEVICE=192.168.1.50:5555 tools/docker-deploy.sh
```

`docker-build.sh` prints where the APK landed and the SHA-256 of the
certificate it was signed with. `docker-deploy.sh` installs it, launches it,
and tails `Stride:I` until you stop it.

First run is a few minutes and downloads a lot. After that a build is seconds.

---

## The container

One image, `stride-build:latest`, defined in
[`tools/docker/Dockerfile`](../tools/docker/Dockerfile). It carries JDK 17,
the Android SDK (platform 34, build-tools 34.0.0, platform-tools) and `adb`.
The same image does both jobs — building needs the JDK and the SDK, deploying
needs `adb`, and keeping them together means one thing to build and one thing
to keep current.

`tools/docker-build.sh` builds it automatically when it is missing. To rebuild
it deliberately — after editing the Dockerfile, or to pick up a new SDK:

```sh
tools/docker-build.sh image
# or, the same thing by hand:
docker build -t stride-build:latest tools/docker
```

### Changing what is in it

Three build arguments, all pinned rather than floating, so that rebuilding
this image next year gives you the same toolchain instead of whatever Google
is shipping that week:

```sh
docker build -t stride-build:latest tools/docker \
  --build-arg ANDROID_PLATFORM='platforms;android-35' \
  --build-arg ANDROID_BUILD_TOOLS='build-tools;35.0.0' \
  --build-arg CMDLINE_TOOLS_ZIP=commandlinetools-linux-11076708_latest.zip
```

The platform has to match `compileSdk` in
[`console/stride/app/build.gradle.kts`](../console/stride/app/build.gradle.kts).
If you raise one, raise the other.

### Why it runs as your user

Both scripts pass `--user $(id -u):$(id -g)`, so the APK and the Gradle
outputs written into the mounted repo belong to you and not to root. That has
one consequence worth knowing about, because the symptom is baffling:

> A JVM finds the debug keystore through `user.home`, and `user.home` comes
> from the passwd entry for the uid it is running as. Your host uid has no
> entry inside someone else's base image — or worse, collides with one that
> does and points at a home the container cannot write. Android Gradle then
> quietly generates a **throwaway debug key on every build**. Two builds of
> identical source get different signatures, and neither can update the other
> on the console.

`docker-build.sh` fixes this twice over: it sets `ANDROID_USER_HOME` explicitly
(which is what Android Gradle actually consults), and it generates a passwd
file giving your uid a real home (which is what everything else consults). You
do not have to do anything, but if you write your own container invocation,
this is the trap.

---

## What persists on the host

The container is disposable. Two directories are not, and both are mounted in:

| Path | What | Override with |
|---|---|---|
| `~/.gradle-stride` | Gradle dependency cache, ~1 GB. Downloaded once. | `STRIDE_GRADLE_HOME` |
| `~/.android` | The debug keystore, and the adb key the console has authorised. | `ANDROID_USER_HOME` |

`~/.android` is the standard Android location on purpose. A build from this
container and a build from Android Studio on the same machine sign with the
same key and can therefore replace each other on the console.

Delete `~/.gradle-stride` freely; it costs a download. **Think before deleting
`~/.android`** — see below.

---

## The signing key, which matters more than it looks like it should

Android identifies an app by its signing key. An APK signed with a different
key than the one already installed is a *different app*, and the update is
refused outright:

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

The only way across is `adb uninstall`, which takes the console's settings,
the people on it, and the whole walk history with it.

So: **back up `~/.android/debug.keystore`.** It is the difference between
updating the console for years and starting it over. If you move to a new
build machine, copy that file across rather than letting the new one generate
its own.

Every build prints its signer so you can see at a glance whether it matches:

```
>>> built .../app-debug.apk (2.0M)
    signer Signer #1 certificate SHA-256 digest: f274956772b6…
```

If a deploy is refused anyway, `docker-deploy.sh --replace` handles it
properly: it backs up `shared_prefs` and `files` off the console first,
uninstalls, installs, and puts the data back. That backup works because a
debug build is `debuggable` and can be read into with `run-as`. On a release
build it cannot, and the script says so *before* uninstalling anything.

---

## Deploying

```sh
tools/docker-deploy.sh                # install, launch, follow the log
tools/docker-deploy.sh --backup-only  # just save the console's data, no install
tools/docker-deploy.sh --replace      # uninstall first; restores the backup after
tools/docker-deploy.sh --shell        # an adb shell on the console
```

The device address defaults to `192.168.10.10:5555`; set `STRIDE_DEVICE` to
yours. Backups land in `.backups/` in the repo, which is gitignored.

The container runs with `--network host` so it can reach the treadmill on your
LAN, and mounts `~/.android` so adb presents the key the console has already
authorised. Without that mount every run would generate a fresh key and the
console would raise its "Allow USB debugging?" prompt again — with nobody
standing in front of it, which rather defeats the point of adb over Wi-Fi.

### Release builds

```sh
tools/docker-build.sh release
```

Needs the four `stride.keystore.*` properties in
`console/stride/local.properties`; they are documented at the top of
`console/stride/app/build.gradle.kts`. Without them Gradle produces an
unsigned APK that adb refuses, and the script says which lines are missing
rather than letting you find out at install time.

A debug build is `debuggable`: anything else on the console can read this
app's stored settings, broker password included, and the WebView DevTools
socket is open. Fine on a bench. Not what you want on a machine that stands in
a hallway for years.

---

## When it goes wrong

**`adb: device offline`, or `failed to connect`.** The console dropped the TCP
connection. Restarting the adb server clears it:

```sh
docker run --rm --network host stride-build:latest adb kill-server
tools/docker-deploy.sh
```

**`unauthorized`.** The console is showing an "Allow USB debugging?" dialog
that nobody has accepted. Tap **Allow**, and tick *Use by default for this USB
device* so it does not ask again. Check the fingerprint against yours:

```sh
awk '{print $1}' ~/.android/adbkey.pub | base64 -d | md5sum \
  | awk '{print toupper($1)}' | sed 's/../&:/g; s/:$//'
```

**`logcat -s Stride:I` prints nothing while the app is plainly running.** This
console ships with `log.tag=E`, so nothing below error level is recorded at
all, and its audio driver spams the ring buffer hard enough to evict the rest
within a minute. `docker-deploy.sh` sets both of these for you; to do it by
hand:

```sh
adb shell setprop persist.log.tag.Stride I
adb logcat -G 16M
```

**The app starts but says `control board not on the USB bus`.** Usually it came
up before USB enumerated — relaunch it. If it persists, check that
`com.ifit.eru` is still disabled ([INSTALL.md § 1.3](INSTALL.md#13-stop-ifit-locking-you-out-again))
and that the board is on the bus at all:

```sh
tools/docker-deploy.sh --shell
cat /sys/kernel/debug/usb/devices | grep -A2 '^T:'   # look for ICON Generic HID
```

**Reinstalling loses the USB permission.** Expected — the grant is per install.
The console will ask again on first launch.

---

## In CI, or over ssh with no terminal

Both scripts work without a tty. `docker-deploy.sh` ends by following the log,
which simply exits at once when there is nothing to attach to; everything
before it has already run. To skip the follow entirely, use `--backup-only`
plus your own install step, or stop the script after `install`.
