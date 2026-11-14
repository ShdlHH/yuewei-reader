# 阅微 · 小说阅读器

一个面向 Windows 的桌面小说阅读器。支持导入本地 TXT，也兼容 Legado（开源阅读）3.0 书源，可在线搜索、阅读和下载。书籍、阅读进度与设置全部保存在本机。

![Java](https://img.shields.io/badge/Java-17-3f5c48)
![JavaFX](https://img.shields.io/badge/JavaFX-17-3f5c48)
![License](https://img.shields.io/badge/License-MIT-3f5c48)

## 功能

- 本地 TXT：自动识别编码、按章节标题分章、导入前预览，支持大文件导入
- 书架与阅读器：目录侧栏、上一章/下一章、阅读进度按章节与章内位置保存
- 在线书源：导入 Legado 3.0 书源，搜索、详情、目录、正文四段解析，支持规则校验与调试
- 下载与离线：整本下载、暂停/继续/取消、失败重试，已缓存章节断网可读
- 阅读排版：默认京华老宋体（缺失时回退宋体并提示），支持导入 ttf/otf/ttc 字体、12–32 号字、1.2–3.0 倍行距、段落首行缩进、滚轮缓动滚动、纸白/夜间/护眼三套主题，调整即时生效
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
jpackage --type app-image --name 阅微阅读器 --app-version 0.3.2 --vendor 阅微 `
  --input target\package-input --main-jar yuewei-reader.jar `
  --main-class com.yuewei.reader.Launcher --icon packaging\app.ico --dest dist\0.3.2

# exe 安装包
jpackage --type exe --name 阅微阅读器 --app-version 0.3.2 --vendor 阅微 `
  --input target\package-input --main-jar yuewei-reader.jar `
  --main-class com.yuewei.reader.Launcher --icon packaging\app.ico --dest dist\0.3.2 `
  --java-options "-Dfile.encoding=UTF-8" `
  --win-shortcut --win-menu --win-menu-group 阅微 --win-dir-chooser --win-per-user-install

# 桌面快捷方式指向 dist\latest，始终打开最新版本
powershell -ExecutionPolicy Bypass -File packaging\make-shortcut.ps1
```

产物为 `dist\0.3.2\阅微阅读器\`（免安装）与 `dist\0.3.2\阅微阅读器-0.3.2.exe`（安装包）。桌面快捷方式指向 `dist\latest`（目录联接），每次出新版本后执行一次 `packaging\make-shortcut.ps1` 即可自动切换到最新版本。详细发布流程见 [docs/开发流程.md](docs/开发流程.md)。

## 书源

书源格式与 Legado 3.0 对齐，可复用社区书源。进入「书源」页后点击「导入文件」选择 JSON，或「粘贴导入」直接贴入 JSON 文本；导入后可逐条校验，校验失败时调试面板会指出搜索、详情、目录或正文哪一段不匹配。

当前支持常见 CSS 选择器、索引与排除索引、相对链接补全、gzip 响应；不支持 JS 规则、POST 请求与登录态书源。站点改版可能导致旧书源失效，重新导入更新后的书源即可。

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
├─ infrastructure/  数据库、网络、规则引擎、TXT 解析、缓存
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
- 未做分页模式与自定义段间距

## 开源协议

[MIT License](LICENSE)

## 免责声明

本软件不内置任何书源与书籍内容。书源由使用者自行导入，抓取内容的版权归原作者与站点所有，请遵守目标站点的使用条款，仅用于个人学习与阅读。
