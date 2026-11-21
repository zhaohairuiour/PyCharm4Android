# PyCharm for Android（单 APK · 容器方案）

把 JetBrains 官方 **PyCharm Community（Linux arm64 版）** 跑在 Android 上的单 APK 封装。
不是"重新编译移植"，而是**在 APK 内自带一套 Linux 用户态 + X 服务器 + 原生渲染通道**。

> 已在本机用等价脚本跑通：PyCharm 2024.3.5 在 proot 容器里启动，Xvfb 出图，Raw RFB 抓帧与触摸点击均正常（见 `docs/screenshot-welcome.png`，x86_64 验证环境截图）。

---

## 为什么不能把 Linux/Windows 版"编译成 APK"

| 事实 | 后果 |
|---|---|
| Android 运行时是 ART/DEX，没有 JVM 字节码解释器 | 桌面 JVM 的 `.jar`/`.class` 无法直接在 Android 进程里执行 |
| 桌面 OpenJDK/JBR 链接 **glibc + Linux 系统调用**，Android 用 bionic | "内置 JVM"本身就必须配一个 Linux 用户态环境 |
| PyCharm UI 是 **Swing/AWT**，需要 X11/Wayland 显示服务与 `libawt_xawt` | Android 没有 X server，必须自建（Xvfb 或 Termux:X11） |
| IntelliJ 平台还依赖 `fsnotifier`、本地 native 库、文件系统语义 | 逐库替换等于重写 IDE，JetBrains 也未做过 |

所以所有"安卓版 PyCharm"本质都是 **Linux 版本 + 容器 + X 显示**。本 APK 把这三层都装进了一个应用里。

## 本项目做了什么

```
APK
├─ assets/proot/                 Termux 版 proot（arm64）+ libtalloc + libandroid-shmem
├─ assets/scripts/setup.sh       容器内 apt 安装 X11/VNC/字体/运行库
├─ assets/scripts/run-ide.sh     启动 Xvfb → openbox → x11vnc → PyCharm
├─ Java
│   ├─ SetupService              前台服务：下载 rootfs/PyCharm、解包、apt 安装
│   ├─ TarGz                     纯 Java tar.gz 解包（保留权限位/符号链接/硬链接）
│   ├─ Proot                     组装 proot 命令与绑定目录
│   ├─ IdeService                前台服务：常驻容器
│   ├─ VncClient                 自研 RFB 客户端（仅 Raw + CopyRect，无编码开销）
│   └─ VncView / VncKeyboardView Canvas 直接绘制帧缓冲 + 触摸/键鼠注入
└─ 首次运行时下载（不打包进 APK）
    ├─ ubuntu-base 24.04 arm64（≈30 MB）
    └─ pycharm-community-2024.3.5-aarch64（≈750 MB，JetBrains 官方 CDN）
```

关键取舍：
- **显示不做浏览器中转**：不用 WebView/noVNC。x11vnc 只发 Raw 像素，客户端直接写 `Bitmap` 并绘制，省掉 JS 解码与两次内存拷贝。
- **proot 免 root**：不需要 root，也不要求设备解锁；代价是 `ptrace` 带来的系统调用开销（实测启动阶段最明显，稳定运行后影响有限）。
- **中文输入**：不在容器里装 ibus/fcitx。Android 输入法输入拼音后，把汉字以 **Unicode keysym** 注入 X（`0x01000000 | codepoint`），AWT 直接按字符接收。
- **APK 只有 ~210 KB**：所有大件在首次运行时下载，避免 APK 膨胀到 GB 级。

## v1.9.6：深色 Windows 风格标题栏 + 底部控制坞重做

- **标题栏**：openbox 默认 Clearlooks 主题是 Win7 蓝色渐变 + 15 px 小按钮，在手机上既小又旧。现改为自带 `Breeze-PyCharm` 主题：`#2B2D30` 深色平铺标题栏、`#3574F0` 细分隔线、12 pt 粗体标题、30 px（2 倍放大）的图标按钮，关闭按钮为 `#E81123` 红色 ×，与 PyCharm 的 Darcula 风格一致。
- 同时写入完整 `rc.xml`：`<decor>yes</decor>` + `<maximized>yes</maximized>` 强制 PyCharm 保留标题栏并最大化；补上 `Alt+F9` 最小化、`Alt+F10` 最大化/还原、`Alt+F4` 关闭、`Alt+Tab` 切换（默认配置没有 F9/F10）。主题与配置每次启动从 APK assets 重新安装，升级即生效。
- **底部控制坞**：原来是一排灰色文字块。改为深色半透明圆角面板、两行布局：第一行「键盘 / 中文 / 日志 / 诊断 / 重连 / 退出」，第二行「Ctrl / Alt / Shift / Esc / Tab / 方向键 / PgUp / PgDn / 右键 / 放大 / 缩小 / 适配」，每个按钮是带描边的胶囊 + 水波纹按压反馈，退出按钮红色；VNC 视图底部预留 90 dp，桌面不被遮挡。
- 版本 1.9.6 / versionCode 22，覆盖安装；启动日志应出现 `窗口主题 Breeze-PyCharm 已安装`。

