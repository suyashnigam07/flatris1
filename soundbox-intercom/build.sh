#!/bin/bash
# Builds SoundboxIntercom.apk with plain SDK tools (aapt, javac, dx, zipalign, apksigner).
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
OUT=build
rm -rf $OUT && mkdir -p $OUT/gen $OUT/classes
aapt package -f -m -J $OUT/gen -M AndroidManifest.xml -S res -I "$SDK" -F $OUT/unsigned.apk
javac -source 8 -target 8 -bootclasspath "$SDK" -d $OUT/classes \
  $(find src $OUT/gen -name '*.java') 2>&1 | grep -v 'bootstrap classpath\|^warning\|^1 warning' || true
dalvik-exchange --dex --min-sdk-version=23 --output=$OUT/classes.dex $OUT/classes
(cd $OUT && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk
[ -f intercom.keystore ] || keytool -genkeypair -keystore intercom.keystore -storepass intercom \
  -keypass intercom -alias intercom -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Soundbox Intercom" >/dev/null 2>&1
apksigner sign --ks intercom.keystore --ks-pass pass:intercom --key-pass pass:intercom \
  --out SoundboxIntercom.apk $OUT/aligned.apk
apksigner verify --verbose SoundboxIntercom.apk | head -4
