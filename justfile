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
