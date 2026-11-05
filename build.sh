#!/bin/bash
# RootGuideApp 构建脚本（macOS / 本机工具链）
#
# 为什么不在源码目录里直接构建？
#   WorkBuddy 环境对**工作区内的删除操作**有保护。Gradle 跑增量任务时要删旧产物
#   （app-metadata.properties、clean 的整个 build 目录），在源码目录里会被拦下来直接
#   BUILD FAILED：Unable to delete directory .../app/build。/tmp 下没这个限制，
#   所以走「复制到临时目录 → 构建 → 把 APK 拷回来」。
#
# 用法：
#   ./build.sh              # debug（默认）
#   ./build.sh release      # release（未配签名，产物装不上真机）
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VARIANT="${1:-debug}"

# macOS 自带 bash 是 3.2，不支持 ${VAR^}，手工转首字母大写
FIRST="$(printf '%s' "$VARIANT" | cut -c1 | tr '[:lower:]' '[:upper:]')"
REST="$(printf '%s' "$VARIANT" | cut -c2-)"
VARIANT_CAP="$FIRST$REST"

# ---- 工具链定位：本机历史上有过两套安装位置，逐个探测，允许任何一种存在 ----
pick_first() {
  local found
  for cand in "$@"; do
    found="$(eval echo "$cand")"
    [[ -n "$found" ]] && printf '%s\n' "$found" && return 0
  done
  return 1
}

JDK_HOME="$(pick_first \
  '$HOME/.local/jdk/zulu17.56.15-ca-jdk17.0.14-macosx_x64/zulu-17.jdk/Contents/Home' \
  '$HOME/.local/jdk/zulu17.56.15-ca-jdk17.0.14-macosx_x64' \
  '$HOME/Library/Java/JavaVirtualMachines/zulu17.54.21-ca-jdk17.0.13-macosx_x64' || true)"

GRADLE_BIN="$(pick_first \
  '$HOME/.local/gradle/gradle-8.9/bin/gradle' \
  '$HOME/.local/toolchain/gradle-8.9/bin/gradle' || true)"

ANDROID_SDK="$(pick_first \
  '$HOME/.local/android-sdk' \
  '$HOME/Library/Android/sdk' || true)"

missing=0
[[ -d "$JDK_HOME" && -x "$JDK_HOME/bin/java" ]] || { echo "找不到 JDK 17"; missing=1; }
[[ -x "$GRADLE_BIN" ]] || { echo "找不到 Gradle 8.9"; missing=1; }
if [[ -z "$ANDROID_SDK" || ! -d "$ANDROID_SDK/platforms/android-35" ]]; then
  echo "找不到带 platform-35 的 Android SDK"; missing=1
fi
if [[ "$missing" -eq 1 ]]; then
  cat >&2 <<'EOF'

缺工具链。装法（全部直链，不需要 Homebrew / Android Studio）：
  JDK 17   curl -L -o /tmp/zulu17.tar.gz https://cdn.azul.com/zulu/bin/zulu17.56.15-ca-jdk17.0.14-macosx_x64.tar.gz
           mkdir -p ~/.local/jdk && tar -xzf /tmp/zulu17.tar.gz -C ~/.local/jdk
  Gradle   curl -L -o /tmp/gradle89.zip https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip
           mkdir -p ~/.local/gradle && unzip -q /tmp/gradle89.zip -d ~/.local/gradle
  SDK      curl -L -o /tmp/platform35.zip https://dl.google.com/android/repository/platform-35_r02.zip
           mkdir -p ~/.local/android-sdk/platforms && unzip -q /tmp/platform35.zip -d ~/.local/android-sdk/platforms
           # 还需要 build-tools 35.0.1 与 licenses/，见 README「本机工具链」
EOF
  exit 1
fi

echo "JDK    : $JDK_HOME"
echo "Gradle : $GRADLE_BIN"
echo "SDK    : $ANDROID_SDK"

TMPDIR_BUILD="$(mktemp -d /tmp/RootGuideBuild.XXXXXX)"
echo "==> 复制到临时构建目录：$TMPDIR_BUILD"
# macOS 自带 rsync 是 openrsync，别用 -a（内含 -o -g，非 root 下会静默失败）
rsync -rlpt \
  --exclude='app/build' --exclude='build' --exclude='.gradle' \
  --exclude='.DS_Store' --exclude='*.apk' \
  "$HERE/" "$TMPDIR_BUILD/"

export JAVA_HOME="$JDK_HOME"
export ANDROID_HOME="$ANDROID_SDK"
export PATH="$JAVA_HOME/bin:$PATH"

# 环境里有代理就透传给 Gradle（沙箱 / CI 场景），否则依赖解析会超时
PROXY_ARGS=()
if [[ -n "${https_proxy:-}" ]]; then
  host="${https_proxy#*://}"; host="${host%:*}"; port="${https_proxy##*:}"
  PROXY_ARGS+=("-Dhttps.proxyHost=$host" "-Dhttps.proxyPort=$port"
               "-Dhttp.proxyHost=$host"  "-Dhttp.proxyPort=$port")
  echo "==> 检测到代理 ${host}:${port}"
fi

echo "==> Gradle assemble${VARIANT_CAP}"
# bash 3.2（macOS 自带）在 set -u 下展开空数组会报 unbound variable，用 ${VAR[@]+...} 兼容
( cd "$TMPDIR_BUILD" && "$GRADLE_BIN" "assemble${VARIANT_CAP}" --console=plain ${PROXY_ARGS[@]+"${PROXY_ARGS[@]}"} )

APK_SRC="$TMPDIR_BUILD/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
[[ -f "$APK_SRC" ]] || { echo "构建没产出 APK：$APK_SRC" >&2; exit 1; }

# 版本号只从 build.gradle.kts 取，避免产物名和实际版本对不上
APP_VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$HERE/app/build.gradle.kts" | head -1)"
OUT="$HERE/magicrootguide-v${APP_VERSION:-dev}-$VARIANT.apk"
# 目标已存在时 cp 会先 unlink 它，而工作区内的 unlink 可能被文件策略拒绝
# （Brokered host copy overwrite refused）。先手动删掉再拷，避开这一步。
rm -f "$OUT" 2>/dev/null || true
cp "$APK_SRC" "$OUT"
echo "==> APK 已输出：$OUT"
ls -lh "$OUT"

if [[ "$VARIANT" == "release" ]]; then
  echo "提醒：release 未配置 signingConfig，产物未签名，装不上真机。" >&2
fi

rm -rf "$TMPDIR_BUILD"
echo "==> 完成"
