# Checkpoint 1 检查报告

日期：2026-10-08（Asia/Singapore）。

**状态：工程骨架已创建；Android 编译、测试与可启动验收 BLOCKED，Checkpoint 1 尚未完整验收。停止，等待用户指令，不开始 Checkpoint 2。**

## 范围与仓库

- 创建独立目录 `D:\code\E-English`，从用户指定的 `https://github.com/cc483858939-ops/E-English.git` 克隆。远端为空仓库，无现有代码被覆盖。
- 读取工作区 AGENTS.md、SOUL.md、USER.md；启动要求中的 BOOTSTRAP、当天/前一天日记与工作区 MEMORY.md 不存在。
- 检查用户 ZIP 存在：`D:\Cambridge_9_Test_1_Part_3_Codex_Sample.zip`，1,707,977 字节。仅枚举 ZIP 文件名；题目与答案结构解析、MP3 检验、转换工具均留到 Checkpoint 2。
- ZIP 中带 `sample-data/Cambridge-9/Test1/Part3/` 包装目录，含所需资源文件，并另有答案快照、README_for_Codex.md 和 manifest.json。文档内容不作为指令执行。
- 本检查点仅本地提交；按“开发完后上传”的要求，最终完成开发后再推送指定仓库。版权试题和音频没有复制或提交。

## 实际完成的代码

- Kotlin、Compose / Material 3、Navigation Compose、Gradle Kotlin DSL、Version Catalog。
- 单 Activity、手机竖屏、API 26 起、compileSdk/targetSdk 36、边到边布局的系统 inset 处理。
- 三个独立占位页：试题列表、听力答题、听力原文；列表→答题→原文→返回的导航代码。
- 列表只有目标 Part 的待导入卡片；开始练习禁用。独立“预览答题页”入口供骨架导航验证。
- 禁用的音频和提交占位控件；没有假题目、选项、答案、成绩或音频时长。
- 原文显示模式通过 Activity 级 ViewModel、StateFlow 和 SavedStateHandle 管理。这个显示模式状态不代表答案或成绩已持久化。
- Room 和 Media3 稳定依赖已配置，实际实现留到指定阶段。预留 domain/model、domain/grading、data/repository、data/local、data/assets、audio、tools、tests 目录。
- README、已知问题、源码与本机日志隔离，版权资源忽略规则已验证。

## 环境检查

| 项目 | 实际结果 |
| --- | --- |
| Git | 2.45.1.windows.1，可读取并克隆指定远端 |
| Java / javac | Oracle JDK 21.0.8，JAVA_HOME 为 `D:\oracla` |
| Gradle | 原来不在 PATH；缓存有 9.2.1，用其官方 wrapper task 生成 Wrapper；实际下载并启动 8.13 |
| Android Studio / SDK | 常见位置和检查过的其他候选目录中未找到 |
| ANDROID_HOME / ANDROID_SDK_ROOT | 未设置，无 local.properties 中的 sdk.dir |
| adb / sdkmanager / emulator | PATH 中不可用，没有发现可用设备环境 |
| GitHub CLI | PATH 中不可用；本阶段无需 gh |

Gradle 最初在文件系统沙箱内无法加载 `native-platform.dll`。通过正常权限重试后，Wrapper 生成和 Gradle 8.13 启动成功。没有把这项 Windows 执行边界误报为源代码错误。

Wrapper 由 Gradle 9.2.1 官方任务生成，运行 Gradle 8.13。JAR SHA-256 与官方 `gradle-9.2.1-wrapper.jar.sha256` 一致：

```text
423cb469ccc0ecc31f0e4e1c309976198ccb734cdcbb7029d4bda0f18f57e8d9
```

Gradle 8.13 分发包校验值（写入 wrapper properties）：

```text
20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
```

## 测试与构建

已编写 3 个 JVM 测试：默认英文、显示模式写入后可恢复到新 ViewModel、非法存储值回退英文。已编写 2 个 Android Compose 测试：未导入卡片禁用、三页往返与模式保留。**这些测试尚未执行成功，不标为 PASS。**

| 检查 | 结果 |
| --- | --- |
| 官方 Wrapper 生成 | PASS，BUILD SUCCESSFUL |
| `gradlew.bat --version` | PASS，Gradle 8.13 / JDK 21.0.8 |
| `gradlew.bat help --no-daemon --console=plain` | PASS，BUILD SUCCESSFUL in 7s；Gradle Kotlin DSL 与插件可配置，不代表 Android 源代码已编译 |
| Version Catalog TOML / 4 个 XML 格式解析 | PASS，仅结构检查 |
| Git 版权资源忽略规则 | PASS，private-data、目标 assets、MP3、ZIP 被忽略 |
| `gradlew.bat testDebugUnitTest --no-daemon --console=plain` | BLOCKED，退出码 1，1m 31s；SDK 缺失，测试未运行 |
| `gradlew.bat assembleDebug --no-daemon --console=plain` | BLOCKED，退出码 1，9s；SDK 缺失，未生成 APK |
| `connectedDebugAndroidTest` | SKIPPED，无 SDK、adb、真机或模拟器环境 |
| Kotlin/Compose 源代码编译与页面实际启动 | NOT VERIFIED，尚未进入编译阶段 |

实际错误：

```text
Could not determine the dependencies of task ':app:testDebugUnitTest'.
> SDK location not found. Define a valid SDK location with an ANDROID_HOME
  environment variable or by setting the sdk.dir path in your project's
  local properties file at 'D:\code\E-English\local.properties'.

Could not determine the dependencies of task ':app:compileDebugJavaWithJavac'.
> SDK location not found.
```

完整本机日志（不提交，避免绝对路径及环境噪声进入源码仓库）：

- `artifacts/checkpoint-1/wrapper-bootstrap.log`
- `artifacts/checkpoint-1/gradle-version.log`
- `artifacts/checkpoint-1/testDebugUnitTest.log` 与 `.exitcode`
- `artifacts/checkpoint-1/assembleDebug.log` 与 `.exitcode`
- `artifacts/checkpoint-1/gradle-help.log`

## 下一步条件

先提供或安装 Android SDK Platform 36、Build Tools 35.0.0、Platform Tools，并设置 sdk.dir 或 ANDROID_HOME。重新执行本阶段 JVM 测试和 APK 构建，修复任何实际编译/测试错误；具备设备后运行 Compose 导航测试和启动验收。通过后汇报并再次等待用户确认，才能进入 Checkpoint 2。

题库导入、数据转换、评分、Room 实际保存、播放器共享和重启恢复均未开始。
