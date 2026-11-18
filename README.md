# 阅微 · 小说阅读器

一个面向 Windows 的桌面小说阅读器。支持导入本地 TXT，也兼容 Legado（开源阅读）3.0 书源，可在线搜索、阅读和下载。书籍、阅读进度与设置全部保存在本机。

![Java](https://img.shields.io/badge/Java-17-3f5c48)
![JavaFX](https://img.shields.io/badge/JavaFX-17-3f5c48)
![License](https://img.shields.io/badge/License-MIT-3f5c48)

## 下载

Windows x64 安装包（内置运行时，无需安装 Java），下载后双击安装即可，支持覆盖升级：

[下载最新版 yuewei-reader-win64.exe](https://github.com/ShdlHH/yuewei-reader/releases/latest/download/yuewei-reader-win64.exe) · [全部版本与更新日志](https://github.com/ShdlHH/yuewei-reader/releases)

Android 客户端暂不提供安装包，按下方「Android 客户端」小节自行构建 APK。

## 功能

- 本地 TXT：自动识别编码、按章节标题分章、导入前预览，支持大文件导入
- 书架与阅读器：目录侧栏、上一章/下一章、阅读进度按章节与章内位置保存；章末继续下滑自动进入下一章，章首继续上滑回到上一章结尾
- 目录：章节搜索、在读标记、缓存圆点与底部进度信息，纸白/夜间/护眼三套主题自动适配；宽度可拖拽调整并记忆
- 在线书源：导入 Legado 3.0 书源，搜索、详情、目录、正文四段解析，支持规则校验与调试
- 下载与离线：整本下载、暂停/继续/取消、失败重试，已缓存章节断网可读
- 键盘翻页：空格/PageDown/→/↓ 下一页，PageUp/←/↑/退格 上一页；翻到章末或章首会继续跨章
- 多端同步：WebDAV（坚果云 / Nextcloud）上传阅读进度与书架（阅读排版各端独立，不参与同步）；进度写成 Legado 兼容的 `bookProgress/书名_作者.json`，手机装 Legado 并把 WebDAV 目录设为同一目录，即可白天手机读、晚上电脑自动接上
- Android 客户端：`mobile/` 提供本地 TXT 导入、在线书源搜索与阅读、按页阅读、WebDAV 双向同步（含删除同步与自动同步），与桌面端共用同一份进度与书架
- 阅读排版：默认京华老宋体（缺失时回退宋体并提示），支持导入 ttf/otf/ttc 字体、12–32 号字、1.2–3.0 倍行距（段内换行与段间距同步）、段落首行缩进、按章节预分页（滚轮整页翻页，页内不留滚动残影）、纸白/夜间/护眼三套主题，调整即时生效
- 标点着色：引号内对话朱红，句号、问号、感叹号、省略号/破折号、括号分别配色；六条规则可逐条开关与调色，阅读器工具栏可一键关闭
- 数据本地：SQLite 保存书籍与进度，正文缓存落文件，可设置缓存上限并一键清理

## 技术栈

| 层面 | 选型 |
|---|---|
| 界面 | JavaFX 17 |
| 构建 | Maven 3.8+ |
| 存储 | SQLite（`sqlite-jdbc`）+ 本地缓存文件 |
| 解析 | Jsoup、Jackson |
| 网络 | JDK `HttpClient`（超时、并发与请求间隔可配） |
| 打包 | `jpackage`（内置精简 JRE，产出 exe 安装包） |

## 运行

需要 JDK 17 与 Maven。

```powershell
mvn -o test          # 运行单元测试
mvn -DskipTests package
java -jar target\yuewei-reader.jar
```

也可以使用 JavaFX Maven 插件直接启动：

```powershell
mvn javafx:run
```

## 生成 Windows 安装包

需要 JDK 17 的 `jpackage`，以及 WiX Toolset 3 的 `candle.exe`、`light.exe`（加入 PATH）。

```powershell
# 测试与打包
mvn test
mvn -DskipTests package

# 准备 jpackage 输入目录
New-Item -ItemType Directory -Force target\package-input
Copy-Item target\yuewei-reader.jar target\package-input\

# 免安装目录
jpackage --type app-image --name 阅微阅读器 --app-version 0.6.0 --vendor 阅微 `
  --input target\package-input --main-jar yuewei-reader.jar `
  --main-class com.yuewei.reader.Launcher --icon packaging\app.ico --dest dist\0.6.0

# exe 安装包
jpackage --type exe --name 阅微阅读器 --app-version 0.6.0 --vendor 阅微 `
  --input target\package-input --main-jar yuewei-reader.jar `
  --main-class com.yuewei.reader.Launcher --icon packaging\app.ico --dest dist\0.6.0 `
  --java-options "-Dfile.encoding=UTF-8" `
  --win-shortcut --win-menu --win-menu-group 阅微 --win-dir-chooser --win-per-user-install

# 桌面快捷方式指向 dist\latest，始终打开最新版本
powershell -ExecutionPolicy Bypass -File packaging\make-shortcut.ps1
```

产物为 `dist\0.6.0\阅微阅读器\`（免安装）与 `dist\0.6.0\阅微阅读器-0.6.0.exe`（安装包）。桌面快捷方式指向 `dist\latest`（目录联接），每次出新版本后执行一次 `packaging\make-shortcut.ps1` 即可自动切换到最新版本。详细发布流程见 [docs/开发流程.md](docs/开发流程.md)。

发布到 GitHub Releases 时，把 exe 重命名为 `yuewei-reader-win64.exe` 再上传，README 的「下载最新版」链接（`releases/latest/download/yuewei-reader-win64.exe`）就会始终指向最新版本。

## 书源

书源格式与 Legado 3.0 对齐，可复用社区书源。进入「书源」页后点击「导入文件」选择 JSON，或「粘贴导入」直接贴入 JSON 文本；导入后可逐条校验，校验失败时调试面板会指出搜索、详情、目录或正文哪一段不匹配。

当前支持常见 CSS 选择器、索引与排除索引、相对链接补全、gzip 响应；不支持 JS 规则、POST 请求与登录态书源。站点改版可能导致旧书源失效，重新导入更新后的书源即可。

## 多端同步（WebDAV）

在「设置 → 同步（WebDAV）」填写地址、账号、密码与同步目录（默认 `yuewei`）并勾选启用。地址填 WebDAV 根路径，例如坚果云 `https://dav.jianguoyun.com/dav/`。

同步目录结构：

| 文件 | 内容 |
|---|---|
| `bookProgress\书名_作者.json` | Legado 兼容的阅读进度（章节、章内位置、更新时间）；删除的书写入归零进度作为墓碑 |
| `shelf.json` | 书架快照：书名、作者、书源、文件指纹、最近进度、最近阅读时间与删除标记 |

阅读排版设置只保存在本机，不写入同步目录。

同步时机：启动时拉取远端数据并反向合并——较新的阅读进度覆盖本地、书架按 `file_hash`/书源地址对齐并补上最近阅读时间与章节数（手机新增的在线书会按书源重新抓目录入架）；返回书架、切换章节与退出程序时上传。设置页「立即同步」可手动执行双向同步。

合并规则：进度按时间新者胜；书架按 `file_hash`/书源地址匹配，删除以墓碑（`deleted` + 删除时间）在两端传播，未在本机导入成功的远端条目原样保留；本地 TXT 若另一台设备没有对应文件，只跳过该书，不影响其他数据。

手机端互通：安装 Legado（开源阅读），把它的 WebDAV 地址、账号、目录设为与本应用一致，即可共用 `bookProgress` 里的进度。双方以「书名 + 作者」匹配同一本书；章内位置由页比例与章节字数换算，可能存在少量偏差。

## Android 客户端

`mobile/` 是 Android 版（Java + 原生 View，不依赖 AndroidX），已实现本地 TXT 导入、Legado 书源在线搜索与阅读、按章分页阅读、书架管理与 WebDAV 双向同步，与桌面端共用同一套 `bookProgress/{书名_作者}.json` 与 `shelf.json`。

同步默认自动进行：打开应用、退出阅读页、删除书籍后都会在后台同步一次（同一分钟内不重复触发），设置页可关闭「自动同步」；手动「同步」按钮保留。阅读排版设置只在本机生效，不参与同步。界面与桌面端统一纸白/夜间/护眼三套配色，书架为卡片式列表，正文使用衬线字体并实时应用字号、行距与主题。

阅读页默认全屏：上下工具栏为浮层并默认隐藏，轻点屏幕中间呼出/收起，左侧轻点或右滑上一页、右侧轻点或左滑下一页，音量键同样可翻页。隐藏工具栏时一并隐藏系统状态栏与导航栏，并按屏幕开孔与底部手势区给正文留白；分页只绘制本页的行，页尾不会露出下一行的半截文字。

在线书源直接复用桌面端的 `RuleEngine`（`mobile/core` 模块以源码方式引入 `domain`、`rule`、`Urls` 等纯 Java 代码），支持搜索、详情、目录与正文多页拼接；正文按章缓存，断网后已读章节仍可打开。

删除同步：书架长按删除只做墓碑标记（本地隐藏、保留记录），`shelf.json` 中该书写 `deleted: true`，同时写入一条归零进度，避免旧进度被拉回；另一端同步时隐藏该书并跳过其进度，已删的书不会被旧快照重新加回书架。所有改动均在设备下次同步时上传。

```powershell
cd mobile
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat :app:assembleDebug
```

产物为 `mobile\app\build\outputs\apk\debug\app-debug.apk`，可用 `adb install -r` 安装。`mobile\local.properties` 的 `sdk.dir` 指向本机 Android SDK，默认不入库；换机器时改成自己的 SDK 路径。

逻辑测试在 JVM 上运行（Robolectric，不需要模拟器），覆盖 TXT 导入去重与墓碑复活、书库持久化、同步推送/合并/删除墓碑、排版设置不参与同步、自动同步开关与间隔，以及用本地假站点跑通在线书源四段解析：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

当前范围：本地 TXT 与在线书源书架、全屏按页阅读（轻点左右翻页、滑动、音量键，轻点中间呼出工具栏）、自动同步与手动同步、WebDAV 进度与书架合并、删除墓碑同步。暂不含标点着色、自定义字体与书源调试面板；模拟器运行需要开启 Windows Hypervisor Platform，否则 x86 镜像会提示缺少硬件加速。

## 数据目录

默认位于 `%USERPROFILE%\.yuewei-reader`，可用 `-Dyuewei.dataDir=路径` 覆盖。

| 路径 | 内容 |
|---|---|
| `reader.db` | 书籍、章节、书源、下载任务、阅读进度与设置 |
| `cache\<bookId>\` | 章节正文缓存，本地导入与在线书都按章节落盘 |
| `fonts\` | 自定义字体，启动时自动加载；也可在设置页点「导入字体文件」 |

### 字体

正文默认使用「京华老宋体」，字体文件由使用者自行提供，本项目不分发字体。把 ttf / otf / ttc 文件放进 `fonts\`（或在设置页导入）后重启即可生效；未检测到该字体时正文回退宋体，设置页会给出提示。

删除整个目录即可清空所有数据。安装版与免安装版共用同一数据目录。

## 目录结构

```
src/main/java/com/yuewei/reader/
├─ application/     服务装配与业务服务（导入、书籍、搜索、下载、设置）
├─ domain/          书籍、章节、书源、进度等领域模型
├─ infrastructure/  数据库、网络、规则引擎、TXT 解析、缓存、WebDAV 同步
├─ ui/              JavaFX 界面（书架、搜索、书源、下载、设置、阅读器）
└─ ReaderApp.java   应用入口与窗口图标
src/main/resources/
├─ icon/            窗口与任务栏图标
└─ ui/style.css     界面样式
packaging/app.ico   安装包与快捷方式图标
docs/               PRD、开发流程、功能规格
```

## 已知限制

- 仅提供 Windows x64 安装包
- 暂不支持 JS 规则、POST 请求与需要登录的书源
- 不支持 EPUB、MOBI 等电子书格式
- 分页按章节预生成，暂不支持双栏排版与自定义段间距

## 开源协议

[MIT License](LICENSE)

## 免责声明

本软件不内置任何书源与书籍内容。书源由使用者自行导入，抓取内容的版权归原作者与站点所有，请遵守目标站点的使用条款，仅用于个人学习与阅读。
