# Checkpoint 1 验证报告

日期：2026-10-08（Asia/Singapore）。

**状态：本机 Android 编译、JVM 单元测试、API 36 模拟器导航测试和 Debug APK 安装启动均通过。申请第一阶段正式验收，等待用户确认；未开始 Checkpoint 2。**

## 提交与验证范围

- 最初工程提交：`cb8988ae5abfb7a02ff95dbb6e07d883a9ee4a61`。此前因 SDK 缺失，编译与测试为 BLOCKED，不能视为通过。
- 本次测试源代码提交：[881279686337242fc5d300c661ed3b115c5d6671](https://github.com/cc483858939-ops/E-English/commit/881279686337242fc5d300c661ed3b115c5d6671)。测试在相同工作区源码上完成后提交；报告、日志及截图在后续文档提交中加入，未再改变应用或测试源码。
- 本阶段变更仅包括固定 Build Tools 35.0.0、忽略 D 盘本地 SDK/缓存目录、强化恢复与导航测试、更新验证文档。
- 三个页面仍是占位页。播放、练习和提交入口保持禁用，没有导入题目、标准答案、原文、翻译或音频，没有实现 Checkpoint 2 功能。

## 已配置的开发环境

| 项目 | 实际安装或验证结果 |
| --- | --- |
| Java | Oracle JDK 21.0.8，编译目标 Java 17 |
| Gradle / AGP / Kotlin | 8.13 / 8.11.1 / 2.2.21 |
| SDK Platform | android-36，revision 2，API 36 |
| SDK Build Tools | 35.0.0；app/build.gradle.kts 显式固定 |
| Platform Tools | 37.0.1，adb 可用 |
| Command-line Tools | 稳定版 23.0，Google 官方下载并校验；安装通过附带的 Android CLI 完成 |
| Android Emulator | 37.2.12；WHPX 检查返回 installed and usable |
| AVD | cp1-api36，API 36 AOSP default x86_64，1080×1920，density 420 |
| 实际启动 | 无窗口启动，1536 MiB RAM、2 CPU cores、SwiftShader；首次开机完成 38.340s |

按用户偏好，下载、安装及本次新增缓存都在 D 盘：

```text
D:\code\E-English\.local\downloads       官方命令行工具下载包
D:\code\E-English\.local\android-sdk     Platform、Build Tools、Platform Tools、模拟器和系统镜像
D:\code\E-English\.local\gradle          Gradle Wrapper 分发包和依赖缓存
D:\code\E-English\.local\android-user    Android CLI 与用户目录
D:\code\E-English\.local\avd             AVD 配置与数据
D:\code\E-English\.local\temp            本次命令的临时目录
```

`local.properties` 设置 `sdk.dir=D:/code/E-English/.local/android-sdk`；上述本机目录和配置均不提交 Git。原 C 盘已有 Gradle 缓存仅复制复用，没有删除或移动原文件。工具仍可能读取系统用户目录下的既有设置；未向 C 盘安装新的 SDK 或系统镜像。

安装方法参考 [Google 官方 SDK 工具说明](https://developer.android.com/tools/sdkmanager)。本机已具备可执行的 SDK 和模拟器环境；本次没有配置或运行 GitHub Actions，结果均来自本机实际执行。

## 实际命令与结果

Windows 使用等价的 `gradlew.bat` 命令。Gradle 属性包含点时使用引号，避免 PowerShell 拆分参数。

```powershell
$env:GRADLE_USER_HOME = "$PWD/.local/gradle"
$env:ANDROID_HOME = "$PWD/.local/android-sdk"
$env:ANDROID_USER_HOME = "$PWD/.local/android-user"
$env:ANDROID_AVD_HOME = "$PWD/.local/avd"
$env:TEMP = "$PWD/.local/temp"
$env:TMP = $env:TEMP

.\gradlew.bat testDebugUnitTest --no-daemon --console=plain --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx1536m'
.\gradlew.bat assembleDebug --no-daemon --console=plain --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx1536m'
.\gradlew.bat assembleDebugAndroidTest --no-daemon --console=plain --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx1536m'
.\gradlew.bat connectedDebugAndroidTest --no-daemon --console=plain --max-workers=1 '-Pandroid.injected.device.serial=emulator-5570' '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx1024m'
```

| 检查 | 真实结果 |
| --- | --- |
| testDebugUnitTest | PASS，BUILD SUCCESSFUL in 3m 23s；3 tests，0 failures、0 errors、0 skipped |
| assembleDebug | PASS，BUILD SUCCESSFUL in 1m 12s；真实生成 Debug APK |
| assembleDebugAndroidTest | PASS，BUILD SUCCESSFUL in 48s；导航测试 APK 可编译 |
| connectedDebugAndroidTest | PASS，BUILD SUCCESSFUL in 1m 38s；API 36 上 5 tests，0 failures、0 errors、0 skipped |
| adb install | PASS，返回 Success |
| am start -W | PASS，Status: ok，LaunchState: COLD；两次启动 1296ms / 1123ms |
| 页面截图和布局检查 | PASS，实际查看列表、答题、原文三个页面 |
| APK 签名 | PASS，apksigner verify；v2 signature verified |
| APK 元数据 | minSdk 26、targetSdk 36、compileSdk 36；四种 ABI；无 INTERNET 权限 |

最初一次补充测试命令因 PowerShell 将未加引号的 Gradle 属性拆分，返回 `Task '.compiler.execution.strategy=in-process' not found`。已修正引号后重新执行成功；失败日志保留在本机 `artifacts/checkpoint-1-validation/testDebugUnitTest-attempt1.log`。本次没有发现需要修改业务源码的编译或测试失败。

成功执行的完整日志和从 JUnit XML 生成的结果摘要已随报告保存：

- [JVM 测试日志](evidence/unit-tests.log)
- [Debug APK 构建日志](evidence/assemble-debug.log)
- [模拟器测试日志](evidence/connected-tests.log)
- [结果与源码 SHA、APK SHA-256](evidence/results.json)

安装、AVD 创建、启动、失败尝试及测试 APK 构建的完整本机日志在 `artifacts/checkpoint-1-validation/`；Gradle 的 JUnit XML 和 HTML 报告仍位于 `app/build/`。

## 测试有效性

JVM 共 3 项：默认英文、非法保存模式回退、从独立 SavedStateHandle 快照恢复显示模式。旧测试仅把同一个 Handle 传给新 ViewModel，本次改为重建独立 Handle，并确认原 ViewModel 后续修改不会改变已恢复的状态。

API 36 模拟器实际运行以下 5 项，没有用静态检查或 JVM 测试替代导航测试：

1. 未导入时只有目标 Part 卡片，开始练习禁用。
2. 三个页面通过按钮往返，原文模式保持，未选择的模式确实未选中。
3. 系统返回键按原文→答题→列表返回。
4. ActivityScenario.recreate 后恢复原文目的地和中文模式，返回答题再进入仍保留模式。
5. 答题页与原文页的播放禁用，提交禁用，显示资源待导入状态。

导航到可滚动容器里的按钮前先滚动到目标，避免仅在恰好可见的屏幕尺寸下有效。Activity 重建测试验证 SavedStateHandle 与 Navigation 的实际生命周期接入；它不等于操作系统杀死进程后的恢复测试。

## Debug APK 与页面证据

- 构建文件：`app/build/outputs/apk/debug/app-debug.apk`。
- 本次验收副本：`artifacts/checkpoint-1-validation/listening-practice-checkpoint1-debug.apk`。
- 包名：`com.eenglish.listening`，版本 `0.1.0-checkpoint1`，13,870,211 字节（约 13.2 MiB）。
- SHA-256：`d4da2810ff95e260c72bdcfb91ddccad577fe80a5426469a679a9d8f5900f04d`。
- APK 没有版权题库资源；源码仓库仅保存报告与截图，本机 SDK、下载包及 APK 副本均被 Git 忽略。

真实模拟器截图：[试题列表](screenshots/list.png)、[答题占位页](screenshots/practice.png)、[原文占位页](screenshots/transcript.png)。截图均在页面内容出现后读取布局并捕获，不是启动闪屏。

## 警告与仍未验证的项目

非阻塞警告：SDK XML v4 对 AGP 的 SDK 读取工具较新；Debug APK 的 `libandroidx.graphics.path.so` 未剥离符号。完整日志保留警告，实际编译、安装启动和测试均通过。

尚未验证：API 26 上的实际运行、物理真机、其他屏幕尺寸和大字体、操作系统杀进程后的状态恢复、GitHub CI。

尚未实现：题库转换、真实题目、评分、幂等提交、Room 练习记录、播放器和共享播放状态、重启恢复答案、实际飞行模式练习。这些属于后续检查点，不纳入本阶段通过声明。

等待用户正式验收 Checkpoint 1；未经确认不进入 Checkpoint 2。