## v1.9.5：长按改为纯时间判定 + 双指轻点右键兜底

- 1.9.4 实测仍无震动、无右键，说明 `armLongPress()` 没被调用，而不是震动马达的问题。两个隐藏条件把长按挡掉了：
  1. `PostDelayed` 可能在 499 ms 就执行，`if (uptime - downTime >= 500)` 不成立就直接返回，**不会重新 post**；若手指完全不动（没有 MOVE 事件补判），长按永远不触发。
  2. `ACTION_UP` 的补判要求 `moved <= 36 dp`；按住时手指轻微滑动超过 36 dp 就被当成普通移动，松手也不进入右键分支。
- 现在长按**只按按压时长判定**：定时器到点无条件 `armLongPress()`；取消条件放宽到明显滑动 **80 dp**；`ACTION_UP` 只要按压 ≥500 ms 且未进入拖拽就补触发。轻微信移不再影响长按。
- 震动加强为 `Vibrator` 80 ms / amplitude 255，并保留 `performHapticFeedback(..., FLAG_IGNORE_GLOBAL_SETTING)`；每次长按弹 Toast「长按已识别：拖动=拖拽，松手=右键」。
- 新增**双指轻点 = 右键**（≤400 ms、双指位移 ≤24 dp），不依赖长按和马达，作为第二条右键路径；工具栏「右键」按钮仍是第三条。
- 若仍无 Toast，日志里应能看到 `[触摸] DOWN`；连 `DOWN` 都没有说明触摸事件没到达 `VncView`（检查日志面板是否还盖在桌面上、软键盘是否弹出）。
- 版本 1.9.5 / versionCode 21，覆盖安装。

## v1.9.4：长按三路判定 + 硬震动/Toast、后台重连收尾、IDE 退出自动重启

- **长按判定不再只靠定时器**：`Handler.postDelayed` 会被 UI 线程的帧绘制/`setPixels` 推迟，1.9.3 实测连震动都没有。现在三条独立路径任一命中即成立：500 ms 定时器、`ACTION_MOVE` 时按 `SystemClock.uptimeMillis()` 补判、`ACTION_UP` 时按按压总时长补判（即使一个 MOVE 都没收到，松手也能识别）。
- **震动兜底**：同时调用 `Vibrator` 直接震动 40 ms（新增 `VIBRATE` 权限）与 `performHapticFeedback(LONG_PRESS, FLAG_IGNORE_GLOBAL_SETTING)`，系统关闭“触摸反馈”也能感到；每次长按弹 Toast「长按已识别：拖动=拖拽，松手=右键」。
- **容差**：长按取消容差 36 dp，按住时的自然抖动不再取消；长按后移动超过 12 dp 才进入拖拽，拖拽按下左键，原地松手发右键（mask 4→0）。
- **诊断日志**：`[触摸] DOWN x,y`、`[触摸] UP moved=N t=Nms armed=… drag=…`、`[触摸] CANCEL`。若长按无 Toast 且日志连 `DOWN` 都没有，说明触摸事件没到 `VncView`（被覆盖层拦截）。
- **切后台/被回收后仍显示“正在重连”**：连接只在 `onResume` 建立，`onPause` 关闭 socket 并移除所有重连回调，`onError` 增加 `resumed` 守卫；容器已退出时显示「容器已停止（IDE 已退出），点「重连」会重新拉起容器」，不再空转。
- **PyCharm 里点 Restart IDE 直接关闭 App**：`run-ide.sh` 原结尾 `wait $IDE_PID`，IDE 退出即脚本结束，`trap cleanup` 杀掉 Xvfb/x11vnc，`IdeService` 随之 `stopSelf()`。改为守护循环：Xvfb/x11vnc/openbox 常驻，IDE 退出后自动重新拉起；兼容 JetBrains Restarter（最多观察 15 s，检测到新实例则等它结束，避免双实例）；连续退出 5 次后改为 60 s 一次。
- 版本 1.9.4 / versionCode 20，覆盖安装；启动日志应出现 `run-ide.sh v1.9.4` 与 `[ide] 已刷新启动脚本`。

## v1.9.3：长按可用（震动反馈 + 拖拽/右键双语义）

