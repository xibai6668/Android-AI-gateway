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

TASK="${1:-:app:assembleRelease}"
# 本项目交付一律用 release 签名（buildTypes.release 已挂 keystore.properties 的密钥），
# 不再产 debug 签名包——避免两种签名交替安装时「签名不一致无法覆盖升级」。
# 附加参数固定由脚本带，调用者不传任务时也不会把选项误当任务。
shift 2>/dev/null || true
exec gradle "$TASK" --console=plain "$@"
