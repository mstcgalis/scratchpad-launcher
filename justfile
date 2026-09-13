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

# Cut a new signed release: bump version, build, tag, push, and publish to GitHub.
# Usage: just release 1.1.9
release version:
    #!/usr/bin/env bash
    set -euo pipefail
    current_code=$(grep -oE 'versionCode [0-9]+' app/build.gradle | grep -oE '[0-9]+')
    new_code=$((current_code + 1))
    sed -i '' "s/versionCode .*/versionCode $new_code/" app/build.gradle
    sed -i '' "s/versionName \".*\"/versionName \"{{version}}\"/" app/build.gradle
    git add app/build.gradle
    git commit -m "Bump to {{version}}"
    git push
    source ~/keys/scratchpad-launcher/credentials.env
    export RELEASE_KEYSTORE_PATH=~/keys/scratchpad-launcher/release.keystore
    export RELEASE_KEYSTORE_PASSWORD="$STOREPASS"
    export RELEASE_KEY_ALIAS="$KEY_ALIAS"
    export RELEASE_KEY_PASSWORD="$KEYPASS"
    ./gradlew assembleRelease
    git tag "v{{version}}"
    git push origin "v{{version}}"
    mkdir -p release-artifacts
    cp app/build/outputs/apk/release/app-release.apk "release-artifacts/ScratchpadLauncher-v{{version}}.apk"
    gh release create "v{{version}}" "release-artifacts/ScratchpadLauncher-v{{version}}.apk" \
        --repo mstcgalis/scratchpad-launcher --title "v{{version}}" --notes "Release v{{version}}"