- 旧实现两个问题：长按阈值只有 12 dp，手指按住时的自然抖动经常超过阈值，`ACTION_MOVE` 直接把 500 ms 定时器取消，长按几乎触发不了；即使触发，也只是延迟发一次左键按下，没有震动或任何反馈，用户无法判断是否生效。
- 容差改为 24 dp：按住期间抖动不再取消长按；500 ms 触发时调用 `performHapticFeedback(LONG_PRESS)` 震动一次，日志输出 `[触摸] 长按已识别（移动=拖拽，松手=右键）`。
- 长按后的语义拆分：
  - **长按后移动超过 12 dp** → 发送左键按下并持续带 mask 1 移动，即拖拽（选文本、拖窗口、拖动滚动条）；
  - **长按后直接松手** → 发送右键按下/抬起（mask 4→0），等价于桌面 context menu；
  - 普通轻点仍是左键单击，位移超过 12 dp 不再误判为点击。
- 版本 1.9.3 / versionCode 19，覆盖安装。

## v1.9.2：鼠标真正能动 + 蓝圈彻底消失（1.9.1 的修复未生效）

- 1.9.1 实测仍然“鼠标不动、蓝圈还在”，日志末尾给出根因：`[触摸] 处理失败：android.os.NetworkOnMainThreadException`。触摸回调在 UI 线程直接写 VNC socket，被 StrictMode 拦截，异常又被 try/catch 吞掉，因此所有 PointerEvent 都没发出去。
- `VncClient.pointer/key` 改为投递到单线程 `ThreadPoolExecutor`（`ArrayBlockingQueue(16)` + `DiscardOldestPolicy`），UI 线程只入队、不碰 socket；快速滑动时丢弃旧事件，避免排队延迟。
- `VncView` 重写为单指状态机：**移动=只发位置（mask 0）**；轻点=成对 press/release；长按 500 ms 后拖动=按住左键拖拽；双指缩放/平移。删除全部自绘圆圈代码，不再有蓝色触点标记。
- 指针改为 App 内置箭头 `Path`（白填充黑描边）跟随最后触点，不依赖服务端是否合成光标；同时**撤掉 Cursor 伪编码（-239）请求**——x11vnc 的 Cursor 帧字段与解析不一致，请求后会造成协议错位（后续帧被当 Cursor 读掉），画面卡死。
- 版本 1.9.2 / versionCode 18，覆盖安装即可（保留容器与已下载的 PyCharm）。

## v1.9.1：鼠标移动模型 + 去掉自绘蓝圈（触摸仍在主线程写 socket，未生效）

- 蓝色圆圈是 App 自己画的“最后触点标记”（`VncView.onDraw` 里的 `drawCircle`），不是 PyCharm 的鼠标指针，已删除。
- 旧触摸模型把每次按下都当成左键点击，拖动时一直带按键，视觉上“鼠标不动”。新模型：
  - **单指拖动 = 移动鼠标**（button mask 0，只发 PointerEvent 位置，不按键）；
  - **轻点 = 左键单击**（按下/抬起成对，位移超过 12 dp 就不算点击）；
  - **长按 0.5 s 后拖动 = 按住左键拖拽**（选中文本、拖窗口）；
  - 双指仍是缩放/平移。
- 曾尝试请求 RFB Cursor 伪编码（-239）绘制服务端光标；实测 x11vnc 的 Cursor 帧与本地解析不一致，1.9.2 已撤销该请求，改用内置箭头。

## v1.9.0：触摸崩溃防护 + 崩溃堆栈自采集

- **越界矩形裁剪**：`applyUpdate` 对服务端给出的 `x/y/w/h` 先裁剪到 bitmap 边界，`setPixels` 包 try/catch。x11vnc 在窗口重绘时偶尔给出超界矩形，旧代码直接 `ArrayIndexOutOfBounds` 把 UI 线程打死——点一下界面触发大面积重绘时最容易复现。
- **不再 recycle 旧 Bitmap**：重连时旧绘制线程可能仍在读，`recycle()` 后触发 `Canvas: trying to use a recycled bitmap`。
- **触摸路径全程兜底**：`onTouchEvent` → `handleTouch` 包 try/catch；坐标 clamp 到 `[0,fbW-1]×[0,fbH-1]`；`fitScale()` 退化为 0 时回退 1。
- **pointer 竞态修复**：`VncClient.pointer()` 先取本地 `out` 引用再 `synchronized`，避免 `out` 被 `close()` 置空后 `synchronized(null)` 抛 NPE。
- **崩溃自采集**：`App` 安装 `UncaughtExceptionHandler`，堆栈写入 `files/crash.txt`；下次进入桌面页自动把 `[上次崩溃]` 堆栈写进日志面板并删除文件，可直接看出崩在哪一行。
- **帧缓冲复用**：`readRaw` 复用 `byte[]`，不再每帧分配 4 MB（1280×800），降低 GC 抖动。

