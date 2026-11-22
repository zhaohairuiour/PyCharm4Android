#!/bin/bash
# 用法: extract-ide.sh <容器内 tgz 路径>
# 退出码: 0 成功 / 4 gzip 损坏 / 5 tar 失败 / 6 找不到 pycharm.sh / 7 改名失败
T="$1"
echo "[解包] 文件 $(ls -l "$T" 2>&1 | head -1)"
echo "[解包] 空间 $(df -h /opt 2>&1 | tail -1)"
echo "[解包] inode $(df -i /opt 2>&1 | tail -1)"
echo "[解包] tar=$(tar --version 2>&1 | head -1) gzip=$(command -v gzip 2>&1)"
P=/opt/host/extract-progress.log
: > "$P"
echo "PHASE VERIFY" >> "$P"
gzip -t "$T" >> "$P" 2>&1
GZ=$?
echo GZIP_RC=$GZ
if [ $GZ -ne 0 ]; then tail -n 20 "$P"; exit 4; fi
mkdir -p /opt/.extract
echo "PHASE EXTRACT" >> "$P"
tar xzf "$T" -C /opt/.extract --no-same-owner --checkpoint=1000 --checkpoint-action='echo=TAR_PROGRESS %u' >> "$P" 2>&1
RC=$?
tail -n 40 "$P"
echo TAR_RC=$RC
if [ $RC -ne 0 ]; then
  find /opt/.extract -maxdepth 2 | head -20
  rm -rf /opt/.extract
  exit 5
fi
D=$(find /opt/.extract -maxdepth 3 -type f -name pycharm.sh 2>/dev/null | head -1)
echo "[解包] pycharm.sh=$D"
if [ -z "$D" ]; then
  find /opt/.extract -maxdepth 2 | head -20
  rm -rf /opt/.extract
  exit 6
fi
R=$(dirname "$(dirname "$D")")
echo "[解包] 根目录 $R"
echo "PHASE MOVE" >> "$P"
mv "$R" /opt/pycharm || { echo MV_FAIL; exit 7; }
rm -rf /opt/.extract
echo EXTRACT_OK
