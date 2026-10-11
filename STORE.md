# Store submission notes

Both stores scrutinise keyboards harder than ordinary apps, and correctly so: an IME can
see every character a person types. The defensible position is not "we promise" — it is
"here is the code path, and here is when it runs."

## The one claim that matters

**Text leaves the device only on an explicit key tap, and only the text being worked on.**

Reviewers can verify this without taking our word for it:

| Question | Answer |
| --- | --- |
| Where can a network call originate? | `grammar/RemoteLanguageTool.kt` and `ai/` — nowhere else |
| What triggers them? | `KeyAction.FixGrammar` and `KeyAction.AiRewrite` in `ime/KeyboardIME.kt` |
| Can typing trigger one? | No. Nothing in `grammar/` or `ai/` is reachable from a text-changed callback |
| What about prediction? | `prediction/` has no network code at all |
| Default configuration? | No grammar server address and no API keys ship with the app, so a fresh install makes **zero** network requests |

## Android — Play Console Data Safety

Declare accurately. The correct answers depend on the *capability* shipped, not the
default configuration.

**Data collected:** none — we operate no servers that receive user data by default.

**Data shared with third parties:** yes, conditionally.

| Data type | Collected | Shared | Purpose | Optional? |
| --- | --- | --- | --- | --- |
| Other user-generated content (text being checked) | No | **Yes**, if the user configures a grammar server | App functionality | **Yes** — off by default |
| Other user-generated content (text being rewritten) | No | **Yes**, if the user adds an AI provider key | App functionality | **Yes** — off by default |

Both are user-initiated, optional, and not used for tracking. Encryption in transit:
yes (HTTPS). Data deletion: nothing is retained to delete.

Do **not** declare "no data shared" merely because the default is off. The capability
ships in the binary, and Play treats an available-but-unconfigured transmission path as
sharing. Under-declaring is the common way keyboard apps get pulled.

**Permissions declared:** `INTERNET` and `ACCESS_NETWORK_STATE`. Both are normal
permissions with no runtime prompt and neither needs a declaration form. If asked about
the second: it is read once per failed request, to tell an unreachable server apart from
a device with no network so the two get the messages they deserve. It observes no
address, no network identity and no traffic. See `net/Connectivity.kt`.

**Backup and transfer:** both refused. `allowBackup="false"` with an empty
`data_extraction_rules.xml` (Android 12+) and `backup_rules.xml` (11 and below). Answer
"no" to data-transfer questions without qualification — the encrypted key store and the
learned-word database are excluded by declaration on every supported version, not just
by the ones where `allowBackup` is honoured.

## iOS — App Review notes for Full Access

Paste into the review notes field:

> This keyboard works fully without Full Access: typing, next-word prediction and
> on-device spelling correction all run locally with no network.
>
> Full Access is requested for two optional features that cannot work without network
> access: (1) grammar checking against a LanguageTool server the user configures, and
> (2) rewriting text using an AI provider the user supplies their own API key for.
>
> Both run only when the user taps a specific key. Neither is enabled by default — the
> app ships with no server address and no API keys. If Full Access is declined, the
> keyboard remains fully functional for typing and prediction, and those two keys show an
> explanatory state rather than failing.
>
> No keystroke logging occurs at any point. The only data transmitted is the text the
> user has explicitly asked to have checked or rewritten.

Also required:

- **App Privacy** section: declare the same two conditional data flows as Android.
- `NSExtension` → `RequestsOpenAccess = true` in the extension's `Info.plist`.
- Onboarding must explain *why* Full Access is needed **before** sending the user to
  Settings. Apple rejects extensions that request it without in-app justification.

## Listing copy

Voice rules from the design spec apply to the store listing too: no superlatives, no
"AI-powered ✨", say what things do.

**Short description**

> A keyboard that fixes your grammar. Prediction and spelling work offline, always free.

**Full description opening**

> Chalkboard corrects spelling on-device, predicts your next word without ever going
> online, and — if you want it — rewrites text using an AI provider you bring your own
> API key for.
>
> Nothing you type is transmitted unless you tap a key that asks for it. Prediction and
> the words it learns from you never leave your phone.

Do not claim "your data never leaves your device" without qualification. It is false the
moment the user configures a grammar server, and a reviewer who reads the binary will
find the code path.

## Screenshots

The chalk-redraw correction is the thing worth showing: original text, coral underline,
corrected word. Capture mid-sequence (~250 ms in) rather than the settled state.
