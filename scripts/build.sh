#!/bin/sh
# 在本容器里构建 APK 的入口。
#
# 需要两件环境相关的事：
#   1. JDK 17（JDK 21 在 PRoot 下无法启动 VM）
#   2. LD_PRELOAD 垫片：PRoot 下 java.io.File.delete() 删目录恒失败，
#      AGP 的 apkzlib 依赖该行为，不打垫片会在 MergeJavaResources 阶段炸掉。
set -e

cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"

SHIM=/opt/android-sdk/lib/libdelfix.so
if [ -f "$SHIM" ]; then
    export LD_PRELOAD="$SHIM"
fi

TASK="${1:-:app:assembleDebug}"
exec gradle "$TASK" --console=plain
