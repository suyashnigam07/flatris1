# Soundbox Intercom (Android)

Speak into your phone and hear it on a Bluetooth speaker in another room.

**Download:** [`SoundboxIntercom.apk`](SoundboxIntercom.apk) (Android 6.0+)

## Install
1. Copy `SoundboxIntercom.apk` to the phone and tap it.
2. If asked, allow "Install unknown apps" for your browser/file manager.
3. If Play Protect warns about an unknown developer, tap **More details → Install anyway**.

## Use
1. Switch on the soundbox downstairs. In the app tap **Bluetooth settings** and pair/connect it (only needed once).
2. The status dot turns green: *Connected to: <speaker name>*.
3. **Hold** the big button and speak; release to stop. (Or tick *Tap to start / tap again to stop*.)
4. A "ding-dong" plays first so the listener knows a message is coming (can be turned off).
5. Use **Voice boost** and the **Volume** button if it's too quiet.

The app refuses to talk when no Bluetooth speaker is connected, so your voice never comes
out of the phone's own speaker.

## Build
Needs `aapt`, `dalvik-exchange`, `zipalign`, `apksigner`, `android-sdk-platform-23` (Ubuntu packages) and a JDK:
```
./build.sh
```
