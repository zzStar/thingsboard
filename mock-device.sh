#!/bin/bash

# ================= 配置区域 =================
#本地mqtt服务
#HOST="127.0.0.1"
#PORT="1883"
# CLIENT_ID="g0k5ajd739ni17kiwxzg"
# USERNAME="axn1qzj1sa53449c4rke"
# PASSWORD="mj3ye19i3ri4dwfug20w"

# 生产环境mqtt服务
HOST="things.zhigongshulian.com"
PORT="1883"
CLIENT_ID="sy7p97g71ze6spicgarm"
USERNAME="p4svq8kaxd44tz84o00h"
PASSWORD="owigq6uw0y8997vkq6xp"

INTERVAL=1
# ============================================



total_count=1

echo "🚀 开始模拟上报数据（MQTT协议），按 Ctrl+C 停止..."

while true; do
    # 使用 mosquitto_pub 发送 MQTT 消息
    mosquitto_pub -h $HOST -p $PORT \
        -i "$CLIENT_ID" \
        -u "$USERNAME" \
        -P "$PASSWORD" \
        -t "v1/devices/me/telemetry" \
        -m "{\"totalCount\": $total_count, \"speed\": 11799}"
    
    echo "📤 已上报数据: totalCount=$total_count, speed=11799"
    
    total_count=$((total_count+1))
    sleep $INTERVAL
done