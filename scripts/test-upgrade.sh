#!/usr/bin/env bash
set -euo pipefail
# Include exact test failures in the job log as well as downloadable reports.
trap 'find app/build/outputs/androidTest-results -name "*.xml" -type f -exec cat {} \; 2>/dev/null || true; adb logcat -d -s TextToSpeech:D TtsEngines:D AndroidRuntime:E | tail -n 180 || true' ERR
gradle :app:connectedDebugAndroidTest --stacktrace
mkdir -p app/build/reports/upgrade
# UTP uninstalls tested apps; reinstall the base APK and seed explicit persisted data.
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell run-as com.leitura.app mkdir -p files shared_prefs
printf 'preserved' | adb shell run-as com.leitura.app sh -c "'cat > files/upgrade-sentinel.txt'"
cat <<'XML' | adb shell run-as com.leitura.app sh -c "'cat > shared_prefs/leitura.xml'"
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map><string name="books">[{"uri":"content://test/book","title":"Duna","favorite":true,"page":7,"pageCount":30,"lastRead":123}]</string></map>
XML
adb shell run-as com.leitura.app cat shared_prefs/leitura.xml > app/build/reports/upgrade/before.xml
cp app/build/outputs/apk/debug/app-debug.apk app/build/reports/upgrade/base.apk
LEITURA_VERSION_CODE=4 gradle :app:assembleDebug --stacktrace
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell run-as com.leitura.app cat shared_prefs/leitura.xml > app/build/reports/upgrade/after.xml
cmp app/build/reports/upgrade/before.xml app/build/reports/upgrade/after.xml
test "$(adb shell run-as com.leitura.app cat files/upgrade-sentinel.txt | tr -d '\r')" = "preserved"
# Public certificates only. APKs are not retained with the verification reports.
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs app/build/reports/upgrade/base.apk > app/build/reports/upgrade/base-certificate.txt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs app/build/outputs/apk/debug/app-debug.apk > app/build/reports/upgrade/update-certificate.txt
cmp app/build/reports/upgrade/base-certificate.txt app/build/reports/upgrade/update-certificate.txt
rm app/build/reports/upgrade/base.apk
echo "Atualização 3 -> 4 instalada por cima; preferências e arquivos preservados."
