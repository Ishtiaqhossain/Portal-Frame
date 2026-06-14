# Frame — Install & User Guide

Turn your **Meta Portal Go** into a digital photo frame that plays your **Google Photos**
albums whenever it's idle. This guide assumes someone has given you a ready-to-install
**`Frame.apk`** file — you don't need to build anything.

> New here? You'll do three things once: **install** the app, **add your album**, and
> **turn it on as the screensaver**. After that it just runs.

---

## What you'll need

- A **Meta Portal Go**, charged and connected to Wi‑Fi.
- The **`Frame.apk`** file you were given.
- A **computer** (Mac, Windows, or Linux) and a **USB cable** to connect the Portal — this is
  the one-time step to get the app onto the device. (The Portal has no app store, so apps are
  "sideloaded" from a computer.)
- A phone or computer that can open your **Google Photos** album (to get its share link/QR).

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
3. On the computer, run:
   ```
   adb devices          # should list your Portal (tap "Allow" on the Portal if prompted)
   adb install Frame.apk
   ```
   (Use the full path to the file if needed, e.g. `adb install ~/Downloads/Frame.apk`.)
4. When it prints **`Success`**, you're done — a **Frame** icon now appears on the Portal's home
   screen. You can unplug the USB cable.

> Updating later? Use `adb install -r Frame.apk` (the `-r` keeps your settings and album).

---

## Part 2 — First-time setup (on the Portal)

Tap the **Frame** icon on the Portal home screen to open the setup screen.

### Step 1 — Add your Google Photos album
Frame plays a **shared album** from Google Photos. First, create the share link on your phone:

1. Open the **Google Photos** app on your phone.
2. Open the album you want to show. (Don't have one? Create an album and add photos to it.)
3. Tap **Share** → **Create link** (so the album is shared by link).
4. Show its **QR code** if offered, or copy the link (it starts with
   `https://photos.app.goo.gl/…`).

Then, on the Portal:

- **Easiest — scan the QR:** tap **Add album**, allow the camera once, and hold your phone up to
  the Portal. Make the QR **fill your phone screen at full brightness** and hold it **about half a
  meter (1.5 ft) away**, steady, until you see **"Album set ✓"**.
- **No QR? Type the link:** tap **Enter link manually** and paste/type the
  `https://photos.app.goo.gl/…` link, then **Save**.

Once set, the Album card shows the album's **title and a preview of its first photo**, so you can
confirm it's the right one.

### Step 2 — Turn Frame on as the screensaver
1. On the setup screen, tap **Use as screensaver**.
2. In the list that opens, choose **Frame**.
3. Done — your photos will now appear whenever the Portal is idle.

Tap **Done** to leave setup.

---

## Part 3 — Using Frame day to day

- **It runs automatically.** When the Portal sits idle, Frame fades through your album. Touch the
  screen (or talk to the Portal) to go back to normal.
- **Touch controls while photos are showing:**
  - **Swipe left / right** — next / previous photo
  - **Single tap** — dismiss (wake the Portal)
  - **Long‑press** — open the Frame setup screen
- **Add photos anytime.** New photos you add to the shared album in Google Photos show up on the
  Portal automatically (it re-checks each time it goes idle).

### Settings you can change (in the Frame app)
- **Seconds per photo** — how long each photo stays (4s up to 1 minute).
- **Shuffle photos** — random order on/off.
- **Transition** — crossfade speed (Slow / Normal / Fast).
- **Side‑by‑side portraits** — pair two vertical photos to fill the screen.
- **Cinematic motion** — a gentle pan/zoom on each photo.
- **Photo captions** — show when each photo was taken (e.g. "2 months ago").
- **Ambient intelligence** — face‑aware framing, auto‑enhance, ambient color glow, clock &
  weather, night warmth, and "On This Day" memories. Turn any on/off to taste.

### Changing or removing your album
- **Change album** — set a different one (scan/paste a new link).
- **Stop showing photos** — clears your album; Frame falls back to a few built‑in sample photos.

---

## Tips & troubleshooting

- **The album must be shared *by link*.** A private album won't load — in Google Photos use
  **Share → Create link** so the album has a `photos.app.goo.gl` link.
- **Preview says "Couldn't load a preview" / no photos appear.**
  - Check the Portal is on Wi‑Fi.
  - Re‑check the link is the shared‑album link (starts with `https://photos.app.goo.gl/` or
    `https://photos.google.com/share/`).
  - Open the album in a browser to confirm the link is still shared and not empty.
- **The QR won't scan.** The Portal's camera is fixed‑focus and wide — hold the phone **~0.5 m
  away**, **full screen brightness**, and let the QR **fill the on‑screen box**. Still no luck? Use
  **Enter link manually** instead.
- **"Frame" isn't in the screensaver list.** Make sure the app installed (the **Frame** icon is on
  the home screen), then tap **Use as screensaver** again.
- **Videos are skipped.** Frame shows photos only; videos in the album are ignored.
- **Only sample photos show.** That means no album is set (or it couldn't be reached) — open Frame
  and add your album again.

---

*Frame is open‑source software, provided as‑is. It reads only the public shared album you point it
at and shows your photos on the Portal — nothing is uploaded anywhere. See the project README and
SECURITY.md for technical details.*
