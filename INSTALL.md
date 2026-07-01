# Frame — Install & User Guide

Turn your **Meta Portal Go** into a digital photo frame. It can play your **Google Photos** or
**iCloud** shared albums, and/or photos you **push straight from your phone** over Wi‑Fi — whenever
it's idle. You install a ready-made `Frame-*.apk` — you don't need to build anything.

> New here? You'll do three things once: **install** the app, **add your photos** (a shared album,
> a phone push, or both), and **turn it on as the screensaver**. After that it just runs.

---

## Get the app

Download the latest **`Frame-vX.Y.Z.apk`** from the project's **Releases** page:

- **<https://github.com/Ishtiaqhossain/Portal-Frame/releases/latest>**

Grab the `.apk` asset under **Assets** (the `.apk.sha256` file next to it is an optional checksum
to verify the download). Save it to the computer you'll use in Part 1.

---

## What you'll need

- A **Meta Portal Go**, charged and connected to Wi‑Fi.
- The **`Frame-*.apk`** you downloaded above.
- A **computer** (Mac, Windows, or Linux) and a **USB cable** to connect the Portal — this is
  the one-time step to get the app onto the device. (The Portal has no app store, so apps are
  "sideloaded" from a computer.)
- A phone or computer that can open your **Google Photos** or **iCloud** album (to get its share link/QR).

If a friend or family member is more comfortable with computers, Part 1 is the only part
they need to help with — everything after that happens on the Portal's screen.

---

## Part 1 — Install Frame on the Portal (one time, from a computer)

Installing uses a free Google tool called **ADB** (Android Debug Bridge).

### 1. Get ADB on your computer
- Download **Android SDK Platform-Tools** from
  <https://developer.android.com/tools/releases/platform-tools> and unzip it.
- The `adb` program is inside that folder. Open a Terminal / Command Prompt in that folder.

### 2. Turn on Developer Mode + USB debugging on the Portal
1. On the Portal, open **Settings**.
2. Find the **build number** (usually under **About** / **System**) and **tap it 7 times** until
   it says "You are now a developer."
3. Go into the new **Developer options** and turn on **USB debugging**.

### 3. Connect and install
1. Connect the Portal to the computer with the USB cable.
2. On the Portal, accept the **"Allow USB debugging?"** prompt (tap **Allow**).
3. On the computer, run (use the actual file name you downloaded):
   ```
   adb devices                              # should list your Portal (tap "Allow" on the Portal if prompted)
   adb install ~/Downloads/Frame-v1.0.0.apk
   ```
4. When it prints **`Success`**, you're done — a **Frame** icon now appears on the Portal's home
   screen. You can unplug the USB cable.

> Updating later? Download the newer `Frame-*.apk` from Releases and run
> `adb install -r ~/Downloads/Frame-vX.Y.Z.apk` (the `-r` keeps your settings and album).

---

## Part 2 — First-time setup (on the Portal)

Tap the **Frame** icon on the Portal home screen to open the setup screen.

### Step 1 — Add your album
Frame plays a public **shared album** from either **Google Photos** or **iCloud**. First, create
the share link on your phone:

**Google Photos:**
1. Open the **Google Photos** app, open the album you want (or create one and add photos).
2. Tap **Share** → **Create link** (so the album is shared by link).
3. Show its **QR code** if offered, or copy the link (starts with `https://photos.app.goo.gl/…`).

**iCloud (Apple Photos):**
1. In the **Photos** app, open the album, tap the people/share icon → **Shared Album**, or open an
   existing Shared Album.
2. Turn on **Public Website** (in the Shared Album's settings) and **copy the link** (starts with
   `https://www.icloud.com/sharedalbum/…`).

Then, on the Portal, tap **Add album**. That one screen lets you either:

- **Scan the QR:** allow the camera once and hold your phone up to the Portal — make the QR **fill
  your phone screen at full brightness**, held **about half a meter (1.5 ft) away**, steady, until
  you see **"Album added ✓"**. (Google Photos shows a QR directly; for iCloud, generate a QR from
  the link or just paste it — see below.)
- **Paste the link:** in the same screen, type/paste the shared-album link into the field
  (`https://photos.app.goo.gl/…` or `https://www.icloud.com/sharedalbum/#…`) and tap **Add album**.

Once set, the Album card shows the album's **title and a preview of its first photo**, so you can
confirm it's the right one.

### Or — add photos straight from your phone (no album needed)
You can also push photos onto Frame from **any phone on the same Wi‑Fi**, without a shared album —
handy for adding a few photos on the spot:

1. On the Portal's setup screen, find **Add photos from a phone**. It shows a **QR code**.
2. On your phone, **scan that QR** with the camera app. It opens a simple web page in your browser —
   there's **no app to install**.
3. Tap **Choose photos**, pick photos from your camera roll, and tap **Add to Frame**.

They appear on the frame **right away** and stay in the rotation, alongside any albums. Because it's
just your phone's browser, it works from an **iPhone or Android**, and **nothing goes to any cloud** —
the photos travel directly from your phone to the Portal over your home Wi‑Fi. Only a phone that
scanned the on-screen QR can add photos.

