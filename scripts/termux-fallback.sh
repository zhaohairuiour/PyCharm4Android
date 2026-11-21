#!/data/data/com.termux/files/usr/bin/bash
# 备用方案：Termux + Termux:X11（原生 X 服务器，帧率优于 VNC）
# 前提：Termux 与 Termux:X11 均从 GitHub / F-Droid 安装（不要用 Google Play 版 Termux）
set -e

echo "[1/4] 安装 Termux 侧依赖"
pkg update -y
pkg install -y x11-repo
pkg install -y proot-distro termux-x11-nightly termux-am

echo "[2/4] 安装 Ubuntu 容器"
proot-distro install ubuntu || true

echo "[3/4] 容器内安装 X11 依赖与 PyCharm"
proot-distro login ubuntu --shared-tmp -- bash -c '
  export DEBIAN_FRONTEND=noninteractive
  export LANG=C.UTF-8
  # 基础镜像无 CA 证书，先用 http 源
  cat > /etc/apt/sources.list <<EOF
deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble main universe restricted multiverse
deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble-updates main universe restricted multiverse
EOF
  printf "nameserver 223.5.5.5\nnameserver 8.8.8.8\n" > /etc/resolv.conf
  apt-get update
  apt-get install -y --no-install-recommends \
    ca-certificates locales dbus-x11 openbox fontconfig fonts-dejavu-core fonts-wqy-microhei \
    libnss3 libxkbcommon-x11-0 libxcb-xinerama0 libxcb-icccm4 libxcb-image0 libxcb-keysyms1 \
    libxcb-randr0 libxcb-render-util0 libxcb-shape0 libxcb-xfixes0 libx11-xcb1 \
    libgbm1 libfreetype6 libfontconfig1 libxrender1 libxi6 libxtst6 libxrandr2 libxext6 \
    libxfixes3 libxt6t64 libsm6 libice6 libglib2.0-0t64 libasound2t64 curl
  mkdir -p /opt
  cd /opt
  curl -L -o pycharm.tar.gz https://download.jetbrains.com/python/pycharm-community-2024.3.5-aarch64.tar.gz
  tar xzf pycharm.tar.gz
  mv pycharm-community-2024.3.5 pycharm
  rm -f pycharm.tar.gz
  printf "\n-Xmx1024m\n-Dide.browser.jcef.enabled=false\n" >> /opt/pycharm/bin/pycharm64.vmoptions
'

echo "[4/4] 启动（新开一个 Termux 会话执行下面两条）"
cat <<'TIP'
termux-x11 :1 &
proot-distro login ubuntu --shared-tmp -- bash -c \
  'unset JAVA_HOME JDK_HOME PYCHARM_JDK; export DISPLAY=:1; openbox & /opt/pycharm/bin/pycharm.sh'
TIP
