#!/usr/bin/env bash
# Installs the debug build on the connected device or emulator, enables the keyboard,
# and drives it through the acceptance criteria for phases 1-3.
#
#   source .tools/env.sh && tools/device_test.sh
#
# Exercises: typing, next-word prediction, the grammar key, and the correction commit.
# Screenshots land in .tools/screenshots/.
set -euo pipefail

IME="com.chalkboard.keyboard/.ime.KeyboardIME"
PKG="com.chalkboard.keyboard"
SHOTS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.tools/screenshots"
mkdir -p "$SHOTS"

shot() {
    adb exec-out screencap -p > "$SHOTS/$1.png"
    echo "  captured $1.png"
}

echo "== waiting for a device"
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'

echo "== installing"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk

echo "== enabling the keyboard"
adb shell ime enable "$IME"
adb shell ime set "$IME"
adb shell ime list -s

echo "== opening the settings activity (it has a text field to type into)"
adb shell am start -n "$PKG/.settings.SettingsActivity"
sleep 3
shot 01-settings

echo "== focusing the try-it field and typing"
# Tap the try-it field under the settings list, then type through the IME.
adb shell input tap 540 1465
sleep 2
shot 02-keyboard-shown

adb shell input text "I%shave%steh%scat"
sleep 2
shot 03-typed-with-misspelling

echo "== screenshots in $SHOTS"
echo
echo "Check by eye:"
echo "  02 - slate keyboard, QWERTY, suggestion strip above it"
echo "  03 - suggestions updating as words are committed"
echo "  then tap the grammar key and confirm 'teh' becomes 'the' with the underline sweep"