> **Manage them:** the **Photos from phones** card on the setup screen shows how many you've added;
> tap **Manage photos** to see them all in a grid and **delete** any (or **Remove all**).

### Step 2 — Turn Frame on as the screensaver
1. On the setup screen, tap **Use as screensaver**.
2. In the list that opens, choose **Frame**.
3. Done — your photos will now appear whenever the Portal is idle.

Tap **Done** to leave setup.

#### Make it stick on Portal / Portal+ (optional, recommended)
On some Portal models (notably **Portal+**, which has a rotating screen) the built-in launcher
**resets the screensaver back to its own** whenever the home screen is recreated — for example
after you **rotate the screen**. If Frame keeps reverting, grant it permission to keep itself set,
once, over ADB:

```bash
adb shell pm grant com.portalhacks.frame android.permission.WRITE_SECURE_SETTINGS
```

Then open Frame and tap **Use as screensaver** again. Frame will now re-assert itself whenever the
launcher tries to take the slot back, so it survives rotations and reboots. (Until you grant this,
Frame falls back to the normal system picker and the launcher may still reset it.)

---

## Part 3 — Using Frame day to day

- **It runs automatically.** When the Portal sits idle, Frame fades through your album. Touch the
  screen (or talk to the Portal) to go back to normal.
- **Touch controls while photos are showing:**
  - **Swipe left / right** — next / previous photo
  - **Single tap** — dismiss (wake the Portal)
  - **Long‑press** — open the Frame setup screen
- **Add photos anytime.** New photos you add to the shared album (in Google Photos or iCloud) show
  up on the Portal automatically (it re-checks each time it goes idle). Or **push a photo from your
  phone** on the spot — scan the QR under **Add photos from a phone** and pick from your camera roll;
  it appears on the frame right away.

### Settings you can change (in the Frame app)
The setup screen has two sides: **Set up** (screensaver, albums, add photos from a phone) and
**Customize** — the playback/look options, grouped into collapsible sections (**Playback**, **Look
& motion**, **Clock & night**, **Extras**). Tap a group to open it. The options include:

- **Seconds per photo** — how long each photo stays (4s up to 1 minute).
- **Shuffle photos** — random order on/off.
- **Transition** — crossfade speed (Slow / Normal / Fast).
- **Side‑by‑side portraits** — pair two vertical photos to fill the screen.
- **Cinematic motion** — a gentle pan/zoom on each photo.
- **Photo captions** — show when each photo was taken (e.g. "2 months ago").
- **Ambient intelligence** — face‑aware framing, auto‑enhance, ambient color glow, clock &
  weather, night warmth, and "On This Day" memories. Turn any on/off to taste.

### Managing albums
- **Multiple albums** — tap **Add album** again (scan or paste) to add more; the slideshow plays
  photos from all of them, merged together. Each album shows its title and a thumbnail of its first
  photo so you can tell them apart.
- **Stop one temporarily** — flip an album's **Playing** switch to off. It stays in the list (link
  kept) but its photos are paused; flip it back on anytime.
- **Remove one** — tap **Remove** next to an album, then **Confirm**. The others keep playing.
- **Remove all** — remove each album; with none left, Frame falls back to the built‑in samples.

---

## Tips & troubleshooting

- **The album must be shared *publicly by link*.** A private album won't load — in Google Photos
  use **Share → Create link**; in iCloud, enable the Shared Album's **Public Website**.
- **Preview says "Couldn't load a preview" / no photos appear.**
  - Check the Portal is on Wi‑Fi.
  - Re‑check the link is a shared‑album link — Google Photos `https://photos.app.goo.gl/…` /
    `https://photos.google.com/share/…`, or iCloud `https://www.icloud.com/sharedalbum/#…`.
  - Open the album link in a browser to confirm it's still shared and not empty.
- **The QR won't scan.** The Portal's camera is fixed‑focus and wide — hold the phone **~0.5 m
  away**, **full screen brightness**, and let the QR **fill the on‑screen box**. Still no luck?
  Paste the link into the field on the same **Add album** screen instead.
- **"Frame" isn't in the screensaver list.** Make sure the app installed (the **Frame** icon is on
  the home screen), then tap **Use as screensaver** again.
- **Frame keeps reverting to the Portal's own screensaver (e.g. after rotating the screen).** The
  launcher reclaims the screensaver slot. Grant the one-time permission in
  [Make it stick on Portal / Portal+](#make-it-stick-on-portal--portal-optional-recommended) above.
- **Adding photos from a phone doesn't work.** Make sure the **phone and the Portal are on the same
  Wi‑Fi** (and the network allows devices to talk to each other — some guest/public networks block
  this). Re-scan the QR from **Add photos from a phone** each session, and add photos in the browser
  page it opens (don't bookmark it — the link is single-use and only valid while that QR is shown).
- **Videos are skipped.** Frame shows photos only; videos in the album are ignored.
- **Only sample photos show.** That means no album is set (or it couldn't be reached) — open Frame
  and add your album again.

---

*Frame is open‑source software, provided as‑is. It reads only the public shared album you point it
at, plus any photos you push to it directly from a phone on your own Wi‑Fi, and shows them on the
Portal — nothing is uploaded to any cloud or outside server. See the project README and SECURITY.md
for technical details.*