## v1.8.3：修复 RFB 握手少读/多读导致的“连上但永远黑屏”

- 现象：x11vnc 日志已出现 `Got connection from client 127.0.0.1`、`Client Protocol Version 3.8`，但 App 一直停在「连接 127.0.0.1:5901 …」，画面不出现。
- 根因：解析 `ServerInit` 时在 blueShift 之后读了 **4 个 padding 字节**，RFB 规范只有 3 个。多读的 1 字节把后面的 `nameLen` 吃掉，`nameLen` 变成一个巨大值，`readFully(name)` 永久阻塞，`onConnected` 永远不触发。
- 修复：padding 改回 3 字节；`nameLen` 加 0~4096 合法性校验；握手阶段 `setSoTimeout(12s)` 防止半包卡死，握手完成后恢复阻塞读。
- 这条与 x11vnc 无关：服务端一直正常，是客户端自己的协议解析 bug（1.0.0 起就存在，只是之前从未真正连上过）。

## v1.8.2：修掉“误杀正在监听的 x11vnc”

- 实测 `vnc.log` 已打印 `Listening for VNC connections on TCP port 5901`，说明 x11vnc 本身正常；但旧版用 `/proc/net/tcp` 探测出现**假阴性**，脚本判定“未监听”后把进程杀掉并报 `VNC_FAIL`，客户端随后自然是 `Connection refused`。
- 改为**以 x11vnc 自己的日志为准**：出现 `Listening for VNC connections` 即判定就绪并写 `/root/.vnc_port`，不再杀进程；只有进程真的退出才重试下一组参数。`/proc` 探测仅作兜底。
- 增加 `-noshm`：日志里的 `shmget(scanline) failed: Function not implemented` 是 proot 不支持 SysV 共享内存导致，禁用 shm 后走普通 framebuffer 读取，少一条错误路径。
- 客户端优先固定 `127.0.0.1:5901`（v4 一定在监听），每 4 次失败才切 `::1` 试一轮，避免在 IPv6 上空转。
- 日志里 `xkbcomp` 的 `Could not resolve keysym XF86...`、`Openbox-Message: Unable to find a valid menu file`、`Xlib: extension "DPMS" missing` 都是无害警告，可忽略。

## v1.8.1：每次启动刷新容器脚本（修复升级后仍跑旧 run-ide.sh）

- **根因**：`run-ide.sh` 只在「完整安装」时复制到 `/opt/host`。升级 APK 后如果只做 IDE 安装/直接启动，容器里仍是旧版脚本（例如还绑 5900、没有独立日志），所以客户端连 5901 永远连不上，诊断里 `/root/vnc.log` 也不存在——这正好对应实测现象。
- `IdeService.runIde()` 启动前用 APK 内的 `run-ide.sh / extract-ide.sh / setup.sh` 覆盖 `/opt/host` 并 chmod 0755，日志打印 `[ide] 已刷新启动脚本 run-ide.sh=NNNNB`。
- 脚本首行打印 `[run-ide +0s] run-ide.sh v1.8.1 · 固定 VNC 端口 5901`，可一眼确认跑的是新脚本。

## v1.8.0：固定 VNC 端口 5901

- 服务端与客户端都写死 **5901**（`display :1` 的传统 VNC 端口），删除 5900~5910 扫描与端口轮询，行为可预期。
- 启动前先清掉上一次残留的 `x11vnc` / `Xvfb` 与 `/tmp/.X1-lock`、`/tmp/.X11-unix/X1`，避免端口/锁文件冲突（旧版残留进程会占住端口，新 x11vnc 直接退出）。
- x11vnc 三次尝试都固定 `-rfbport 5901`：`-localhost -noxdamage` → `-allow 127.0.0.1 -noxdamage` → `-allow 127.0.0.1`，日志记录 `x11vnc -version` 与每轮退出情况。
- 客户端只连 5901，仅在 `127.0.0.1` / `::1` 间交替；工具条新增「重连」按钮，可立即重试而不必退出页面。
- 失败时把 `/root/vnc.log` 末尾 30 行写进 ide.log 关键事件，可直接看到 `XOpenDisplay failed`、`bind: Address already in use`、`invalid option` 等真实原因。

## v1.7.2：VNC 端口按实际监听值连接（5901 优先）

