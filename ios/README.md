# iOS — Chalkboard Keyboard

> **Status: written, never compiled.** There is no macOS or Xcode on the machine this was
> developed on, so none of this Swift has been through a compiler, let alone run on a
> device. Treat it as a reviewed design with implementation attached, not as working
> code. The Android side, by contrast, is verified on an emulator.

## Structure

```
KeyboardApp/            containing app — settings, onboarding
  Sources/
    Settings/           SwiftUI, mirrors the Android settings module
    Shared/             shared with the extension via an App Group + shared Keychain
      AIProvider.swift          same protocol shape as Android's interface
      Providers.swift           Gemini + OpenAI-compatible clients
      SecureKeyStore.swift      Keychain wrapper, shared access group
      NgramDictionary.swift     reads the identical .bin format as Android
      DesignTokens.swift        section 1 tokens
KeyboardExtension/
  KeyboardViewController.swift  UIInputViewController
  KeyboardView.swift            key layout, mirrors the Android visual spec
  GrammarClient.swift           LanguageTool over the network (needs Full Access)
  Info.plist                    NSExtension, RequestsOpenAccess
```

## The two constraints that shape everything

### 1. Memory ceiling (~30–60 MB)

A custom keyboard extension is killed well before an app would be. The 7.4 MB n-gram
dictionary must therefore be **memory-mapped, never loaded**. `NgramDictionary.swift`
uses `Data(contentsOf:options:.mappedIfSafe)`, so the same binary format Android ships
works unchanged and the pages stay evictable.

Do not be tempted to parse it into a Swift dictionary at launch: 43k words plus 480k
n-gram records would blow the budget on its own, before UIKit.

### 2. No network without Full Access

`RequestsOpenAccess` gates *all* networking in the extension. So:

- typing and prediction work **without** Full Access — they are local;
- grammar and rewrite **cannot** work without it;
- if it is declined, both keys stay visible and show an explanatory state rather than
  failing silently or disappearing. A key that vanishes reads as a bug; a key that says
  "Enable Full Access in Settings to use grammar correction" reads as a choice.

Onboarding must explain why **before** sending the user to Settings — Apple rejects
extensions that request Full Access without in-app justification. See `STORE.md`.

There is no on-device grammar tier here beyond spelling: the same reasoning as Android
applies, and more forcefully, since the JVM library is not even a theoretical option.

## Sharing state with the containing app

API keys and provider configuration live in a **shared Keychain access group**, and
non-secret settings in an **App Group** `UserDefaults` suite. Storage is never duplicated
between the app and the extension.

Both must be declared in each target's entitlements:

```
keychain-access-groups   $(AppIdentifierPrefix)com.chalkboard.keyboard.shared
com.apple.security.application-groups   group.com.chalkboard.keyboard
```

## Building

Requires macOS with Xcode 15+. There is no `.xcodeproj` in this repository — generate one
(or an SPM package + app target) and add the sources. What is here is the source, the
entitlement requirements, and the plist.

Before shipping, verify on a **low-end device** (an iPhone SE class) that:

1. the extension's resident memory stays under ~40 MB with the dictionary mapped;
2. the keyboard appears within ~100 ms of the field being focused;
3. declining Full Access leaves typing and prediction fully working.

None of those three has been measured.
