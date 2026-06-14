# Screenshots

Captured on a Meta Portal Go (1280×800). **PII scrubbed** — real photos, album
titles, and album URL tokens are replaced with sample images / generic labels, and
the live camera preview is masked.

| File | Flow |
| --- | --- |
| `01-slideshow.png` | The slideshow screensaver (bundled sample photo + clock/weather overlay). |
| `02-settings-albums.png` | Settings with multiple albums — per-album thumbnail, Stop/Playing switch, and Remove; **Add album** below. |
| `03-add-album.png` | The unified Add screen — scan a QR in the box, or paste a link; **Done**. |
| `04-empty-state.png` | First-run settings with no albums configured. |

Regenerate: capture with `adb … screencap`, then run the redaction over the raw
captures (sample thumbnails from `app/assets/slides`, labels in the bundled Inter font).
