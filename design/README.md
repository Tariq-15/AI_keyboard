# Design source

Imported from the Claude Design project **Chalkboard Frame Gallery App**
(`24d744df-ed04-49fc-a973-3dc38d4f278a`) with the `claude_design` MCP.

| File | What it is |
| --- | --- |
| `Chalkboard Frame Gallery.dc.html` | The gallery: annotated mockups at 390dp, sections A–E |
| `Keyboard.dc.html` | The keyboard component the gallery imports 12 times |
| `support.js` | The `dc-runtime` bundle both files load. Generated; do not edit |

Open the gallery in a browser to view it — everything is inline except the two Google
Fonts links, so it renders offline with fallback faces.

## Section → code

| Frame | Implemented in |
| --- | --- |
| B1–B2 suggestion strip, placeholder, weight-only ranking | `ui/components/SuggestionStrip.kt` |
| B3–B6 grammar ✓ and AI ✦ beside the strip | `ui/components/SuggestionBar.kt` |
| B4 chalk-redraw sequence | `ui/components/CorrectionAnimation.kt`, `ui/theme/Motion.kt` |
| B7 symbols page, B8 shift vs. caps lock | `ime/KeyLayout.kt`, `ime/KeyboardView.kt` |
| C1 settings home | `settings/SettingsActivity.kt` |
| D1–D4 provider screens | `settings/ProviderScreens.kt` |
| E1/E4 keyboard notices | `ui/components/StatusBanner.kt`, `ui/Notice.kt` |
| E2/E3 per-provider failures | `ai/ProviderRegistry.kt`, `settings/ProviderScreens.kt` |
| Tokens throughout | `ui/theme/`, `res/values/colors.xml`, `res/values/dimens.xml` |

## Local edits to the imported files

The cloud project still holds the originals; these copies were corrected on import and
have **not** been pushed back.

Rendering and correctness:

- `suggestions='["you\'re",…]'` terminated its own single-quoted HTML attribute at the
  backslash-escaped apostrophe, so those three frames parsed a broken value. Now
  `&apos;`, which the HTML parser resolves to `'` before the JSON is read.
- The demo underline was `position:absolute` inside a parent with no positioned
  ancestor, so it resolved against the initial containing block rather than the word.
- `grammarBg` was a ternary with the same value in both branches.
- B1 and B5 were the same frame with different captions.
- Tapping ✓ in the idle frames ran the demo state machine with nothing to show, which
  recoloured the key as if a correction had happened.

Claims that were not true:

- "12.6:1" for text.chalk on bg.slate; it is 13.0:1. "11.8:1" for text.ink on
  surface.paperCard; it is 14.2:1. Both still clear AAA.
- "every key measures ≥44×44dp" — ten keys and nine gaps do not fit at 44dp on a 390dp
  screen; row-1 keys are 33.8dp wide. The caption now says so and names the exception.
- Caps lock was distinguished from shift by fill colour alone in B8. It now changes
  glyph as well (⇧ → ⇪), which is what the implementation always did.

Specified but not buildable as drawn:

- E1 and E4 drew their banners over the host app's message thread. An IME has no access
  to another app's views. The `Keyboard` component gained `notice-title` /
  `notice-body` / `notice-severity` props so the frames show the notice where it will
  actually appear, inside the keyboard's own panel.
- E1 also withdrew the ✓ button when the correction *server* was unreachable. On-device
  spelling does not need that server and keeps working, so withdrawing the button would
  claim a larger outage than happened. The button stays and the notice says which tier
  is down.
