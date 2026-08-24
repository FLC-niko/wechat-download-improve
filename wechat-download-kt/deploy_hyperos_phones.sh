#!/usr/bin/env bash
set -euo pipefail

# 动态初始化所有已连接设备；不假定无线 ADB 地址或 XSpace 固定为 999。
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="${1:-$ROOT_DIR/../archive-system-crawling-android-master/app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE="com.topviewclub.crawling"
SERVICE="$PACKAGE/com.topviewclub.crawling.service.wechat.weread.WeReadLoginOperationService"

devices=()
while IFS= read -r serial; do
    [[ -n "$serial" ]] && devices+=("$serial")
done < <(adb devices | awk '$2 == "device" { print $1 }')
if ((${#devices[@]} == 0)); then
    echo "[ERROR] 未检测到状态为 device 的 ADB 设备。"
    exit 1
fi

for serial in "${devices[@]}"; do
    echo ">>> 初始化设备: $serial"
    users_output="$(adb -s "$serial" shell pm list users)"
    users=()
    while IFS= read -r user_id; do
        [[ -n "$user_id" ]] && users+=("$user_id")
    done < <(printf '%s\n' "$users_output" | sed -n 's/.*UserInfo{\([0-9][0-9]*\):.*/\1/p' | sort -nu)
    if ((${#users[@]} == 0)); then users=(0); fi

    # 这里接受 APK 或 APK 所在目录；AAR 不能直接安装，给出明确提示。
    if [[ "$APK_PATH" == *.apk && -f "$APK_PATH" ]]; then
        adb -s "$serial" install -r "$APK_PATH" >/dev/null
    else
        echo "[WARN] 未提供可安装 APK，跳过安装: $APK_PATH"
    fi

    for user_id in "${users[@]}"; do
        adb -s "$serial" shell pm install-existing --user "$user_id" "$PACKAGE" >/dev/null 2>&1 || true
        adb -s "$serial" shell settings --user "$user_id" put secure enabled_accessibility_services "$SERVICE"
        adb -s "$serial" shell settings --user "$user_id" put secure accessibility_enabled 1
        adb -s "$serial" shell cmd appops set --user "$user_id" com.tencent.mm RUN_IN_BACKGROUND allow || true
        echo "    user=$user_id accessibility=enabled"
    done

    adb -s "$serial" shell svc power stayonusb true || true
    adb -s "$serial" shell dumpsys deviceidle disable || true
done

echo ">>> 所有在线设备初始化完成"