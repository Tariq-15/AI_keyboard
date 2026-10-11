# Privacy policy — Chalkboard Keyboard

Last updated: 1 August 2026

Chalkboard is a keyboard. Keyboards see everything you type, so the only thing worth
saying is what happens to it. This policy is written to be checkable: every claim below
corresponds to code you can read in this repository.

## The short version

**Nothing you type is transmitted unless you tap a specific key, and then only the text
you are working on goes to a service you chose.** There is no background upload, no
analytics, no advertising identifier, and no account.

## What stays on your device, always

- **Everything you type.** The keyboard has no continuous transmission of any kind.
- **Next-word prediction.** The dictionary is bundled in the app and read locally.
  Prediction never touches the network.
- **Learned words.** Words you type are counted locally to improve suggestions. This is
  stored in an app-private database, excluded from device backup
  (`android:allowBackup="false"`), and never leaves the phone. You can delete it at any
  time: **Settings → Learned words → Delete learned words**.
- **Spelling correction.** The on-device tier corrects spelling using the bundled
  dictionary, with no network access.

## What is sent, and only when you ask

### Grammar checking (only if you configure a server)

The app ships with **no grammar server address**. If you leave it empty, no grammar
request is ever made.

If you enter an address, then **when you tap the grammar key**, the text in the field
(up to 600 characters either side of the cursor) is sent to that server for checking.
Nothing else is sent: no device identifier, no app version, no history, no account.

The reference deployment (`backend/`) keeps no records. Requests are stateless, no text
is written to disk, and access logs record the request line only — never the body.

### AI rewrite (only if you add your own API key)

The app ships with **no API keys** and contacts no AI provider. If you add your own key
for Gemini, Groq, OpenAI, or a custom endpoint, then **when you tap the rewrite key**,
the text in the field is sent to that provider.

That request goes to the provider directly, under **that provider's own privacy policy
and terms**, using your account and your quota. We are not an intermediary: the text does
not pass through any server we operate, and we cannot see it.

Your API key is stored encrypted on your device (Android `EncryptedSharedPreferences`,
backed by the hardware keystore), is excluded from backup, and is never logged or
transmitted anywhere except to the provider it belongs to.

## What we never do

- We do not log keystrokes.
- We do not collect analytics, crash telemetry containing typed text, or advertising IDs.
- We do not sell, share, or monetise any data. There is nothing to sell.
- We have no servers that receive your text except the grammar server *you* configure.

## Permissions

The app requests two permissions, both of them normal permissions granted at install
with no prompt.

`INTERNET` is needed for the two user-initiated features above. A keyboard asking for
internet access is a reasonable thing to be suspicious of — the two code paths that use
it are `grammar/RemoteLanguageTool.kt` and `ai/`, and both are reachable only from an
explicit key tap.

`ACCESS_NETWORK_STATE` is read-only and used for one question: whether this device has a
network at all. It decides only which message you are shown when a request fails —
"couldn't reach the correction server" or "no internet connection" — because a socket
cannot tell those apart and blaming the wrong one sends you looking for the wrong fix.
It reveals no address, no network name and nothing about your traffic. The only code
that uses it is `net/Connectivity.kt`, which observes nothing except that answer.

## Children

The app is not directed at children and collects nothing that could identify anyone.

## Changes

Material changes to this policy will be published in this repository and reflected in
the app's store listings before they take effect.

## Contact

Open an issue in this repository.
