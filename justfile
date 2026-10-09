app_id := "app.scratchpad.launcher.debug"
activity := "app.olauncher.MainActivity"

# Assemble debug APK
build:
    ./gradlew assembleDebug

# Run unit tests (fast, no device/emulator needed)
test:
    ./gradlew testDebugUnitTest

# Android lint
lint:
    ./gradlew lintDebug

# Install debug build on connected device/emulator and launch it
run:
    ./gradlew installDebug
    adb shell am start -n "{{app_id}}/{{activity}}"

# Remove build outputs
clean:
    ./gradlew clean

# Cut a new signed release: bump version, build, tag, push, publish to GitHub.
# F-Droid picks up the new tag on its own (UpdateCheckMode: Tags).
# CalVer YYYY.M.N, N counting releases within the month from 0. Usage: just release [VERSION]
release version="":
    #!/usr/bin/env bash
    set -euo pipefail
    month=$(date +%Y.%-m)
    version="{{version}}"
    [ -n "$version" ] || version="$month.$(git tag -l "v$month.*" | wc -l | tr -d ' ')"
    git rev-parse -q --verify "refs/tags/v$version" >/dev/null && { echo "Tag v$version already exists"; exit 1; }
    current_code=$(grep -oE 'versionCode [0-9]+' app/build.gradle | grep -oE '[0-9]+')
    new_code=$((current_code + 1))
    # Changelog shown on the GitHub release and in F-Droid: a hand-written changelogs/<code>.txt if present,
    # otherwise commit subjects since the last tag
    changelog="fastlane/metadata/android/en-US/changelogs/$new_code.txt"
    mkdir -p "$(dirname "$changelog")"
    [ -s "$changelog" ] || git log --pretty='- %s' "$(git describe --tags --abbrev=0)..HEAD" > "$changelog"
    [ -s "$changelog" ] || { rm "$changelog"; echo "No commits since the last tag - nothing to release"; exit 1; }
    sed -i '' "s/versionCode .*/versionCode $new_code/" app/build.gradle
    sed -i '' "s/versionName \".*\"/versionName \"$version\"/" app/build.gradle
    git add app/build.gradle "$changelog"
    git commit -m "Bump to $version"
    git push
    source ~/keys/scratchpad-launcher/credentials.env
    export RELEASE_KEYSTORE_PATH=~/keys/scratchpad-launcher/release.keystore
    export RELEASE_KEYSTORE_PASSWORD="$STOREPASS"
    export RELEASE_KEY_ALIAS="$KEY_ALIAS"
    export RELEASE_KEY_PASSWORD="$KEYPASS"
    # clean: stale incremental Kotlin output made v1.1.13 non-reproducible for F-Droid (#6)
    ./gradlew clean assembleRelease
    git tag "v$version"
    git push origin "v$version"
    mkdir -p release-artifacts
    cp app/build/outputs/apk/release/app-release.apk "release-artifacts/ScratchpadLauncher-v$version.apk"
    gh release create "v$version" "release-artifacts/ScratchpadLauncher-v$version.apk" \
        --repo mstcgalis/scratchpad-launcher --title "v$version" --notes-file "$changelog"
