# Specter Scope

An entertainment "ghost hunting" camera for Android (minSdk 29). It does **not** detect anything paranormal:
the tracked stick figures, radar blips, spirit-box words and "thermal" colours come from a simulation that
reacts to your phone's real sensors (camera motion, magnetometer, microphone).

## Features
- SLS-style camera: structured-light dot field with tracked stick figures (labels, distance, confidence)
- Night-vision and simulated thermal (false-colour) views, plain camera view
- Radar driven by the compass, EMF meter (magnetometer) with LED bar, graph and calibration
- Spirit box (synthesized radio sweep, random words, optional speech), EVP recorder with playback
- Photos and video with the overlay burned in (video has no sound), torch, front/back camera
- Auto-capture on detection, vibration and proximity beeps, investigation log (shareable),
  in-app gallery, settings (sensitivity, entity count, units, colour theme)

## Get the APK (pick one)

**A. GitHub Actions (no installs):** create a new GitHub repository, upload everything in this folder
(including the hidden `.github` folder), open the **Actions** tab, run **Build APK**, then download
`SpecterScope-apk` from the finished run. Unzip it to get `app-release.apk`.

**B. Android Studio:** open this folder, let Gradle sync, then *Build > Build APK(s)*.

**C. Command line** (JDK 17 + Android SDK with platform 34):

    ./gradlew assembleRelease     # APK: app/build/outputs/apk/release/app-release.apk

The release build is signed with the standard debug key so it installs directly. To install, copy the APK to
the phone and open it (allow "install unknown apps" for your file manager or browser when asked).

## Desktop tests
`tools/CoreTest.java` checks the pure-Java core (stick-figure rig and simulation engine):

    javac -d out app/src/main/java/app/specterscope/Skeleton.java app/src/main/java/app/specterscope/Engine.java tools/CoreTest.java
    java -cp out CoreTest poses.png
