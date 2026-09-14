#!/bin/bash
# 启动脚本：构建 fat JAR 后用 java -jar 直接运行
# 这样 Ctrl+C 的 SIGINT 直接传给 JVM，不会残留子进程
set -e

cd "$(dirname "$0")"

# 风险点补救：确保子模块已拉取（防止克隆时漏掉 --recurse-submodules）
if [ -d "../.git" ] && [ -f "../.gitmodules" ]; then
    echo "[run.sh] 检查并更新 Git 子模块..."
    (cd .. && git submodule update --init --recursive)
fi

JAR="build/libs/wechat-download-kt-all.jar"

# 如果 fat JAR 不存在或源码有更新，先构建
# 注意：不能直接用 [ "src/main/kotlin" -nt "$JAR" ]，目录 mtime 不随深层文件递归更新，
# 会导致改了代码但未重建、部署旧 JAR。改为用 find 找出最新源文件做比较。
NEED_BUILD=0
if [ ! -f "$JAR" ]; then
    NEED_BUILD=1
elif [ "build.gradle.kts" -nt "$JAR" ]; then
    NEED_BUILD=1
elif [ -n "$(find src -type f \( -name '*.kt' -o -name '*.kts' \) -newer "$JAR" -print -quit)" ]; then
    NEED_BUILD=1
fi

if [ "$NEED_BUILD" = "1" ]; then
    echo "[run.sh] 构建 fat JAR..."
    # 风险点补救：确保 gradlew 有执行权限
    chmod +x ./gradlew
    ./gradlew buildFatJar --no-daemon -q
fi

echo "[run.sh] 启动应用: java -Duser.timezone=Asia/Shanghai -jar $JAR"
# 风险点补救：强制设定 JVM 时区为 Asia/Shanghai，防止服务器默认为 UTC 导致覆盖判定全部错乱
exec java -Duser.timezone=Asia/Shanghai -jar "$JAR"
