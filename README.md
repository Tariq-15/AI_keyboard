# Chalkboard Keyboard

A grammar-correcting mobile keyboard. Three layers, in order of how much they cost to run:

1. **Grammar and spelling correction** — free, always. On-device LanguageTool, with a
   self-hosted server as fallback.
2. **Next-word prediction** — free, always. Fully on-device n-gram model, no network.
3. **AI rewrite** — optional, bring-your-own-key. Gemini, Groq, OpenAI, or a custom
   OpenAI-compatible endpoint. Inert until the user adds a key.

Android is the reference implementation; iOS follows once Android is complete end to end.

## Status

| Phase | Scope | State |
| --- | --- | --- |
| 1 | Android IME shell — QWERTY, symbols, installable | **verified on emulator** |
| 2 | On-device next-word prediction | **verified on emulator** |
| 3 | Grammar correction + chalk-redraw animation | **verified on emulator** |
| 4 | Settings and onboarding, paper theme | **verified on emulator** |
| 5 | AI providers + secure key storage | **verified on emulator** (UI and gating; no real key exercised) |
| 6 | LanguageTool server deployment | written, not deployed — `backend/` |
| 7 | iOS extension | **written, never compiled** — see `ios/README.md` |
| 8 | Privacy policy, store forms | written — `PRIVACY.md`, `STORE.md` |
| 9 | Stage 1 UI — number row, key hints, emoji, AI tools panel, grammar modes, undo | **verified on emulator**, answers are samples |

### Stage 1 UI: what is real and what is a sample

Phase 9 built every screen of the AI keyboard so the layout and flow can be judged
before any model is wired in. Real: the key layout, the number-row setting, emoji, the
Casual/Formal switch (long-press the grammar key), tap-to-undo after a correction or a
replacement, and the settings screens for assistants, translation targets and layout.

**Not real yet:** every answer in the tools panel comes from `SampleAnswers` in
`ai/AiTool.kt`, is tagged SAMPLE on its card, and ignores the text in the field. The
Casual/Formal setting is stored but does not change what the grammar key does — that key
is still the English-only spelling tier, which rewrites words from other languages
typed in Latin letters (measured: `ami bhalo achi tumi kemon acho` becomes
`ami halo ache yumi lemon ache`). Stage 2 replaces both with a model call.

Two things are deliberately *not* claimed as verified:

- **AI rewrite against a live provider.** The registry, key storage, validation flow and
  keyboard gating are all exercised, but no real API key was used, so no request has ever
  reached Gemini, Groq or OpenAI. The response-parsing paths are unproven.
- **All of iOS.** No macOS or Xcode was available, so not one line of the Swift has been
  compiled. It is a reviewed design with an implementation attached, nothing more.

68 JVM unit tests pass. Phases 1-3 are additionally verified end to end on an Android 35
emulator (`chalkboard` AVD, in `.tools/android-home/avd`): keyboard installs and is
selectable, typing commits through the IME, prediction updates live, and the grammar key
corrects a misspelling with the chalk-redraw before committing.

### What device testing caught that unit tests could not

Phase 9 added two more of the same kind. The symbols page drew its keys into the letter
page's rectangles, because key positions were only recomputed when the view changed
size and a page switch no longer changes it. And on Android 15 every settings title sat
underneath the status-bar clock, because the app bar ignored the edge-to-edge inset.

Three real defects from the first pass, none of which any JVM test would have found:

1. **Both setup cards read "Not enabled"** on a device where the keyboard *was* enabled
   and active. The status check matched the IME id by string suffix, but Android stores
   the short form (`com.chalkboard.keyboard/.ime.KeyboardIME`) while `getClassName()` is
   fully qualified. Now compared as a `ComponentName`.
2. **`<s>` was offered as a suggestion.** The sentence-start marker is a real vocabulary
   entry, and the corpus padding produces the pair `(<s>, <s>)`, making the marker a
   legitimate continuation of itself. Filtered at lookup; regression test added.
3. **The spell checker went blind to typos.** The personal store learns every committed
   word — including the typo the user is about to ask to have fixed — so "the user has
   typed this before" silently meant "this is a real word". A word must now be used
   [OnDeviceSpellChecker.PERSONAL_TRUST_USES] times before it is accepted as deliberate,
   which still protects names and jargon. This one made the headline feature quietly do
   nothing, and the JVM test passed throughout because it ran with no personal store.

Emulator flow (see `tools/device_test.sh`; key coordinates in it are Pixel 6 / 1080x2400
specific):

```
source .tools/env.sh
$ANDROID_HOME/emulator/emulator -avd chalkboard -no-window -no-audio -gpu swiftshader_indirect &
adb wait-for-device && ./gradlew :app:installDebug
adb shell ime enable com.chalkboard.keyboard/.ime.KeyboardIME
adb shell ime set com.chalkboard.keyboard/.ime.KeyboardIME
```

Note: reinstalling the APK resets the system default IME, so re-run the two `ime`
commands after every `installDebug`.

