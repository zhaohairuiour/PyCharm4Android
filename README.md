<div align="center">

# PyCharm for Android

**在 Android 手机上运行 JetBrains 官方 PyCharm 桌面版**

[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#)
[![Arch](https://img.shields.io/badge/arch-arm64-00E5FF?style=flat-square)](#)
[![Root](https://img.shields.io/badge/root-not%20required-41E3A0?style=flat-square)](#)
[![UI](https://img.shields.io/badge/UI-v1.14.5-3D7BFF?style=flat-square)](#)

[介绍页](https://zhaohairuiour.github.io/PyCharm4Android/) · [下载最新版](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.14.5-arm64.apk) · [全部版本](#全部版本) · [构建](#构建)

</div>

---

## 这是什么

不是远程开发客户端，也不是重编译的“安卓版 PyCharm”。这个 APK 把三层运行时装进手机：

- **Linux 用户态**：Ubuntu 24.04 arm64 rootfs，Termux 版 proot 免 root 运行；
- **X 显示**：Xvfb 虚拟屏 + openbox 窗口管理 + x11vnc 固定 5901 端口；
- **渲染与输入**：自研原生 RFB 客户端直接写 `Bitmap`，单指移动/轻点/长按拖拽、双指缩放、双指上下滑=滚轮、双指轻点=右键、自绘软键盘注入 keysym。

IDE 本体是 JetBrains 官方 Linux arm64 发行版，安装时按需下载，不塞进 APK。社区版 2022.3–2025.3、专业版 2022.3–2026.3.0.1 可选。

![PyCharm 欢迎页](docs/screenshots/screenshot-welcome.png)

## v1.14.5 更新：修复点击失效，双指上下滑动改为滚轮

- 修复 1.14.4 引入的点击回归：指针线程原先只保留最新状态，同一 UI 帧内的 `press(mask=1)` 会被随后的 `release(mask=0)` 覆盖，表现为“点屏幕任何位置都没反应”。改为有序事件队列：按下/抬起/右键/滚轮严格按序成包，仅合并 mask 相同的连续移动；
- 指针线程改用 `closed` 标志退出：连接建立前 `running=false` 不再让线程提前结束，消除“完全没有指针”的另一条路径；
- 双指手势重新分工：捏合 = 缩放，双指上下滑 = 滚轮（每约 24 dp 一格；上滑滚轮向下、下滑滚轮向上），横向位移仍用于平移；400 ms 内双指轻点仍触发右键；
- 键盘发送队列 16 → 256，降低快速输入丢键概率；
- 新增模拟 RFB 服务端的指针顺序测试：同一帧两次轻点、滚轮上下、右键的报文序列 `1,0,1,0,8,0,16,0,4,0` 全部按序到达。

## v1.14.4 更新：VNC 改走 Hextile，滚动和首帧更快

- RFB 客户端优先协商 Hextile（16×16 瓦片，背景色 + 单色/彩色子矩形），Raw 降为兜底；新增纯 Java `HextileDecoder`，按 RFC 6143 处理 subencoding 位、bg/fg 跨瓦片继承和非整瓦片边界；
- 1280×800 静态全屏首帧：Raw 4,096,115 B → Hextile 4,164 B（约 1/983）；滚动 3 s：63.1 MB → 0.81 MB（约 1/78），单帧解码 1.4~1.8 ms → 0.41~0.44 ms（本机 Xvfb + x11vnc 实测，帧缓冲 SHA-256 与 Raw 完全一致）；
- x11vnc 启用 XDamage（移除 `-noxdamage`），只抓真正变化的区域；`-wait 10 -defer 10` 调为 `-wait 8 -defer 5`。滚动 5 s 实测：XDamage 出帧 75，无 Damage 仅 22（全屏 XGetImage + memcmp 跟不上）；
- `VncView` 以 16 ms 窗口合并 RFB 矩形、跳过空更新、去掉 `DITHER_FLAG`（指针事件的合并策略在 1.14.5 改为有序队列）；
- 连接后日志打印 `[vnc] 服务端采用 Hextile 编码`，便于确认优化生效。

## v1.14.3 更新：堆内存改为输入框

- 堆内存设置改为数值输入框（256–6144 MB）+ 快捷档位，不再只有固定 6 档；输入框自动聚焦并弹出数字键盘；
- 应用后回读 `runtime/vmoptions.txt` 并在 Toast 中显示实际写入的 `-Xmx`，主界面按钮同步显示当前值；
- 堆参数改由 `PYCHARM_VM_OPTIONS` 文件承载，只托管 `-Xmx` 与容器相关 `-D`；`ReservedCodeCacheSize` / `MaxMetaspaceSize` 沿用官方默认，避免重复 `-XX` 让较小值覆盖官方值；
- PyCharm 运行中修改后可直接确认“立即重启”，启动脚本重新拉起 IDE 即读取新堆值；
- `run-ide.sh` 启动时把实际 `-Xmx` 写进日志，便于确认生效。

## v1.14.2 更新：日志滚动、VNC 光标与 apt 中断修复

- 日志区单独消费纵向手势，抬手后恢复外层页面拦截；滚动条常显，安装日志和显示页日志都不会被自动回底抢走；
- RFB `RichCursor` 补读 1 位透明掩码，修掉旧实现漏读导致的指针矩形异常与后续消息错位；光标上限降到 16dp，无服务端光标时备用箭头同步缩小；
- `setup.sh` 在 apt 前清理 dpkg/apt 锁并执行 `dpkg --configure -a`、`apt-get -f install`；首次安装失败时收敛 dpkg 后自动重试一次，修复“dpkg was interrupted”导致的 exit 100。

## v1.14.1 更新：日志滚动与 VNC 指针

- 日志区禁用文本选择，长按改为复制全文，避免 `TextView` 抢走纵向拖拽手势；
- 显示页日志上滑后停止自动回底，按 1.5 s 轮询刷新时保持阅读位置；主界面缓冲区整体替换时保留原滚动位置；
- RFB 增加 `RichCursor(-239)` 协商，直接绘制 x11vnc 的真实 X11 光标；服务端空光标时隐藏指针，异常大光标限制在 24dp，不再默认使用大号自绘箭头。

## v1.14.0 更新：修复堆内存修改不生效

- 堆参数写入 `runtime/vmoptions.txt`（保留官方默认 JVM 选项，只替换托管 `-Xmx` 块）；`run-ide.sh` 导出 `PYCHARM_VM_OPTIONS`，覆盖安装目录默认文件与用户配置副本；
- `applyToRootfs` 兼容 `pycharm64.vmoptions` / `pycharm.vmoptions`，写前先 `chmod 0644`；找不到默认文件不再直接报失败，并同步 runtime 参数；
- 写入后回读校验 `-Xmx`；每次启动 PyCharm 前自动刷新运行参数，运行中的实例需重启后读取新参数。

## v1.13.0 更新：第二轮性能优化

- RFB 输入改为 64 KB `BufferedInputStream`，协议头/矩形头不再逐字节触发 socket read；输出同样改为 16 KB 缓冲；
- Raw 像素解码改走 little-endian `IntBuffer` 批量读入，帧缓冲只做整像素 alpha 合成；大矩形缓冲区跨帧复用；
- `ide.log` 的 200 KB tail、关键事件拼接和 `LogBus` 快照全部移到 `ide-log-tail` 单线程，UI 只做增量 `TextView.append`；
- `TarGz` 普通条目直接用 `File` 拼接，只有 `..`、绝对路径或符号链接前缀才走 canonical 校验；目录 `mkdirs` 结果缓存，无执行位的普通文件跳过冗余 `chmod`；
- `Rootfs.verify` 先用 `Os.access(X_OK)` 判断，已可执行就不再在每次 proot 启动时重复 `chmod`。

## v1.12.0 更新：启动、解包与渲染性能

- proot 每次启动不再递归 `chmod` 整个 `usr/bin`、`usr/sbin`；rootfs 首次校验后写 `.p4a-rootfs-verified`，后续只检查关键 5 个文件；
- `TarGz` 整个解包过程复用一块 64 KB I/O 缓冲，不再为每个文件分配 64 KB；`skipFully`/pax 元数据也复用缓冲；
- 主界面空闲轮询从 800 ms 降到 2 s，设备内存/存储等详情 5 s 更新一次；状态、进度条、按钮只在值变化时刷新；
- 显示页连接成功且日志面板隐藏后停止日志轮询，不再每 800 ms 读取 200 KB `ide.log`；日志文件未变化时直接复用缓存；
- VNC 更新先入队、按 UI 帧合并，避免高频 RFB 消息刷爆主线程；`CopyRect` 改为原地按行拷贝，去掉每个矩形的 `w*h` 临时数组；
- `LogBus` 改为 `ArrayDeque` 环形日志，去掉 600 行上限下的 `ArrayList.remove(0)` 搬移。

## v1.11.0 更新：解包/删除进度与控制坞

- rootfs 与 PyCharm 解包新增实时进度：压缩包已读/总量、百分比、当前文件；容器内 tar 用 `--checkpoint` 上报，Java 解包回退同样有进度；
- 旧 IDE、临时目录、下载缓存和卸载流程显示“扫描文件 → 按字节删除”进度，不再只有结果 Toast；
- 底部控制坞新增「收起 ▾」，收起后保留「控制栏 ▲」，VNC 画布与日志面板自动扩展；
- 修复 proot 环境下的 file watcher / 脚本启动器提示：禁用原生 `fsnotifier`，优先 `bin/pycharm` 原生 launcher；
- 保留 v1.9.9 的强制横屏、v1.9.8 的六档堆内存与科技感安装界面。

## v1.9.9 更新：强制横屏

- 主界面与 IDE 显示页统一锁定 `sensorLandscape`，支持左右翻转，不再回到竖屏；
- 进入 PyCharm 后不会因手机姿态变化重排画面，VNC 帧缓冲保持稳定；
- 保留 v1.9.8 的堆内存六档调节与 v1.9.7 科技感安装界面。

## v1.9.8 更新：堆内存可调

- 主界面新增「堆内存」圆角胶囊，512 / 768 / 1024 / 1536 / 2048 / 3072 MB 六档可选；
- 已安装时直接改写 rootfs 内 `pycharm64.vmoptions`，托管块自动去重，不会叠加多个 `-Xmx`；
- 修改后无需重装，重启 PyCharm 即生效；安装中自动写入当前档位；
- 保留 v1.9.7 的科技感安装界面：深空渐变 + 青色网格背景、圆角状态卡/进度条/日志面板/对话框。

## 特性

| | |
|---|---|
| **官方 IDE** | 直接运行 JetBrains 官方 Linux arm64 构建，不是兼容层或重编译 |
| **免 root** | proot + 自编译 loader，零售机、未解锁 Bootloader 可用 |
| **原生 RFB** | Hextile + CopyRect，Raw 兜底；不走 noVNC / WebView，无 JS 解码 |
| **固定端口** | 服务端与客户端都写死 5901，不做端口扫描 |
| **窗口外观** | Breeze-PyCharm 深色 openbox 主题，30 px 按钮、红色关闭、Alt+F9/F10 |
| **强制横屏** | 主界面与显示页锁定 `sensorLandscape`，可左右翻转，桌面画布始终横向铺满 |
| **堆内存可调** | 主界面六档 `-Xmx`（512–3072 MB），已安装容器原地改写 `pycharm64.vmoptions`，重启生效 |
| **版本管理** | 社区版/专业版双目录、断点续传、SHA-256 + 体积双校验、卸载与缓存清理 |
| **脚本自刷新** | 每次启动用 APK assets 覆盖容器内 `run-ide.sh`/`extract-ide.sh`/`setup.sh` |
| **崩溃可诊断** | `files/crash.txt` 下次启动自动回显；日志面板增量刷新、贴底才跟随 |
| **低开销运行** | rootfs 校验缓存、解包 I/O 复用、空闲 2 s 轮询、日志隐藏即停、VNC 帧合并 |

## 架构

```text
APK
├─ assets/proot/            proot + libtalloc + libandroid-shmem + loader
├─ assets/scripts/          setup.sh / run-ide.sh / extract-ide.sh
├─ assets/scripts/openbox-theme.tar + openbox-rc.xml
└─ Java
   ├─ SetupService           rootfs / PyCharm 下载、校验、解包、apt 安装
   ├─ IdeService             前台服务：常驻容器，IDE 退出后自动重启
   ├─ Proot / Rootfs         proot 命令组装、loader 覆盖、挂载点
   ├─ Downloader / Versions  多镜像、Range 续传、社区版/专业版版本目录
   ├─ TarGz                  纯 Java tar.gz 解包（保留权限/符号链接/硬链接）
   ├─ VncClient              原生 RFB：握手、Hextile/CopyRect/Raw、单线程发送队列
   └─ VncView                Bitmap 绘制、触摸状态机、内置箭头光标
```

运行链路：`proot Ubuntu → Xvfb :1 → x11vnc :5901 → RFB → Canvas`。

## 全部版本

| 版本 | 日期 | 体积 | SHA-256 | APK |
|---|---|---|---|---|
| v1.14.5 | 2026-11-28 | 298 KB | [`c0de9fdc4b14…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.14.5-arm64.apk) |
| v1.14.4 | 2026-11-28 | 293 KB | [`8b608fd30304…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.14.4-arm64.apk) |
| v1.14.3 | 2026-11-27 | 293 KB | [`2cd55c6a4110…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.14.3-arm64.apk) |
| v1.14.2 | 2026-11-24 | 289 KB | [`9633d0814382…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.14.2-arm64.apk) |
| v1.14.1 | 2026-11-24 | 289 KB | [`d460b4dbe92c…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.14.1-arm64.apk) |
| v1.14.0 | 2026-11-22 | 289 KB | [`55db48815070…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.14.0-arm64.apk) |
| v1.13.0 | 2026-11-22 | 289 KB | [`d3c998c6e2a2…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.13.0-arm64.apk) |
| v1.12.0 | 2026-11-22 | 289 KB | [`3b44d5fa47c2…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.12.0-arm64.apk) |
| v1.11.0 | 2026-11-22 | 285 KB | [`1724137332ee…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.11.0-arm64.apk) |
| v1.9.9 | 2026-11-21 | 280 KB | [`7cfb6f42baa6…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.9.9-arm64.apk) |
| v1.9.8 | 2026-11-21 | 280 KB | [`dbeef96e919c…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.9.8-arm64.apk) |
| v1.9.7 | 2026-11-21 | 276 KB | [`3462e049f328…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.9.7-arm64.apk) |
| v1.9.6 | 2026-11-21 | 270 KB | [`6d0f28581a09…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.9.6-arm64.apk) |
| v1.9.5 | 2026-11-21 | 262 KB | [`582d5806ca72…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.9.5-arm64.apk) |
| v1.9.4 | 2026-11-21 | 262 KB | [`6e3240e04f8d…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.9.4-arm64.apk) |
| v1.9.3 | 2026-11-21 | 261 KB | [`c4b3636208c7…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.9.3-arm64.apk) |
| v1.9.2 | 2026-11-21 | 261 KB | [`ad7f85cd7cca…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.9.2-arm64.apk) |
| v1.9.1 | 2026-11-21 | 257 KB | [`a1ae26ffad6b…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.9.1-arm64.apk) |
| v1.9.0 | 2026-11-21 | 257 KB | [`7264747e5834…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.9.0-arm64.apk) |
| v1.8.3 | 2026-11-21 | 257 KB | [`4b4859ace03d…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.8.3-arm64.apk) |
| v1.8.2 | 2026-11-21 | 257 KB | [`fa1f55fcac1b…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.8.2-arm64.apk) |
| v1.8.1 | 2026-11-21 | 257 KB | [`512b0564993d…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.8.1-arm64.apk) |
| v1.8.0 | 2026-11-21 | 257 KB | [`037324d95bd4…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.8.0-arm64.apk) |
| v1.7.2 | 2026-11-21 | 257 KB | [`f1e51d848634…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.7.2-arm64.apk) |
| v1.7.1 | 2026-11-21 | 257 KB | [`0b84b8b6d912…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.7.1-arm64.apk) |
| v1.7.0 | 2026-11-21 | 257 KB | [`abd6185b6650…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.7.0-arm64.apk) |
| v1.6.1 | 2026-11-21 | 244 KB | [`c2785aa82085…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.6.1-arm64.apk) |
| v1.6.0 | 2026-11-21 | 244 KB | [`0ecd197f0747…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.6.0-arm64.apk) |
| v1.5.0 | 2026-11-21 | 244 KB | [`de6a541cfa22…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.5.0-arm64.apk) |
| v1.4.0 | 2026-11-21 | 199 KB | [`cd2903d678d6…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.4.0-arm64.apk) |
| v1.3.0 | 2026-11-21 | 199 KB | [`503fe375a871…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.3.0-arm64.apk) |
| v1.2.0 | 2026-11-20 | 199 KB | [`75c8ec792c16…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.2.0-arm64.apk) |
| v1.1.1 | 2026-11-20 | 191 KB | [`6654047c1424…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.1.1-arm64.apk) |
| v1.1.0 | 2026-11-20 | 191 KB | [`15bf4da9f3df…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.1.0-arm64.apk) |
| v1.0.0 | 2026-11-20 | 187 KB | [`931c3830bb20…`] | [下载](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.0.0-arm64.apk) |

所有 APK 也放在 [`docs/apk/`](docs/apk/)，GitHub Pages 直接提供下载。覆盖安装保留容器与已下载的 IDE。

> 国内下载建议使用 [GitProxy 镜像](https://api.gitproxy.dev/raw.githubusercontent.com/zhaohairuiour/PyCharm4Android/main/docs/apk/PyCharm4Android-1.14.5-arm64.apk)；介绍页下载按钮默认走 GitProxy，也可切换 GitHub 直链。全版本文件与 SHA-256 均一致。

## 安装

1. 下载 `PyCharm4Android-1.14.5-arm64.apk`，允许“安装未知应用”；
2. 打开应用点「开始安装」：自动下载 Ubuntu base（约 30 MB）、校验 SHA-256、解包、apt 安装 X11/VNC，再下载 PyCharm（750 MB–1.3 GB）；
3. 点「启动 PyCharm」进入桌面。首次 JVM 冷启动 1–3 分钟，之后固定连接 `127.0.0.1:5901`。

要求：arm64 设备、Android 8.0+、建议 7 GB 内存、8 GB 以上空闲存储。

## 构建

需要 JDK 17、Android SDK 34、Gradle 8.7：

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
gradle assembleRelease --no-daemon
# 产物 app/build/outputs/apk/release/app-release.apk
```

`local.properties` 写入 `sdk.dir=/path/to/android-sdk`。当前使用 debug key 签名，覆盖安装可保留数据。

## 项目结构

- `app/src/main/java/com/pycharm4android/` — Android 端全部 Java 代码
- `app/src/main/assets/scripts/` — 容器内启动/解包/apt 脚本与 openbox 主题
- `docs/` — GitHub Pages 介绍页与全部历史 APK
- `PROJECT_LOG.md` — 逐版本开发日志与踩坑记录

## 已知限制

- 仅 arm64；x86 设备不适用。
- proot 的 ptrace 开销在启动/大量文件 IO 时明显，稳定后影响有限。
- 专业版需要 JetBrains 账号激活（含 30 天试用）。
- 首次安装依赖网络，国内建议使用阿里云/华为云镜像（应用内可切换）。

## 声明

本项目为非官方封装，与 JetBrains s.r.o. 无隶属关系。PyCharm 是 JetBrains s.r.o. 的商标；APK 不包含 IDE 本体，安装时从 JetBrains 官方 CDN 下载。

## License

[MIT](LICENSE)
