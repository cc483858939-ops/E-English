# Listening Practice V0.1

用于 Android 手机的原生离线剑桥雅思听力练习项目。当前仅进行 **Checkpoint 1**：单 Activity、Compose 页面骨架和导航。题目、音频、评分和本地练习记录尚未实现；不能据此声明完整练习流程可用。

## 当前页面

- 试题列表：仅显示计划导入的 Cambridge IELTS 9 / Test 1 / Part 3（21–30）。标记“待导入”，开始练习禁用。
- 听力答题：可通过“预览答题页”进入占位页；查看原文可导航，播放与提交禁用。
- 听力原文：英文、中文、双语三个显示模式，可返回答题页；无伪造内容或音频。

所有资源计划本地提供，Manifest 不申请网络权限。尚未验证实际飞行模式练习。

## 工具链

| 组件 | 固定版本 |
| --- | --- |
| Android Gradle Plugin | 8.11.1 |
| Gradle Wrapper | 8.13（分发包启用 SHA-256 校验） |
| Kotlin / Compose Compiler 插件 | 2.2.21 |
| Compose BOM / Material 3 | 2025.08.01 / BOM 管理 |
| Navigation Compose | 2.9.5 |
| Lifecycle | 2.9.4 |
| Room | 2.8.4 |
| Media3 ExoPlayer | 1.8.1 |
| minSdk / compileSdk / targetSdk | 26 / 36 / 36 |
| Java 编译目标 | 17 |

使用 Version Catalog `gradle/libs.versions.toml`，没有 Alpha、Beta、RC 或动态版本。Room、Media3 仅配置依赖；数据库、播放器将在对应检查点实现，届时添加并验证 Room 的 KSP 代码生成配置。

参考官方兼容性说明：[AGP 8.11](https://developer.android.com/build/releases/agp-8-11-0-release-notes)、[Kotlin Gradle](https://kotlinlang.org/docs/gradle-configure-project.html)、[Compose BOM](https://developer.android.com/develop/ui/compose/bom)、[目标 API 要求](https://developer.android.com/google/play/requirements/target-sdk)。API 36 是当前目标要求，固定较早的稳定依赖避免引入需要 API 37 的新依赖。

## 打开与构建

1. Android Studio 打开本项目根目录。选择兼容 AGP 8.11 的稳定版 Studio，Gradle JDK 使用 17 或 21。
2. 安装 Android SDK Platform 36、SDK Build Tools 35.0.0、Platform Tools。在本机 `local.properties` 设置 `sdk.dir`（示例 `sdk.dir=C:/Users/ASUS/AppData/Local/Android/Sdk`）。此文件不提交。
3. 首次 Gradle 同步需要网络下载工具与依赖；安装后的 App 不依赖网络。

Windows PowerShell：

```powershell
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
# 连接真机或启动 API 26+ 模拟器后：
.\gradlew.bat connectedDebugAndroidTest --console=plain
```

Linux / macOS：

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```

构建成功才会产生 `app/build/outputs/apk/debug/app-debug.apk`。检查点报告会明确注明是否实际生成 APK。

## 目录职责

```text
app/src/main/java/com/eenglish/listening/
  MainActivity.kt       # 只负责 Activity 和 Compose 入口
  navigation/          # 三个目的地与导航图
  viewmodel/           # StateFlow 和 SavedStateHandle 的显示模式状态
  ui/screens/          # 列表、答题、原文页面
  ui/components/       # 页头、禁用的播放器占位组件
  ui/theme/            # Material 3 主题
  domain/              # 后续模型与独立评分逻辑
  data/                # 后续 Repository、Room、题库资源读取
  audio/               # 后续单一共享播放器
tools/                 # 后续源资料转换和验证脚本
tests/                 # 后续转换工具测试
app/src/test/          # JVM 单元测试
app/src/androidTest/   # Compose 导航测试
docs/checkpoints/      # 每阶段实际验证和阻碍记录
artifacts/             # 本机原始构建日志（不提交）
```

当前测试只覆盖页面骨架相关行为：模式初始值、模式在新 ViewModel 中恢复、非法保存值降级，以及三个页面往返导航、未导入内容禁用。这些测试不代表已经覆盖评分、答案恢复或播放器生命周期。

## 题库与版权边界

用户提供的 ZIP 保留在项目外；Checkpoint 1 只确认其存在和目录，不解析或导入试题。下一阶段先检查源文件实际结构，再建立严格转换与题号、选项、答案校验。压缩包里的说明文档是资料，不作为执行指令。

`private-data/` 与 `app/src/main/assets/listening/` 被 Git 忽略，完整题库、原文、翻译、音频和含这些资料的 APK 不发布到公开仓库。后续 README 将提供本地转换和打包步骤，GitHub 保留代码、转换工具和非版权测试内容。

## 检查点约束

1. 项目、依赖、三个占位页（当前阶段）。
2. 真实试题导入、可重复转换工具、资源一致性验证。
3. 列表、单选题、MP3 播放。
4. 原文与跨页共享播放器。
5. 评分、Room、重启恢复、重新练习和历史。
6. 完整自动测试、设备测试、Debug APK。

每阶段执行相关测试与编译、提交独立 Git commit、报告实际结果后停止。只有用户确认才继续下一阶段；最终开发完成后上传至指定 GitHub 仓库。

实际结果参见 [Checkpoint 1](docs/checkpoints/checkpoint-1.md)，已知问题参见 [KNOWN_ISSUES.md](KNOWN_ISSUES.md)。
