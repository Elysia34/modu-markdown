#!/usr/bin/env bash
# 墨读 · 不使用 Gradle 的打包脚本（aapt2 + javac + d8 + zipalign + apksigner）
#
# 依赖：
#   - JDK 17（必须，d8 / apksigner 需要）
#   - Android SDK：build-tools 34.0.0、platforms/android-34
#
# 用法：
#   export ANDROID_SDK_ROOT=/path/to/android-sdk
#   export JAVA_HOME=/path/to/jdk-17
#   ./scripts/build-apk.sh
#
# 签名（可选，正式包请设置）：
#   export MODU_KEYSTORE=/path/to/release.keystore
#   export MODU_KEYSTORE_PASS=******  MODU_KEY_ALIAS=modu  MODU_KEY_PASS=******
# 未设置时脚本会在 build/ 下自动生成一个本地调试签名（不要用于发布）。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
SRC="$APP/src/main"
BUILD="$ROOT/build"
DIST="$ROOT/dist"
PKG="com.wbx.mdreader"

to_native() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

# ---------- 环境检查 ----------
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ]; then
    ANDROID_SDK_ROOT="$ANDROID_HOME"
  elif [ -f "$ROOT/local.properties" ]; then
    ANDROID_SDK_ROOT="$(grep -E '^sdk\.dir=' "$ROOT/local.properties" | cut -d= -f2- | sed 's/\\\\/\//g')"
  fi
fi
if [ -z "${ANDROID_SDK_ROOT:-}" ] || [ ! -d "$ANDROID_SDK_ROOT" ]; then
  echo "错误：请设置 ANDROID_SDK_ROOT 指向 Android SDK 目录" >&2
  exit 1
fi
if [ -z "${JAVA_HOME:-}" ] && command -v javac >/dev/null 2>&1; then
  JAVA_HOME="$(cd "$(dirname "$(command -v javac)")/.." && pwd)"
fi
if [ -z "${JAVA_HOME:-}" ]; then
  echo "错误：请设置 JAVA_HOME 指向 JDK 17" >&2
  exit 1
fi

BT="$ANDROID_SDK_ROOT/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_SDK_ROOT/platforms/android-34/android.jar"
[ -x "$BT/aapt2" ] || [ -x "$BT/aapt2.exe" ] || { echo "错误：未找到 build-tools/34.0.0" >&2; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "错误：未找到 platforms/android-34/android.jar" >&2; exit 1; }

# Git Bash / MSYS 下 PATH 里必须是 /d/... 形式，Windows 盘符形式查不到命令
if command -v cygpath >/dev/null 2>&1; then
  export PATH="$(cygpath -u "$JAVA_HOME")/bin:$PATH"
else
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# Windows 版 build-tools 只提供 .bat 包装
pick() {
  if [ -f "$BT/$1.bat" ]; then printf '%s' "$BT/$1.bat"; else printf '%s' "$BT/$1"; fi
}
AAPT2="$(pick aapt2)"
D8="$(pick d8)"
ZIPALIGN="$(pick zipalign)"
APKSIGNER="$(pick apksigner)"

PY=""
for c in python3 python; do
  if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
done
[ -n "$PY" ] || { echo "错误：需要 python3（用于合并 classes.dex）" >&2; exit 1; }

VERSION="$(grep -E "versionName" "$APP/build.gradle" | head -1 | sed -E 's/.*"(.*)".*/\1/')"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "==> 1/7 编译资源"
"$AAPT2" compile --dir "$SRC/res" -o "$BUILD/res.zip"

echo "==> 2/7 注入 package 并链接资源"
"$PY" - "$SRC/AndroidManifest.xml" "$BUILD/AndroidManifest.xml" "$PKG" <<'PY'
import sys
src, dst, pkg = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(src, encoding="utf-8").read()
if "package=" not in s.split(">", 1)[0]:
    s = s.replace("<manifest ", '<manifest package="%s" ' % pkg, 1)
open(dst, "w", encoding="utf-8").write(s)
PY
"$AAPT2" link -o "$BUILD/app-unsigned.apk" \
  -I "$(to_native "$ANDROID_JAR")" \
  --manifest "$(to_native "$BUILD/AndroidManifest.xml")" \
  "$(to_native "$BUILD/res.zip")" \
  --java "$BUILD/gen" \
  --min-sdk-version 21 --target-sdk-version 34

echo "==> 3/7 编译 Java"
"$PY" - "$ROOT" "$BUILD/sources.txt" <<'PY'
import os, sys
root, out = sys.argv[1], sys.argv[2]
paths = []
for base in (os.path.join(root, "app", "src", "main", "java"),
             os.path.join(root, "build", "gen")):
    for dirpath, _, files in os.walk(base):
        for f in files:
            if f.endswith(".java"):
                paths.append(os.path.join(dirpath, f))
open(out, "w", encoding="utf-8").write("\n".join(paths))
PY
javac -encoding UTF-8 --release 8 -Xlint:-options \
  -classpath "$(to_native "$ANDROID_JAR")" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "==> 4/7 dex"
"$PY" - "$BUILD/classes" "$BUILD/classes.txt" <<'PY'
import os, sys
base, out = sys.argv[1], sys.argv[2]
paths = []
for dirpath, _, files in os.walk(base):
    for f in files:
        if f.endswith(".class"):
            paths.append(os.path.join(dirpath, f))
open(out, "w", encoding="utf-8").write("\n".join(paths))
PY
"$D8" --release --min-api 21 \
  --lib "$(to_native "$ANDROID_JAR")" \
  --output "$(to_native "$BUILD/dex")" \
  $(cat "$BUILD/classes.txt")

echo "==> 5/7 打包 dex"
"$PY" - "$BUILD/app-unsigned.apk" "$BUILD/dex/classes.dex" "$BUILD/app-dex.apk" <<'PY'
import shutil, sys, zipfile
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY

echo "==> 6/7 对齐与签名"
"$ZIPALIGN" -f -p 4 "$BUILD/app-dex.apk" "$BUILD/app-aligned.apk"

KS="${MODU_KEYSTORE:-}"
if [ -z "$KS" ]; then
  KS="$BUILD/debug.keystore"
  [ -f "$KS" ] || keytool -genkeypair -v -keystore "$(to_native "$KS")" -alias debug \
    -keyalg RSA -keysize 2048 -validity 3650 \
    -storepass android -keypass android \
    -dname "CN=MoDu Debug, O=MoDu, C=CN" >/dev/null 2>&1
  ALIAS="debug"; STORE_PASS="android"; KEY_PASS="android"
  echo "    未提供正式签名，使用本地调试签名"
else
  ALIAS="${MODU_KEY_ALIAS:-modu}"
  STORE_PASS="${MODU_KEYSTORE_PASS:?请设置 MODU_KEYSTORE_PASS}"
  KEY_PASS="${MODU_KEY_PASS:-$STORE_PASS}"
fi

OUT="$DIST/Modu-$VERSION.apk"
"$APKSIGNER" sign --ks "$(to_native "$KS")" --ks-pass "pass:$STORE_PASS" \
  --ks-key-alias "$ALIAS" --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$(to_native "$OUT")" "$(to_native "$BUILD/app-aligned.apk")"

echo "==> 7/7 校验"
"$APKSIGNER" verify --print-certs "$(to_native "$OUT")" | head -3
ls -lh "$OUT"
echo "完成：$OUT"
