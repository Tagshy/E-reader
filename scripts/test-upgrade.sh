#!/usr/bin/env bash
set -euo pipefail
gradle :app:connectedDebugAndroidTest --stacktrace
mkdir -p app/build/reports/upgrade
# Seed an explicit sentinel after tests and compare it across an in-place APK upgrade.
adb shell run-as com.leitura.app sh -c "'echo preserved > files/upgrade-sentinel.txt'"
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
