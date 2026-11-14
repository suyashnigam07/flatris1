# Home CCTV – Android TV app (Sony Bravia / Google TV)

Watch your Wi-Fi CCTV cameras live on the TV, over your home network, with the TV remote.

## Features
- Full-screen live view. **◀ / ▶** or **CH+ / CH−** switch cameras, **1–9** jump to a camera, **OK** shows info.
- **Grid view** with up to 9 cameras. The highlighted tile plays sound, and **OK** opens it full screen.
- **Wi-Fi scan** finds cameras that have open RTSP ports (554/8554) or answer ONVIF discovery.
- **Check this IP** lists which ports a device has open, so you can see whether your camera offers a local stream.
- **Find stream path** tries 35+ common RTSP paths, with Basic and Digest password support.
- Reconnects on its own if the Wi-Fi drops. Uses low-latency buffering.
- Camera details are saved only on the TV.

## ⚠️ Important: Qubo cameras
The app plays the camera's **local RTSP stream**, the standard used by NVRs, VLC and other CCTV software.
The Qubo mobile app streams through **Qubo's private cloud**, which has no public API, so no third-party app can use that route.

- Open the Qubo app → your camera → **Settings**, and look for **RTSP / ONVIF / NVR / Third-party** and turn it on.
  The RTSP username, password and path are usually shown there.
- If your model has no such option, open this app → **Scan Wi-Fi** → enter the camera's IP → **Check this IP**.
  (You can find the IP in your router's list of connected devices or in the Qubo app's device info.)
  If it reports no RTSP port, that camera can only be viewed through the Qubo cloud. Your options then are:
  1. Install the Qubo phone app on the TV (sideload its APK). It may work with the remote or a mouse.
  2. Cast your phone screen to the Bravia (Google Cast / screen mirroring) while the Qubo app is open.

## Install on the Sony Bravia
1. Download `HomeCCTV-TV.apk` from this repository's **Releases** page, or from the **Actions** build artifacts.
2. Use one of these:
   - **USB stick:** copy the APK onto it, plug it into the TV, and open it with a file manager (e.g. "File Commander" from Play Store).
   - **Downloader app** (Play Store): type the APK's release download link.
   - **Send Files to TV** app from phone to TV.
3. On the TV, allow installing: **Settings → Apps → Security & restrictions → Unknown sources** → enable it for the file manager / Downloader app you used.
   On Google TV: **Settings → System → About → tap "Android TV OS build" 7 times**, then enable unknown sources under **Apps → Security**.
4. Open **Home CCTV** from the TV's apps row.

## Adding a camera
**Scan Wi-Fi for cameras** → select the camera → enter username/password → **Find stream path** → **Test** → **Save**.
Or enter a full URL such as `rtsp://admin:password@192.168.1.50:554/live/ch00_0`.

## Building
`./gradlew assembleRelease` (needs the Android SDK). GitHub Actions builds it automatically. See `.github/workflows/cctv-tv-app.yml`.