- **x11vnc 端口不再写死 5900**：容器按 `5901 → 5900` 的顺序尝试（`display :1` 的 VNC 惯例端口是 5901，部分 x11vnc 构建/端口占用下不会落在 5900），每轮启动后用 `/proc/net/tcp`、`tcp6` 扫描 5900~5910，找到实际 LISTEN 的端口即写入 `/root/.vnc_port`，日志打印「VNC 实际监听 5901」。
- **客户端读真实端口**：`DisplayActivity.readVncPort()` 读 `rootfs/root/.vnc_port`；文件还没生成时按 5900→5901→5902→5903 轮询，并在 `127.0.0.1` / `::1` 间交替，状态栏显示当前尝试的 `host:port`。
- **组合回退**：5901/v4 → 5900/v4 → 5901/v6 → 5900/v6，全部失败才报 `VNC_FAIL` 并输出 `vnc.log` 末尾。

## v1.7.1：x11vnc 启动失败定位与 IPv4/IPv6 回退

- **日志重排**：桌面页日志不再被 PyCharm 的启动刷屏淹没。`composeLog()` 先抽「关键事件」（`[run-ide`、`x11vnc`、`Xvfb`、`VNC_`、诊断行，保留最后 150 条），再附 ide.log 末尾 25 行原始输出。
- **每个服务独立日志**：`/root/xvfb.log`、`/root/vnc.log`；x11vnc 失败时自动把 vnc.log 末尾 15 行写进 ide.log。
- **端口探测不再依赖 `ss/netstat/bash /dev/tcp`**：直接读 `/proc/net/tcp`、`/proc/net/tcp6` 的 0A（LISTEN）状态判断 5900 是否真的在监听。
- **x11vnc 三轮参数回退**：`-listen 127.0.0.1` → `-listen ::1` → `-listen localhost`（全部保持回环，不开公网）。旧版用 `-localhost`，若容器里 `localhost` 解析到 IPv6，x11vnc 只绑 `::1`，Java 客户端连 `127.0.0.1` 就永远 `Connection refused`——这是最可能的成因。
- **客户端双栈重试**：每 1.5 s 重连，并在 `127.0.0.1` / `::1` 之间交替，状态栏显示当前尝试的地址。
- **「诊断」按钮**：一键在容器里收集 `ls -l /tmp/.X11-unix`、`pgrep -a Xvfb/x11vnc/pycharm`、xvfb.log/vnc.log 末尾，输出到日志面板「服务日志」段，可直接看出 Xvfb 死没死、x11vnc 退出的原因。

## v1.7.0：启动日志、自动重连、应用内软键盘

- **启动日志面板**：进入桌面页即显示 `rootfs/root/ide.log` 尾部（Xvfb / openbox / x11vnc / PyCharm JBR 输出）与 `LogBus` 服务日志，800 ms 增量刷新、自动贴底；顶部一行阶段提示（启动 Xvfb → 启动 x11vnc → PyCharm JVM 启动中）与已等待秒数。连上 VNC 后自动隐藏，工具条「日志」可随时重新打开（固定显示）。
- **启动顺序改为并行**：Xvfb 套接字就绪 → 立刻拉起 PyCharm（JBR 冷启动最耗时）→ 再起 openbox 与 x11vnc。VNC 通常 2~4 s 内可连，先看到 PyCharm 启动画面，不必等 JVM 全部加载完。
- **Connection refused 不再当错误**：客户端改为 1.5 s 间隔自动重连，提示「x11vnc 尚未监听（属正常）」；只有非 refused 的异常才显示具体原因。旧版一次连接失败就停在“连接断开”，x11vnc 稍晚启动即整页卡死。
- **应用内软键盘 `SoftKeysView`**：自绘 6 行键盘（Esc/Tab/Ctrl/Alt/Shift、数字、q~p、a~l、z~m、空格、PgUp/PgDn/Home/End、Del/⌫/⏎），直接注入 X keysym，不唤起系统输入法，因此不会把 VNC 画面顶上去或遮住整屏；工具条「键盘」开关，「收起」收起，Ctrl/Alt/Shift 可锁定（高亮）。中文输入保留「中文」按钮走系统输入法。
- **`windowSoftInputMode="adjustNothing"`**：系统输入法浮在画面上而不是 resize 画布，避免缩屏后画面被压扁。
- 若仍不喜欢自带键盘，可用 Gboard「浮动键盘」：设置 → 键盘 → Gboard → 浮动模式，再把自带键盘收起。

## v1.6.1：解包失败可诊断 + Java 解包兜底

