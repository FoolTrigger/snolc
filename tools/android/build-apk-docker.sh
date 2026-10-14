#!/usr/bin/env bash
# ==============================================================================
# Скрипт автоматической сборки APK на VPS через Docker
# Запуск: bash tools/android/build-apk-docker.sh
# ==============================================================================

set -euo pipefail

echo "===> Проверка наличия Docker..."
if ! command -v docker &>/dev/null; then
    echo "Установка Docker..."
    curl -fsSL https://get.docker.com | sh
fi

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUTPUT_DIR="$PROJECT_ROOT/dist-apk"
mkdir -p "$OUTPUT_DIR"

echo "===> Запуск сборки APK в изолированном контейнере Android SDK..."
docker run --rm \
    -v "$PROJECT_ROOT/android":/project \
    -v "$OUTPUT_DIR":/output \
    -w /project \
    cimg/android:2024.01-ndk \
    bash -c "gradle wrapper && ./gradlew assembleDebug && cp app/build/outputs/apk/debug/*.apk /output/snolc-stealth.apk"

echo "=========================================================================="
echo " APK УСПЕШНО СОБРАН!"
echo " Файл сохранен в: $OUTPUT_DIR/snolc-stealth.apk"
echo "=========================================================================="
