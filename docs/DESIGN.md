# lookee design language

The phone setup page (`core/remote/page.html`) is the reference design. The user approved it on 2026-11-25 ("Love the yellow accent, the icon and everything. Let's follow that pattern across the app"). Every TV screen uses the same palette, shapes and tone, scaled for a 10-foot screen and driven by the D-pad instead of touch.

## Tokens

These match the CSS variables in `page.html`. In the app they live in `ui/theme/` as a single `LookeeColors` and `LookeeShapes` source; screens never hard-code colors.

| Token | Value | Use |
|---|---|---|
| `bg` | `#0D0F12` | App background, behind every screen |
| `surface` | `#15181D` | Cards, panels, dialogs, the OSD |
| `surface-2` | `#1C2027` | Inputs, unset badges, raised items inside a card |
| `line` | `#262B33` | 1 px card borders and dividers |
| `text` | `#EEF0F3` | Primary text |
| `muted` | `#8B93A1` | Secondary text, help, section labels |
| `accent` | `#F2B544` | The yellow: focus, primary buttons, links, progress |
| `accent-ink` | `#1A1404` | Text on accent fills |
| `accent-2` | `#E0752E` | Second stop of the logo gradient (135°, `accent` → `accent-2`) |
| `ok` | `#4CC38A` | Success, "set" badges, the watched mark |
| `err` | `#F06A6A` | Errors |
| Soft fills | 12–18 % alpha of `ok`, `err` or `accent` | Badge and alert backgrounds, focus glow |

Shapes: cards use a 14 dp radius, inputs and buttons 10–12 dp, the logo tile 10 dp on 36 dp, and badges are pills.

## The mark

The mark is a rounded square with the yellow-to-orange gradient and a heavy lowercase **l** in `accent-ink`. It is used for the launcher banner, the top-left of the navigation, the splash screen and the phone page. Next to it, the wordmark **lookee** appears in bold with slightly tight tracking.

## Type

- **Faces.** The phone page uses the system stack (SF on iPhone). The TV bundles **Inter** to get the same tight, modern look on every device, because Roboto on Android 9 reads flatter. This choice is to be confirmed in the design pass.
- **Rules carried over from the page.**
  - Headings use negative tracking (-0.02em).
  - Section labels are small uppercase muted text with +0.08em tracking (the `.card h2` style).
  - Body text is regular weight in `text`, and help text is smaller in `muted`.
- **10-foot scale.** The Mi Box renders at 960×540 dp. Body text is 16–18 sp, card titles 20 sp, screen titles 32–40 sp and the hero title 48 sp. No text is smaller than 13 sp.

## Focus (the TV's replacement for hover and tap)

Focus has to be obvious from across the room, and it uses the page's focus style:

- **Cards and posters:** a 2 dp `accent` border, a soft `accent` glow at 18 % alpha (the page's `box-shadow: 0 0 0 3px`), and a scale of 1.05–1.08. The change animates over about 150 ms.
- **Buttons:** a focused primary button is an `accent` fill with `accent-ink` text. An unfocused one is `surface-2` with `text`, so only the focused control is yellow.
- **Text fields:** an `accent` border and glow, as on the page.
- **Rules:**
  - Exactly one focused element at a time.
  - Focus is never conveyed by color alone; scale and border carry it too.
  - Nothing is reachable only by touch.

## Components (built once in Phase 3, reused everywhere)

| Component | Based on |
|---|---|
| `LookeeCard` (surface, line border, 14 dp radius) | `.card` |
| `SectionLabel` | `.card h2` |
| `PrimaryButton` / `SecondaryButton` | `button` |
| `StatusBadge` (set/unset, quality, "4K", "Disc") | `.status` |
| `Alert` (error/info) | `.alert` |
| `PosterCard`, `BackdropHero`, `CastAvatar`, `EpisodeRow` | New pieces that follow the same tokens |
| `PinDisplay` (the TV side of the phone setup flow) | `.pin` |
| `SuccessCheck` | `.done .check` |

## Motion

Motion is short and quiet: 120–200 ms ease-out for focus, and a 250 ms fade or slide for screen changes. There is no bounce and no parallax that could stutter on the Mi Box's GPU. Every animation has to hold 60 fps on the box. If one doesn't, it is removed instead of degraded.

## Tone of copy

Copy is plain and friendly, and errors say what to do next. For example, the page says "That PIN doesn't match the one on the TV." It never says "Error 401".

## Still to decide in the design pass

The token set above is settled. Still open:

- the layout of Home, Details and the player OSD (mockups first);
- the poster aspect ratio and row density;
- backdrop treatment (gradient scrim strength);
- whether Concerts get their own visual flavour.
