#!/bin/bash
# 容器内启动链：Xvfb -> x11vnc（带参数回退与端口探测）-> openbox / PyCharm
# 每个服务单独日志：/root/xvfb.log、/root/vnc.log；关键事件追加到 /root/ide.log
set -uo pipefail
export DISPLAY=:1
export HOME=/root
export LANG=C.UTF-8
export XDG_RUNTIME_DIR=/tmp/xdg
unset JAVA_HOME JDK_HOME PYCHARM_JDK XAUTHORITY
mkdir -p /tmp/xdg /tmp/.X11-unix "$HOME/.config/JetBrains" /dev/shm 2>/dev/null

SCREEN="${PYCHARM_SCREEN:-1280x800}"
IDE_DIR="${PYCHARM_DIR:-/opt/pycharm}"
LOG=/root/ide.log
XL=/root/xvfb.log
VL=/root/vnc.log
START=$(date +%s)
: > "$LOG"; : > "$XL"; : > "$VL"
rm -f /root/.vnc_port

# 清掉上一次残留的 X/VNC，避免端口或锁文件冲突
pkill -f "x11vnc" >/dev/null 2>&1 || true
pkill -f "Xvfb" >/dev/null 2>&1 || true
pkill -x openbox >/dev/null 2>&1 || true
sleep 1
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true
say() { echo "[run-ide +$(( $(date +%s) - START ))s] $*" | tee -a "$LOG"; }
say "run-ide.sh v1.9.9 · 固定 VNC 端口 5901 · IDE 退出后自动重启"

cleanup() {
  pkill -f "pycharm" >/dev/null 2>&1 || true
  pkill -f "Xvfb" >/dev/null 2>&1 || true
pkill -x openbox >/dev/null 2>&1 || true
  pkill -f "x11vnc" >/dev/null 2>&1 || true
  pkill -f "openbox" >/dev/null 2>&1 || true
}
trap 'cleanup' EXIT INT TERM

# 读 /proc/net/tcp 判断某端口是否 LISTEN（不依赖 bash /dev/tcp，也不依赖 ss/netstat）
port_listen() {
  local p=":$(printf '%04X' "$1")"
  awk -v p="$p" '$2 ~ p && $4 == "0A" {f=1} END {exit !f}' /proc/net/tcp /proc/net/tcp6 2>/dev/null
}

# 固定端口：5901（display :1 的传统 VNC 端口）
VNC_PORT=5901

say "虚拟屏 ${SCREEN}x24"
Xvfb :1 -screen 0 "${SCREEN}x24" -nolisten tcp -ac -dpi 96 >"$XL" 2>&1 &
XVFB_PID=$!

for i in $(seq 1 20); do
  [ -S /tmp/.X11-unix/X1 ] && break
  sleep 1
done
if [ ! -S /tmp/.X11-unix/X1 ]; then
  if kill -0 "$XVFB_PID" 2>/dev/null; then
    say "X 套接字未出现，但 Xvfb 进程仍在（检查 /tmp/.X11-unix 权限）"
  else
    wait "$XVFB_PID"; say "Xvfb 已退出，exit=$?"
  fi
  tail -n 20 "$XL" >>"$LOG"
  exit 2
fi
say "X 就绪（Xvfb pid=$XVFB_PID）"

if ! command -v x11vnc >/dev/null 2>&1; then
  say "X11VNC_MISSING：容器内没有 x11vnc 可执行文件"
  exit 3
fi

