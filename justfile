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

# Cut a new signed release: bump version, build, tag, push, publish to GitHub,
# and add a matching F-Droid build entry (pushed to the fdroiddata MR branch).
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
    commit_sha=$(git rev-parse "v{{version}}")
    python3 - "{{version}}" "$new_code" "$commit_sha" <<'PYEOF'
    import re, sys
    version, code, sha = sys.argv[1:4]
    path = "fdroid/app.scratchpad.launcher.yml"
    text = open(path).read()
    entry = (
        f"  - versionName: {version}\n"
        f"    versionCode: {code}\n"
        f"    commit: {sha}\n"
        f"    subdir: app\n"
        f"    gradle:\n"
        f"      - yes\n\n"
    )
    text = text.replace("\nAllowedAPKSigningKeys:", "\n" + entry + "AllowedAPKSigningKeys:", 1)
    text = re.sub(r"CurrentVersion: .*", f"CurrentVersion: {version}", text)
    text = re.sub(r"CurrentVersionCode: .*", f"CurrentVersionCode: {code}", text)
    open(path, "w").write(text)
    PYEOF
    git add fdroid/app.scratchpad.launcher.yml
    git commit -m "Add F-Droid build entry for v{{version}}; bump CurrentVersion to {{version}}"
    git push
    fdroid_dir=$(mktemp -d)
    git -c credential.https://gitlab.com.helper= -c credential.https://gitlab.com.helper="!glab auth git-credential" \
        clone --depth 1 --branch app.scratchpad.launcher --filter=blob:none --sparse \
        https://gitlab.com/dgalis/fdroiddata.git "$fdroid_dir"
    git -C "$fdroid_dir" sparse-checkout set --skip-checks metadata/app.scratchpad.launcher.yml
    cp fdroid/app.scratchpad.launcher.yml "$fdroid_dir/metadata/app.scratchpad.launcher.yml"
    git -C "$fdroid_dir" add metadata/app.scratchpad.launcher.yml
    git -C "$fdroid_dir" -c user.name="Daniel Gális" -c user.email="danielgalis21@gmail.com" \
        commit -m "Add build entry for v{{version}}; bump CurrentVersion to {{version}}"
    git -C "$fdroid_dir" -c credential.https://gitlab.com.helper= -c credential.https://gitlab.com.helper="!glab auth git-credential" \
        push https://gitlab.com/dgalis/fdroiddata.git HEAD:app.scratchpad.launcher
    rm -rf "$fdroid_dir"