- **不再盲目重下 1.3 GB**：旧逻辑解包失败就删包重下、再解包，两次都失败才报错——若根因在容器侧（路径/权限/tar），重下完全无效。现在先 `gzip -t` 校验压缩包完整性：只有包损坏才重下。
- **解包步骤独立成脚本** `assets/scripts/extract-ide.sh`，解到临时目录 `/opt/.extract` 再用 `find` 定位 `bin/pycharm.sh` 的根目录改名，避免 `ls -d pycharm*` 猜目录名。
- **诊断日志**：打印包文件 `ls -l`、`df -h /opt`、`df -i /opt`、`tar --version`、`gzip` 路径、`GZIP_RC`、`TAR_RC`、tar 输出最后 40 行。下次报错可直接定位是「文件不可见 / 空间 / 权限 / 包损坏」哪一类。
- **Java 解包兜底**：容器 tar 失败时，改用 APK 内置的 `TarGz`（纯 Java，保留权限位/符号链接/硬链接）直接解到 `rootfs/opt/.extract_java`，再改名为 `opt/pycharm`，绕开 proot 对 `tar` 的影响。
- **空间预估按版本体积计算**：`size*3.9 + 400 MB`（专业版 2026.x 约需 5.5 GB），不足时在下载前就报错，而不是解到一半失败。

## v1.6.0：专业版版本管理、日志滚动彻底修复

- **版本管理二级菜单**：主界面「版本」按钮改为菜单 —— 「选择并安装版本」/「清理下载缓存」/「卸载已安装的 PyCharm」。当前已装版本显示在菜单标题里（如 `2025.3（社区版）`）。
- **社区版 / 专业版分流**：先选版本类型，再拉取对应目录 —— 社区版 `code=PCC` 77 个 ARM64 版本（2022.3 → 2025.3），专业版 `code=PCP` 104 个 ARM64 版本（2022.3 → 2026.3.0.1，275 条发布里筛出）。列表按版本号升序（旧 → 新），标「已安装」。
- **安装前空间校验**：对话框给出安装包体积、解包后额外占用（约 2 倍）、当前可用空间；专业版额外提示「首次启动需 JetBrains 账号激活，含 30 天试用」。
- **清理缓存 / 卸载**：清理只删 `pycharm-*.tar.gz`，不动 rootfs 与项目；卸载在 PyCharm 运行中会拒绝，删除 `/opt/pycharm` 与版本标记。
- **日志滚动**：不再每 400 ms `setText` 整段重排（那会把滚动位置钉回顶部并触发 `scrollTo(max)` 抖动）。改为**增量 append**：新内容以已显示文本为前缀时只 `append` 差集，显示位置天然不动；仅在用户本来贴底时才 `scrollTo(max)` 跟随；缓冲区截断导致前缀不匹配时才整体替换。
- **主界面副标题显示版本号**（`v1.6.0`），可直接确认装的是哪一版。

## v1.5.0：版本列表修复、2026 专业版、官方图标、日志滚动

- **版本列表解析失败**：旧缓存用 `Io.readText()` 读，最多 4096 字节，JetBrains 的 releases JSON（约 800 KB）被截断，报 `Unterminated string at character 4096`。改为 `Io.readAll()` 全量读取；缓存格式改成逐行文本 `version;edition;url;sha;size`（约 20 KB），不再解析大 JSON。
- **2026 版**：Community（`code=PCC`）的最新发布停在 2025.3；2026.x 属于 Professional（`code=PCP`，最新 2026.3，linuxARM64 约 1.31 GB）。版本列表现在合并两个 API，条目标注「社区版 / 专业版」，选择后按 `ce:<ver>` / `pro:<ver>` 存 key，避免同名版本混淆；专业版安装后首次启动需 JetBrains 账号激活（30 天试用），对话框里有提示。
- **日志滚动**：滚动条反复跳动的根因是每次刷新都 `fullScroll(FOCUS_DOWN)`，且 `setText` 会把位置重置到顶部。现在记录「是否贴底」状态：只有用户本来就在底部时才自动跟随；用户往上滚后保持当前位置（更新文本前后保存/恢复 `scrollY`）。日志保留 300 行，可长按复制。
- **图标**：改用 PyCharm 官方 300×300 徽标（`resources.jetbrains.com`），生成 mdpi~xxxhdpi 五套 `ic_launcher.png`，移除 adaptive-icon（否则 v26+ 仍显示旧的矢量前景）。



- `execve("/usr/bin/env"): No such file or directory` 在 rootfs 完全健康时（bin/lib/sbin 链接正常、env 68368B、bash 1543048B）仍然出现，说明缺的不是镜像里的文件，而是 **proot 自己的 loader**。Termux 版 proot 在编译期把 loader 路径写死为 `/data/data/com.termux/files/usr/libexec/proot/loader`，不在 Termux 环境里必然找不到；proot 的提示信息里就有一条 “the loader was not found or doesn't work”。
- 修复：`Proot.builder` 显式设置 `PROOT_LOADER=<runtime>/loader`（以及 `PROOT_TMP_DIR`、`PROOT_NO_SECCOMP=1`），loader 由 assets 解出并 chmod 0755。
- 启动日志会打印 loader/proot 的字节数，便于确认资产解包完整。