# 以 x11vnc 自己的 "Listening for VNC connections" 日志为准判断就绪
# （不要把还在监听的进程杀掉：之前用 /proc 端口探测出现假阴性，误杀导致客户端永远 Connection refused）
start_vnc() {
  local label="$1"; shift
  say "x11vnc 启动【$label】port=$VNC_PORT"
  echo "--- attempt: $label ---" >>"$VL"
  x11vnc -display :1 -rfbport "$VNC_PORT" -noshm -forever -shared -nopw \
         -repeat -wait 10 -defer 10 "$@" >>"$VL" 2>&1 &
  local pid=$!
  local i
  for i in $(seq 1 60); do
    if grep -q "Listening for VNC connections" "$VL" 2>/dev/null; then
      echo "$VNC_PORT" > /root/.vnc_port
      say "VNC 就绪【$label】$(grep -m1 'Listening for VNC connections' "$VL")"
      return 0
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid" 2>/dev/null
      say "x11vnc 已退出【$label】exit=$?"
      break
    fi
    sleep 0.25
  done
  if kill -0 "$pid" 2>/dev/null; then
    if port_listen "$VNC_PORT"; then
      echo "$VNC_PORT" > /root/.vnc_port
      say "VNC 就绪【$label】（端口探测兜底）"
      return 0
    fi
    say "x11vnc 进程存活，但日志与端口都未确认监听【$label】"
  fi
  tail -n 15 "$VL" | sed 's/^/[vnc] /' >>"$LOG"
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  return 1
}

say "x11vnc 版本：$(x11vnc -version 2>&1 | head -1)"
start_vnc "localhost" -localhost -noxdamage \
  || start_vnc "allow 127.0.0.1" -allow 127.0.0.1 -noxdamage \
  || { say "VNC_FAIL：两种参数均未确认监听，见 /root/vnc.log"; tail -n 30 "$VL" >>"$LOG"; }

# 深色 Windows 风格 openbox 主题 + 窗口规则（标题栏 30 px 按钮、Alt+F9/F10 快捷键）
if [ -f /opt/host/openbox-theme.tar ]; then
  mkdir -p "$HOME/.themes" "$HOME/.config/openbox"
  rm -rf "$HOME/.themes/Breeze-PyCharm"
  tar -xf /opt/host/openbox-theme.tar -C "$HOME/.themes" 2>>"$LOG" || say "主题解包失败（继续用默认主题）"
  cp /opt/host/openbox-rc.xml "$HOME/.config/openbox/rc.xml" 2>>"$LOG" || true
  say "窗口主题 Breeze-PyCharm 已安装（深色标题栏）"
fi

say "窗口管理器 openbox"
openbox --sm-disable >>"$XL" 2>&1 &

if [ ! -x "$IDE_DIR/bin/pycharm.sh" ]; then
  say "IDE 目录不存在或不可执行：$IDE_DIR"
  exit 4
fi
# IDE 守护循环：PyCharm 自己「Restart IDE」会退出 JVM，旧脚本随之退出并杀掉 Xvfb/x11vnc，
# 表现为整个 App 关闭。这里保持 X/VNC 常驻，IDE 退出后自动重新拉起。
FAILS=0
while true; do
  say "PyCharm 启动（JBR 冷启动，首次 1~3 分钟）"
  ( cd "$IDE_DIR" && "$IDE_DIR/bin/pycharm.sh" ) >>"$LOG" 2>&1 &
  IDE_PID=$!
  say "PyCharm 进程已拉起 pid=$IDE_PID"
  wait "$IDE_PID"
  CODE=$?
  say "PyCharm 退出（exit=$CODE），桌面与 VNC 保持，准备重启 IDE"
  FAILS=$((FAILS + 1))

  # JetBrains Restarter 可能已自行拉起新进程：最多等 15 s 观察，避免和它同时跑两个实例
  sleep 2
  RESTARTED=0
  for i in $(seq 1 15); do
    if pgrep -f "pycharm" >/dev/null 2>&1; then RESTARTED=1; break; fi
    sleep 1
  done
  if [ "$RESTARTED" = 1 ]; then
    say "检测到 PyCharm 已在自行重启，等待该实例结束"
    while pgrep -f "pycharm" >/dev/null 2>&1; do sleep 2; done
    say "PyCharm 实例已结束"
  fi

  if [ "$FAILS" -ge 5 ]; then
    say "IDE 连续退出 $FAILS 次，延长等待 60 s（可点「退出」结束）"
    sleep 60
  else
    sleep 4
  fi
done
