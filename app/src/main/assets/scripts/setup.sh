#!/bin/bash
# 首次安装：配置源、安装 PyCharm 运行所需的 X11 / VNC / 依赖库
# 在 proot 容器内以 root 身份执行，可重复执行（幂等）
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
export LANG=C.UTF-8

ARCH="$(dpkg --print-architecture)"
# 基础 rootfs 不含 CA 证书，先用 http 源装好 ca-certificates 再可切换 https
if [ "$ARCH" = "arm64" ]; then
  DEFAULT_MIRROR="http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
else
  DEFAULT_MIRROR="http://mirrors.tuna.tsinghua.edu.cn/ubuntu"
fi
MIRROR="${APT_MIRROR:-$DEFAULT_MIRROR}"

echo "[1/4] 写入 DNS 与 APT 源 ($ARCH, $MIRROR)"
cat > /etc/resolv.conf <<'EOF'
nameserver 223.5.5.5
nameserver 119.29.29.29
nameserver 8.8.8.8
EOF

mkdir -p /etc/apt/sources.list.d
cat > /etc/apt/sources.list.d/ubuntu.sources <<EOF
Types: deb
URIs: $MIRROR
Suites: noble noble-updates noble-backports noble-security
Components: main universe restricted multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF

echo "[2/4] 修正容器内的服务/文本工具桩"
# 容器内没有 systemd / sysvinit：阻止 postinst 启动服务，并提供 postinst 依赖的最小工具
cat > /usr/sbin/policy-rc.d <<'EOF'
#!/bin/sh
exit 101
EOF
cat > /sbin/runlevel <<'EOF'
#!/bin/sh
echo "N 5"
EOF
cat > /usr/sbin/update-gsfontmap <<'EOF'
#!/bin/sh
exit 0
EOF
chmod +x /usr/sbin/policy-rc.d /sbin/runlevel /usr/sbin/update-gsfontmap

echo "[3/4] apt-get update"
apt-get update -y

echo "[4/4] 安装 X11 / VNC / 运行库"
apt-get install -y --no-install-recommends \
  ca-certificates locales \
  xvfb x11vnc novnc websockify openbox xterm \
  dbus-x11 fontconfig fonts-dejavu-core fonts-wqy-microhei \
  libnss3 libxkbcommon0 libxkbcommon-x11-0 \
  libxcb-xinerama0 libxcb-icccm4 libxcb-image0 libxcb-keysyms1 \
  libxcb-randr0 libxcb-render-util0 libxcb-shape0 libxcb-xfixes0 \
  libxcb-cursor0 libx11-xcb1 \
  libgbm1 libfreetype6 libfontconfig1 libxrender1 \
  libxi6 libxtst6 libxrandr2 libxext6 libxfixes3 \
  libxt6t64 libsm6 libice6 \
  libglib2.0-0t64 libasound2t64 \
  procps psmisc
apt-get -f install -y || true

echo "[4/4] 生成中文 locale 缓存"
sed -i 's/^# *\(C.UTF-8 UTF-8\)/\1/' /etc/locale.gen || true
locale-gen C.UTF-8 >/dev/null 2>&1 || true
fc-cache -f >/dev/null 2>&1 || true

echo "SETUP_OK"