- 根因：Ubuntu 24.04 是 usr-merge 布局，`/bin`、`/lib`、`/sbin` 都是指向 `/usr/...` 的符号链接；这些链接缺失或悬挂时，内核找不到 ELF 解释器 `/lib/ld-linux-aarch64.so.1`，`proot` 会在启动瞬间报 `execve("/usr/bin/env"): No such file or directory`，看起来像 apt 失败，实际是容器根本没起来。
- 新增 `Rootfs.verify()`：幂等校验 + 自动修复——重建 `bin → usr/bin`、`lib → usr/lib`、`sbin → usr/sbin`（符号链接建不出来时退化为硬链接镜像目录）；检查 `env/bash/tar/sh` 与 `ld-linux-aarch64.so.1` 是否存在，递归补 `usr/bin`、`usr/sbin` 的可执行位。每次启动容器前都会跑一遍，`修复/重装` 时若发现 rootfs 损坏会自动重新解包。
- `Proot` 增加兜底绑定：若 `/lib/ld-linux-aarch64.so.1` 不可达，直接 `-b usr/lib:/lib`、`usr/bin:/bin`、`usr/sbin:/sbin`，绕开符号链接依赖。
- apt 之前新增容器自检 `echo CONTAINER_OK`；失败时日志打印 `bin/lib/ld/env` 的存在性与 env/bash 字节数，便于定位。
- 日志区改为独立 ScrollView（固定 240 dp），支持手指滚动与选中复制，不再被外层页面抢手势；更新时自动滚到底部。

## v1.2.0：版本管理 + 截断/选源修复
- **系统镜像完整性**：下载完成后按官方 SHA-256 校验（`04207713…7ff2`），本地已有文件在解包前也会重校验；哈希不符直接删除重下，解包异常再删包重下一次，杜绝 `Unexpected end of ZLIB input stream`。旧包残留的半截 `ubuntu-base-arm64.tar.gz`（≥20 MB 但内容不全）不会再被误当作完整包解压。
- **下载源对话框**：改为「选择 + 应用」两步，安装中也能切换（会置 cancel 并用新源重启，已下载部分 Range 续传）。
- **PyCharm 版本管理**：「版本」按钮列出 JetBrains releases API 中所有提供 `linuxARM64` 包的分发版本（2022.3 → 2025.3，共 77 个，升序），点选即下载该版本并替换现有 `/opt/pycharm`，**不动系统镜像与 apt 依赖**；单独安装只需下载 IDE 包。API 不可达时用 12 小时缓存，再无缓存则回退到内置 2022.3 / 2023.3 / 2024.3.5 / 2025.3 四个已验证版本。
- 已安装版本写入 `.ide_version`，主界面显示「PyCharm：已装版本 · 目标版本」。

## 下载源与进度（v1.1.0 起）

- 主界面「**下载源**」可切换 Ubuntu 系统镜像来源：自动（国内镜像优先）/ 阿里云 / 华为云 / 清华 TUNA / 中科大 USTC / 官方。
- 自动模式按 阿里云 → 华为云 → 清华 → 中科大 → 官方 顺序并发尝试，任一源失败自动切下一个；换源后仍按已下载字节 **Range 续传**，不重下。
- 本机实测吞吐（4 MB 抽样）：阿里云 6.5 MB/s、华为云 6.9 MB/s、清华 3.3 MB/s；官方 cdimage 国内直连普遍更慢。
- PyCharm 安装包（约 750 MB）**没有国内镜像**（TUNA/USTC/阿里云/华为云均无 jetbrains 目录），只能走 `download.jetbrains.com`，并以 `download-cdn.jetbrains.com` 作为回退域；速度取决于国际链路。
- 进度刷新：下载回调 250 ms 一次，界面 400 ms 轮询，状态栏通知 1 s 一次；显示 `已下载 / 总大小 · 百分比 · 实时速度 · 当前镜像主机`。系统镜像对应进度区间 8→24%，PyCharm 54→78%。
- 安装进行中不允许切换下载源（按钮会提示）；空闲时切换后点「开始安装」即从断点续传。

## 安装与使用

1. 安装 `PyCharm4Android-1.9.6-arm64.apk`（arm64 设备，Android 8.0+）。
   - 从 1.4.0 及更早版本**覆盖安装**（同一签名，versionCode 6）。apt 依赖若已装完，直接点「版本」选 PyCharm 即可，不必重跑整套安装。