## Prediction dictionary

`app/src/main/res/raw/ngram_dictionary.bin` (7.4 MB) is generated, not hand-written:

```
python tools/build_dictionary.py path/to/eng_sentences.tsv.bz2 \
    -o app/src/main/res/raw/ngram_dictionary.bin
```

**Corpus**: the [Tatoeba](https://tatoeba.org) English sentence export
(`downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2`), released under
**CC BY 2.0 FR**. Attribution is required and belongs in the app's about screen and store
listing (phase 8). Tatoeba is conversational sentences rather than prose or scraped web
text, which is closer to what people type on a phone than a general web corpus is.

Current build: 2,032,690 sentences → 43,533 words, 159,869 bigrams, 321,964 trigrams.

The binary is memory-mapped straight out of the APK, which requires it to be stored
uncompressed — hence `androidResources { noCompress += "bin" }` in the app build file. If
that line is removed the dictionary silently fails to load and prediction falls back to
the personal store alone. The format is documented at the top of `build_dictionary.py`.

## Design system

Theme: **chalkboard correction**. The keyboard is slate, corrections are chalk marks,
settings are paper. Tokens live in exactly two places and must be changed together:

- `app/src/main/java/com/chalkboard/keyboard/ui/theme/` — Compose (`Color.kt`, `Type.kt`,
  `Shape.kt`, `Motion.kt`, `Theme.kt`), used by the containing app.
- `app/src/main/res/values/` — `colors.xml` and `dimens.xml`, used by the View-rendered
  keyboard, which cannot read Compose tokens.

No ad hoc colours, no arbitrary spacing values (4dp base unit only), no touch target
under 44dp.

### Fonts

The type scale expects **Inter** (400/500/600) for all functional text and **Caveat**
(600) as the signature display face at 28sp and up. The binaries are not vendored. Drop
these into `app/src/main/res/font/` and they are picked up with no code change:

```
inter_regular.ttf
inter_medium.ttf
inter_semibold.ttf
caveat_semibold.ttf
```

Until then `ChalkType` falls back to the platform sans and cursive faces — the layout is
correct but the typography is not final. `ChalkType.load(context)` performs the lookup by
resource name; it is called from both `KeyboardIME.onCreate` and `SettingsActivity`,
because the keyboard process can start without the app ever running.

## Building

The entire toolchain lives in `.tools/` inside this project — JDK 17, the Android SDK,
the Gradle distribution, and Gradle's dependency cache. Nothing is installed on `C:`,
which is full. `.tools/` is gitignored; it is ~2.4 GB.

```
source .tools/env.sh      # or:  . .\.tools\env.ps1
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug
```

The env scripts set `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME`, `ANDROID_USER_HOME`
and `ANDROID_AVD_HOME`, so SDK downloads, AVDs, adb keys and the dependency cache all
land in the project rather than under the user profile.

They reach `.tools/` through the junction `H:\chalkboard-tools`. That exists because
Android's `cmdline-tools` batch wrappers interpolate the SDK path unquoted and break on
the space in "Tacos Projects" — `sdkmanager` fails with `'H:\Tacos' is not recognized`.
The junction is only a name; the files are in the project folder. If it is missing, the
env scripts recreate it, or:

```
cmd /c mklink /J "H:\chalkboard-tools" "H:\Tacos Projects\AI_keyboard\.tools"
```

Gradle and AGP handle the spaces fine — only the SDK batch wrappers do not.

Contents: JDK 17.0.13 (Temurin), Android SDK (`platforms;android-35`,
`build-tools;35.0.0`, `platform-tools`, `cmdline-tools;latest`), Gradle 8.9. AGP 8.7
cannot run on JDK 22, which is this machine's default — hence the bundled 17.

The unit tests run on the JVM with no device: `NgramDictionary` works against a plain
`ByteBuffer`, so the tests map the real dictionary file from disk and exercise every
lookup path, including the 16 ms latency budget.

Enable the keyboard from the launcher app, or directly:

```
adb shell ime enable com.chalkboard.keyboard/.ime.KeyboardIME
adb shell ime set com.chalkboard.keyboard/.ime.KeyboardIME
```

## Grammar correction: why there are two tiers

The original plan was to bundle LanguageTool's Java library and run it on-device. That is
not viable. `org.languagetool:language-en:6.4` resolves to **86 artifacts, 59.7 MB of
JARs**, including `grpc-netty-shaded`, `lucene-core`, OpenNLP models and `fastutil`. It is
a server library: Netty and Lucene 5 do not run in an Android IME process, and the dex
size and startup cost are incompatible with a keyboard that must appear in under 100 ms.

So the offline tier is the part that can genuinely be done offline:

- **`OnDeviceSpellChecker`** — spelling only, no network, built on the 43k-word
  dictionary already bundled for prediction, so it costs no extra memory. Edit-distance-1
  candidates (the four Damerau operations) filtered to real words, ranked by personal
  usage, then bigram fit with the previous word, then raw frequency.
- **`RemoteLanguageTool`** — full grammar, `POST /v2/check` against the self-hosted
  server. Only the text being checked is sent, only on an explicit key tap.

`GrammarService` runs the offline tier first and only pays for a round trip if it found
nothing. The active tier is reported rather than silently switched: "Grammar: on-device"
vs "Grammar: server". **With no server URL configured the app makes no network requests
at all** — there is no default endpoint baked in.

## The chalk-redraw animation

An IME cannot draw into the host app's text field, so the correction cannot be animated
in place. `CorrectionAnimation` is a panel the keyboard owns: it shows the sentence,
plays the sequence, and only then commits the corrected text to the field.

Per word — coral underline drawn left-to-right (150 ms, a stroke reveal, not a fade),
cross-fade original → correction (100 ms), underline shifts coral → chalk yellow and
fades (200 ms); words staggered 80 ms. With system animations off it collapses to an
instant swap with no underline, per section 1.4. Corrected words keep a heavier weight
after the colour fades, so the change never depends on colour alone.

## Architecture

```
app/src/main/java/com/chalkboard/keyboard/
  ime/          InputMethodService, canvas-rendered key grid, touch state machine
  prediction/   n-gram dictionary + personal learning store
  grammar/      on-device spelling + remote LanguageTool
  ai/           BYOK providers behind one interface, encrypted key store
  settings/     containing app: home list + grammar, prediction, providers, about
  net/          one read-only question: does this device have a network
  ui/           Notice — the two-part copy every failure is stated in
  ui/theme/     design tokens
  ui/components/suggestion bar, chalk-redraw correction panel, notice banner
backend/        LanguageTool + Caddy, one exposed endpoint
design/         the imported frame gallery this UI is built to
ios/            Swift sources for the extension and containing app (uncompiled)
tools/          dictionary builder, device test script
```

**Bring-your-own-key, throughout.** The app ships with no API keys and no grammar server
address. A fresh install makes zero network requests. `ProviderRegistry` validates a key
against the provider before storing it, so a dead credential is never stored. The tools
key is always on the suggestion bar: with no provider configured it still opens the
panel, whose first line says so and leads straight to the add-a-key screen — a key that
only appeared after setup could never lead anyone to the setup.

The keyboard is drawn on a `Canvas` rather than built from child views: it redraws on
every keypress and a ~40-node view hierarchy in that surface drops frames on low-end
devices. Layout is data (`KeyLayout.kt`), so adding a page or a language is a data change
rather than a rendering change. Because canvas-drawn keys are invisible to accessibility
services, both `KeyboardView` and `SuggestionStrip` expose their keys through
`ExploreByTouchHelper`.

### Why grammar and the AI tools are not keys

They sit on the suggestion bar (`SuggestionBar.kt`) rather than in the key grid. Neither
one types a character and both act on the whole field, so the grid stays the same keys
in the same places whatever is configured. As ordinary `ImageButton`s they also get
focus, ripple and TalkBack without going through `ExploreByTouchHelper` at all.

The tools key swaps the key grid for `ToolsPanel`, and the emoji key swaps it for
`EmojiPanel`. Both panels take exactly the height the keys had, so opening one never
resizes the host app's window. A tool's answer never reaches the text field on its own:
every card ends in an explicit Insert or Replace, and a Replace can be undone by tapping
the notice that follows it.

### Failure copy

Every failure is a [`Notice`](app/src/main/java/com/chalkboard/keyboard/ui/Notice.kt):
a title naming what happened, a body saying what still works, and a severity that picks
the rule colour. The keyboard renders it on slate (`StatusBanner`), settings renders the
same notice on paper (`NoticeCard`), so the two surfaces cannot drift apart in wording.

Two failures that look identical to a socket get opposite copy, which is the whole
reason `net/Connectivity.kt` exists: an unreachable correction server is coral and says
the server is down, while a device with no network at all is muted and says so, because
in the second case there is nothing wrong with the app to go looking for. AI provider
failures are additionally remembered per provider, so a key that was accepted when it
was added and is rejected weeks later explains itself on its own card in settings
instead of in a toast the user had to catch.

## Privacy

- Prediction and the personal learning store never leave the device, and are never
  transmitted anywhere.
- Grammar checking runs on-device by default. On the remote fallback it sends only the
  text being checked, only when the user taps the grammar key — never continuously.
- AI rewrite sends the selected text to the provider the user configured, under that
  provider's own terms, only on an explicit tap.
- The app requests `INTERNET` for the two network paths above, and
  `ACCESS_NETWORK_STATE` — read-only, no prompt — solely to tell "the server is down"
  apart from "this phone has no network", which need opposite messages. Nothing else.
- Neither cloud backup nor Android 12 device-to-device transfer takes any app data:
  `allowBackup="false"` plus an empty `res/xml/data_extraction_rules.xml`. Without the
  second one the encrypted key store and the learned-word database would transfer to a
  new device by default.

Full policy and store data-safety declarations: phase 8.
