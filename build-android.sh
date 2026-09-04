#!/usr/bin/env bash
# Build a CROSS-PLATFORM Fun Mode jar (desktop .class + Android classes.dex) WITHOUT the Android SDK.
#
# The Mindustry mod template's `deploy`/`jarAndroid` tasks need ANDROID_HOME + d8 in PATH, which this
# machine doesn't have. Instead we dex the desktop jar with a stashed r8.jar (contains D8) and merge the
# resulting classes.dex into a copy of the desktop jar. Desktop reads the .class files and ignores the
# .dex; Android (ART) reads classes.dex. One jar, both platforms.
#
# One-time tooling (already stashed in ~/.local/androiddex):
#   r8.jar      : https://storage.googleapis.com/r8-releases/raw/8.5.35/r8.jar   (D8 8.5.35)
#   android21.jar: https://raw.githubusercontent.com/Sable/android-platforms/master/android-21/android.jar
set -euo pipefail

PROJ="/home/mihail/projects/fun-mode"
DEX_TOOLS="$HOME/.local/androiddex"
OUT_MAIN="/home/mihail/MindustryMods/fun-mode.jar"
OUT_SHARE="/home/mihail/MindustryMods/fun-mode-android.jar"

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk/jdk-17.0.20+8}"
export PATH="$JAVA_HOME/bin:$PATH"

R8="$DEX_TOOLS/r8.jar"
ANDROID="$DEX_TOOLS/android21.jar"
[ -f "$R8" ]      || { echo "missing $R8 (see header for URL)"; exit 1; }
[ -f "$ANDROID" ] || { echo "missing $ANDROID (see header for URL)"; exit 1; }

cd "$PROJ"
echo ">> gradlew jar"
./gradlew jar -q

DESK="$PROJ/build/libs/fun-modeDesktop.jar"
DEPS="$(find "$HOME/.gradle" -iname 'Mindustry-*.jar' 2>/dev/null | head -1)"
echo ">> deps jar: $DEPS"

WORK="$(mktemp -d)"
echo ">> dexing (min-api 21)"
java -cp "$R8" com.android.tools.r8.D8 --min-api 21 --lib "$ANDROID" --classpath "$DEPS" --output "$WORK" "$DESK"

echo ">> merging classes.dex into a copy of the desktop jar"
cp "$DESK" "$WORK/out.jar"
( cd "$WORK" && jar uf out.jar classes.dex )
cp "$WORK/out.jar" "$OUT_MAIN"
cp "$WORK/out.jar" "$OUT_SHARE"
rm -rf "$WORK"

echo ">> done. Cross-platform jar (desktop + Android):"
ls -la "$OUT_MAIN"
