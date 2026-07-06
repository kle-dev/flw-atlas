# Flowable Keys — JetBrains Marketplace assets

Ready-to-upload media for the plugin's Marketplace listing. All content uses a neutral
demo project (`flowable-demo` / `com.example.app`) — **no customer data**.

> On the Marketplace, screenshots and video are **not** part of the `plugin.xml`
> `<description>`. Upload the images below in the listing's **Screenshots** section
> (PNG/JPG/GIF accepted), and add any video as a **YouTube link**.

## Screenshots (2560×1600, 16:10 — upload in this order)

| File | Suggested caption |
|------|-------------------|
| `01-key-completion.png` | Context-aware completion — the right model keys at every Flowable API call site, searchable by key or name. |
| `02-cascade-completion.png` | Cascade completion — `operation()` / `value()` complete from the data object resolved earlier in the fluent chain. |
| `03-broken-key-quickfix.png` | Inline inspection — an unknown key is flagged with a “did you mean …?” quick-fix to the closest real key. |
| `04-goto-definition-and-docs.png` | Go to definition & docs — Ctrl/Cmd-click a key to open its model; hover for type, name and file. |
| `05-generate-model-constants.png` | Generate model constants — a typed Java class for every key, kept in sync automatically. |

## Animated demo

| File | Use |
|------|-----|
| `demo-completion.gif` | 1120×520 loop: type a key — or a word from its **name** — pick, done. Use as the lead “screenshot”, or as a README/wiki demo. |

## Regenerating / editing

Sources are in `src/`. They render with headless Chrome (no build tools, no npm deps):

```bash
cd src
# one screenshot (repeat for shot1..shot5)
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless --disable-gpu --hide-scrollbars --force-device-scale-factor=2 \
  --window-size=1280,800 --screenshot=shot1.png "file://$PWD/shot1.html"

# animated demo: render frames 0,1,3,5 then encode (pure Node, no deps)
for f in 0 1 3 5; do "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless --disable-gpu --hide-scrollbars --force-device-scale-factor=2 \
  --window-size=1120,520 --screenshot=frame$f.png "file://$PWD/frames.html?f=$f"; done
node mkgif.js
```

`ide.css` is the shared IntelliJ-look stylesheet used by shot2–shot5; `shot1.html` and
`frames.html` are self-contained. `mkgif.js` is a dependency-free GIF encoder.
