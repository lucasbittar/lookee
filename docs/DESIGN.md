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

## Layouts (agreed 2026-11-25)

The mockups live on the [layouts canvas](https://claude.ai/artifact/VpFhyAz42B5QG4wgGs3Tai). The user's decisions:

- **Home banner follows focus.** The big banner at the top shows the backdrop, title, details and synopsis of whichever card is focused.
- **Details hides the top navigation.** It's a pushed screen with a small breadcrumb; Back returns to the row it came from.
- **Card shapes are mixed.** Wide 16:9 stills for anything in progress (Continue watching, Series up next), and 2:3 portrait posters for browsing (Recently added, the Movies wall).
- **Row spacing.** There are at least 32 dp (64 px at 1080p) between the bottom of a row, including the captions under its cards, and the next row's label. That way a focused card's scale and glow never touch the next row. This was the user's one note on the first mockup.
- **Each category gets its own look** (see below). Focus is the same yellow everywhere, so the remote always feels the same.

## One line per label (agreed 2026-11-25)

- **Card titles and captions never wrap.** At rest they're cut with an ellipsis. When the card is focused, overflowing text scrolls back and forth as a marquee: it starts after a short pause and loops slowly (about 30 dp/s). In Compose this is `Modifier.basicMarquee()`, enabled only while focused.
- **Big titles (Home banner, Details) stay on one line.** They shrink to fit, down to 36 dp, and use the marquee only if they still don't fit.
- **Synopses are the exception.** They're prose, so they clamp: 2 lines on the Home banner and episode rows, 3 on Details.

## Every card has a picture (agreed 2026-11-25)

The user's rule: no empty cards. TMDB artwork comes first. Otherwise (lessons, personal videos, unmatched titles) the card shows a frame grabbed from the video, about 10% in, skipping black frames. A plain surface remains only when there's no video at all (disc folders, ISOs).

## Category looks (approved 2026-11-25)

| Category | Mood | Tint (ambient glow and labels only) | Layout | Type |
|---|---|---|---|---|
| Movies | "Cinema", like a projector beam | Accent yellow | A poster wall (2:3, seven across) with one "Sort · …" button that cycles the order | Inter |
| Series | "Up next" | Blue `#6E9CF5` | Wide cards with the next episode, plus per-episode season progress segments | Inter |
| Concerts | "Live", like stage lights | Rose `#E8608C` | Large gig-poster cards with the artist name over the image, Blu-ray/DVD/ISO badges, and "More artists" chips | Artist names on the cards in Bebas Neue |
| Tutorials | "Workshop", like a notebook | Teal `#4FC3D9` | No posters: folders on the left, numbered lessons on the right with duration, progress and a CC badge | Lesson numbers and paths in JetBrains Mono |
| Discover | "Stream", like a signal | Violet `#9B7BF0` | Search first, then trending rows. A "Stream" badge marks titles to stream, and "On your drive" marks titles you already have. Picking a title lists streams best first (quality badge, one-line release name, size, seeder signal bars). Buffering is a full screen with a ring, three plain steps and live speed/peers | Sizes and stats in JetBrains Mono |

**Screen headings (revised 2026-11-25):** every category heading is the same stack, with the kicker in the category tint ("NOW SHOWING", "UP NEXT", "LIVE", "WORKSHOP", "STREAM") above the title in Inter. The categories differ in mood, layout and card type, not in heading fonts. Headings stay pinned on Movies, Series, Tutorials and Discover, where the content is a grid or rows that scroll under them. On Concerts and Settings they scroll away with the content.

**Search:** each media screen has a search box at the right of its heading. It's a pill on the D-pad, so passing over it doesn't open the keyboard. OK turns it into a text field. The keyboard's Search key, Back or Down return to the pill, and **Clear** appears beside it while a search is active.
- Movies, Series and Concerts filter the titles already on screen, live, ignoring case and accents.
- Tutorials searches every lessons folder through the core (`searchLessons`). Each hit shows its folder underneath, and OK on a folder opens it.

## Still to decide in the design pass

The token set above is settled. Still open:

- backdrop treatment (gradient scrim strength);
- nothing: Discover was approved on 2026-11-25, which completes the design. Bebas Neue and JetBrains Mono are bundled like Inter (all OFL). Fraunces was dropped when headings went back to Inter.
