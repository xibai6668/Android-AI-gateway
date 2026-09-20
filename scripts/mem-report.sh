#!/bin/sh
# 查看本应用的内存构成（在电脑上通过 adb 执行，手机需开启 USB 调试）。
#
#   adb shell sh /data/local/tmp/mem-report.sh
# 或直接一条命令：
#   adb shell dumpsys meminfo dev.traegw.app
#
# 重点看这几行：
#   Java Heap  —— Java/Kotlin 对象（业务代码在这里，优化后应显著小于 50MB）
#   Native Heap——WebView 的 Chromium、图像解码等（登录过就是这里偏大）
#   Graphics   —— Compose 渲染缓冲
#   TOTAL PSS  —— 系统统计的总体占用
set -e
PKG=dev.traegw.app
echo "=== $PKG 内存构成 ==="
dumpsys meminfo "$PKG" 2>/dev/null | sed -n '1,40p'
echo
echo "=== 进程 ==="
ps -A 2>/dev/null | grep "$PKG" || true