2. 打开应用 → **开始安装**（约 5~20 分钟，取决于网络：rootfs 30 MB + PyCharm 750 MB + apt 依赖 ~400 MB）。
3. 安装完成后点 **启动 PyCharm**：先起容器（30~90 秒），再打开显示窗口。
4. 首次进入 IDE 会依次出现 **许可协议**（勾选后 `Continue`）和 **数据共享**（点 `Don't Send`），只需一次。
   - 欢迎页若提示 "launched with a script launcher"，点 `Don't show again` 即可，不影响使用。
5. 工具条：`键盘 / Ctrl / Alt / Shift / Esc / Tab / 方向键 / PgUp / PgDn / 右键 / 放大 / 缩小 / 适配`。
   - 单指 = 鼠标（按下-拖动-抬起），双指 = 缩放/平移。
   - 项目放在手机存储里，容器内挂载为 `/sdcard`，PyCharm 里选 `/sdcard/...` 打开。

### 磁盘与内存
- 磁盘：rootfs + PyCharm 解包后约 **3.2 GB**（装在应用私有目录，卸载即清）。
- 内存：PyCharm 桌面版实测常驻 1.5~2.5 GB。**6 GB 设备属于下限**，建议先关掉后台应用；默认 JVM 堆 1024 MB（`Prefs` 可调）。
- 分辨率默认 `1280x800`；调低可显著降低像素传输与合成开销。

## 已知限制（务必先看）

1. **不是官方产品**：PyCharm 商标与版权归 JetBrains；本项目只是把官方 Community 版（Apache 2.0 代码 + 官方分发包）装进容器运行，安装包在运行时从 JetBrains 官方 CDN 下载。
2. **性能**：ARM 手机 + proot + 无 GPU 加速，索引大型项目会明显慢于桌面机；冷启动 1~3 分钟属正常。
3. **JCEF 关闭**（`-Dide.browser.jcef.enabled=false`）：Markdown 预览、部分内置浏览器面板不可用，换取稳定与内存。
4. **无音频、无系统级快捷键**（Alt+Tab 之类会被 Android 拦截）。
5. **targetSdk 28**：这是 Android 10+ 允许"执行应用私有目录中的二进制"的前提，代价是系统会提示"为旧版 Android 构建"。侧载安装不受影响；上架 Google Play 不可行。
6. 首次 apt 安装依赖官方/清华镜像；若网络不通会失败，日志会给出 `HTTP xxx` 与出错包名。

### 为什么不是"内置 JVM + Swing/AWT"（不用 proot）

- PyCharm 用的是 JetBrains Runtime，本质是 **glibc 动态链接的 Linux ELF**；Android 的 C 库是 bionic，不提供 glibc，也**没有 X11/Xlib**，Swing/AWT 的 `libawt_xawt` 必须连 X server 才能出窗口。
- Android 运行时是 ART/DEX，**没有 JVM 字节码解释器**；把 PyCharm 的 jar 塞进去也无法执行，且 HotSpot 需要 `fork/exec`、`/proc`、`ptrace` 等 Linux 语义。
- 因此"内置 JVM"不是绕开容器的办法：JVM 本身就要 Linux 用户态。proot 只是一个**免 root 的用户态 chroot**（ptrace 转发路径），不是模拟器、不做指令翻译，额外开销约为单次系统调用转发（个位数百分比）；真要比它快只有 root + chroot（你的设备未 root）或远程开发。

## 常见问题

- **卡在"安装容器内依赖"**：多为镜像不可达。改 `Prefs.aptMirror`（默认 `http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports`）后点"修复/重装"。
- **启动后黑屏**：先看 `显示窗口` 顶部状态；若 60 秒仍未连上，说明 `Xvfb/x11vnc` 没起来，查看应用内日志（`/root/ide.log` 同步在日志面板）。
- **`No JRE found`**：容器里存在无效的 `JAVA_HOME`；`run-ide.sh` 已 `unset JAVA_HOME/JDK_HOME/PYCHARM_JDK`，如自定义脚本请保持该行为。
- **想彻底重来**：系统设置 → 应用 → 存储 → 清除数据（会删掉 rootfs 与 PyCharm，重新下载）。

## 构建

```bash
# 需要 JDK 17、Android SDK platform 34 + build-tools 34.0.0
cd pycharm-android
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease      # 产物：app/build/outputs/apk/release/app-release.apk
```

无 NDK 依赖；Kotlin 未使用（纯 Java，减少构建面）。

## 备用方案（不装本 APK）

`scripts/termux-fallback.sh`：用 Termux + termux-x11 手动部署同一套容器，适合想自己控制版本或设备装不上本 APK 的情况。原生 X 服务器（Termux:X11）帧率更高，但需要装 Termux / Termux:X11 两个应用。
