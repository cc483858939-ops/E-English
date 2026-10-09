# Listening Practice V0.1

原生 Android 离线听力练习 App。支持 Cambridge IELTS 5–21 按册本地资料包导入、册数 → Test → Part 列表、按需读取、单选 / 多选 / 匹配 / 填写与图片题，以及共享 MP3 播放、精听、永久高亮、Room 保存和历史记录。原有 Cambridge IELTS 9 / Test 1 / Part 3 样本继续兼容。

公开仓库不包含版权题目、答案资源、原文、翻译、音频或包含这些资源的 APK。未导入本地资源时，应用显示“暂无已导入试题”，不会添加假试题。暂不发布安装包或创建 Release；当前 Debug 构建只用于开发验证。

## 本地导入

完整资料先在电脑转换，生成每册独立的 `.eelpack`，复制到手机后通过列表中的“导入本地资料包”选择文件。校验并复制到应用内部存储后，练习无需联网。不会把约 1 GB 音频嵌入 APK。

```powershell
python -m pip install -r tools/requirements-listening.txt
# Windows 建议沿用 D 盘临时目录；不要修改系统全局环境变量。
$env:TEMP='D:/code/E-English/.local/temp'
$env:TMP=$env:TEMP
python tools/import_library.py 'D:/IELTS-Listening-Cambridge-5-13_已修复.zip' 'D:/IELTS-Listening-Cambridge-13-17.zip' 'D:/IELTS-Listening-Cambridge-18-21.zip' --audit-only
python tools/import_library.py 'D:/IELTS-Listening-Cambridge-5-13_已修复.zip' 'D:/IELTS-Listening-Cambridge-13-17.zip' 'D:/IELTS-Listening-Cambridge-18-21.zip' --output private-data/library/rechecked-batch --report private-data/library/recheck-audit.json
```

真实扫描范围为 17 册、272 个不同 Part。六个遗留 Part 已定点恢复，当前实际可用 **272 个 Part、2720 道题、68 个完整 Test**。原有 266 个 Part 的 JSON、索引条目、音频和图片字节保持不变。修复依据、测试及本地新包位置见 [六个 Part 恢复报告](docs/pending-part-recovery.md)；[原资源清单](docs/library-source-audit.md) 和 [原导入验证说明](docs/library-import.md) 保留此前 266 个 Part 的历史验证结果。版权资源、原始来源记录和私有修正均在忽略目录。

上面的转换命令不带私有地图修正，仍用于复现原始资料的严格解析结果。当前完整的 17 个包在 `private-data/library/pending-six/updated-library/`；已有题库只需重新导入其中的 Cambridge 6、9、11。定点转换支持重复指定 `--part`，可靠来源修正通过 `--corrections` 传入原始资源哈希及证据，不会改写 ZIP。使用更新包需要包含双空填写支持的当前应用代码。

二次复查工具：先执行转换命令，再运行 `python tools/recheck_library.py`，逐资源核对原 ZIP、资料包和解包数据。开发模拟器可在构建 Debug 与 AndroidTest 后运行 `python tools/install_local_library.py --device emulator-5570`；它先验证完整资料包和全部音频，再使用现有导入器安装，不清空练习数据库。资料包单独推送，不加入 APK。

手机导入器拒绝损坏、路径越界、资源缺失或覆盖冲突的资料包，不修改 Room 记录。相同资料包重复导入幂等；同册扩展包必须保留已有 Part 的相同内容。更改旧 Part 的正文或答案会明确拒绝，防止高亮错位和资源被覆盖。目录使用不可变版本与原子索引切换，旧版本目录暂时保留用于安全更新。

### 原始单样本工具

保留原始 ZIP，不修改它。压缩包中的说明文档按资料处理，不作为执行指令。

```powershell
python tools/import_sample.py D:/Cambridge_9_Test_1_Part_3_Codex_Sample.zip
$env:LISTENING_SAMPLE_ZIP='D:/Cambridge_9_Test_1_Part_3_Codex_Sample.zip'
python -m unittest discover -s tools -v
```

也可以传入已解压的 Part3 目录。输出到忽略的 `app/src/main/assets/listening/`，之后重新构建即可离线使用。转换前检查实际源结构，严格验证题号、选项、标准答案与多个来源的一致性；歧义直接报错。详细说明见 [数据格式](docs/data-format.md)。

标准答案仅在提交后展示；本地资源不提供防提取保护。源文件的解析是登录提示，没有解析正文，因此没有编造解析。原文翻译保留源文本；时间轴与音频可能存在差异，不做精确同步或自动滚动高亮。手动永久高亮保持现有实现。

## 使用行为

- 列表显示实际导入试题及未开始 / 进行中 / 已完成状态。
- “导入本地资料包”使用系统多选文件选择器，可一次选择一个或多个 `.eelpack`。使用 `*/*` 保持自定义扩展名可见，格式由包内容及原有 SHA-256 校验决定。
- 批量导入在一个后台队列中逐册执行，只打开当前文件的流；显示文件名、真实阶段、已处理数量及逐文件结果。相同资料显示“已存在”，冲突拒绝覆盖，失败后继续其他文件。新增/已存在 Part 按实际索引计算，完成后列表立即刷新。
- 每册沿用独立原子提交，不改动 Room。进程终止会中止尚未完成的队列，已发布的册数仍保留；重新选择资料包可安全重试。
- 单选答案可修改；仅在 Room 提交写入成功后更新已保存状态。同题只有一个答案。
- 多选使用同组复选框，选择集合原子保存到原始题号对应的记录，顺序无关且不重复计分；填空和简答支持源数据明确列出的等价答案、大小写与空白规范化，以及单词/数字数量限制。表格、笔记、流程图和地图题复用输入或选项组件与本地图片，可放大查看。
- 本地 MP3 支持播放、暂停、继续、拖动及时间显示，系统管理音量与音频焦点。
- 答题页和原文页共用同一播放器；英文、中文、双语可切换，原文可滚动。
- 精听页答题卡的灰色横条支持连续拖动高度，不吸附固定档位；收起再展开恢复之前高度，原文与题目各自滚动。验证见 [可拖拽面板报告](docs/checkpoints/resizable-panel.md)。
- 原文、翻译、双语段落、题干和选项支持系统文字选区、准确复制、黄色永久高亮及部分取消。标记按 Part 保存，重新练习不会清空。英文/中文模式可以跨段选择；双语模式按单个语言段选择，同一段的高亮跨模式共享。验证及数据库迁移见 [文字标注报告](docs/checkpoints/annotations.md)。
- 进入后台暂停，回到前台手动继续；页面切换保持进度，播放器随 ViewModel 释放。
- 漏答提交需要确认；未答计错。提交事务幂等，已提交尝试只读。
- 重新练习创建新尝试，保存旧成绩；历史记录可查看。若从旧成绩页返回已有草稿，按钮明确显示“继续未完成练习”。
- 答案与成绩保存在 Room；结束进程后重新打开可恢复。历史保存题目快照，题库更新不会改写旧成绩。

## 工具链与构建

| 组件 | 固定版本 |
| --- | --- |
| AGP / Gradle | 8.11.1 / 8.13 |
| Kotlin / Compose 插件 | 2.2.21 |
| Compose BOM / Navigation | 2025.08.01 / 2.9.5 |
| Lifecycle / Coroutines | 2.9.4 / 1.10.2 |
| Room / KSP | 2.8.4 / 2.2.21-2.0.4 |
| Serialization / Media3 | 1.9.0 / 1.8.1 |
| minSdk / compileSdk / targetSdk | 26 / 36 / 36 |
| Build Tools / Java 编译目标 | 35.0.0 / 17 |

依赖由 Version Catalog 管理，无 Alpha 或动态版本。配置参考官方 [Room](https://developer.android.com/jetpack/androidx/releases/room#2.8.4)、[KSP 发布](https://github.com/google/ksp/releases/tag/2.2.21-2.0.4)、[Serialization 发布](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.9.0) 与 [Media3](https://developer.android.com/media/media3/exoplayer/hello-world) 文档，并通过本地实际编译验证。

Android Studio 打开项目根目录，Gradle JDK 使用 17 或 21，安装 Platform 36、Build Tools 35.0.0、Platform Tools。用不提交的 `local.properties` 配置 SDK。首次构建需要下载依赖，安装后的应用不需要服务器或网络。

本机沿用现有 D 盘工具：`D:\code\E-English\.local\android-sdk`，下载、Gradle 缓存、AVD 和临时文件也在 `.local/`。用户后续消息中的 `E-English.local` 路径不存在，因此没有重复下载 SDK。

```powershell
# 本机辅助脚本：使用现有 D 盘缓存并限制 Gradle 内存。
powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify.ps1
# 设备测试；只在可丢弃的模拟器运行，Gradle 可能在测试结束后卸载应用。
powershell -NoProfile -ExecutionPolicy Bypass -Command "& ./tools/verify.ps1 -Tasks connectedDebugAndroidTest -Device emulator-5570 -Log artifacts/connected.log"
```

脚本的 ExecutionPolicy 只影响该进程，不更改系统策略。也可直接运行：

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```

Windows 使用 `gradlew.bat`，设置 `ANDROID_HOME` / `GRADLE_USER_HOME` 或 `local.properties`。Debug 输出在忽略的 `app/build/outputs/apk/debug/`，不上传 GitHub。

## 测试与验证边界

自动测试包括严格导入、真实私有样本一致性、独立 JVM 评分、Room 文件重开、并发提交/创建、答题导航、Activity 重建、原文、实际音频播放与进度、历史及重新练习。UI 测试使用专用数据库与资料目录，数据层测试使用独立文件，不清空用户的 `practice.db`。

题库扩展新增 Python 严格解析、重复/冲突、确定性打包与 ZIP 路径测试；JVM 验证 24 个代表性 Part、原有 266 个 Part 和恢复的 6 个 Part。设备测试覆盖按册导入、资源校验、三级列表、题型、增量扩展及原有记录/高亮保护。真实资料测试仅使用忽略目录中的本地资源；公开 checkout 缺少私有资料的测试明确 SKIPPED。最近一次定点验证结果见 [恢复报告](docs/pending-part-recovery.md)。

未导入私有资源的公开 checkout 中，实际样本测试明确 SKIPPED：Python 需要 `LISTENING_SAMPLE_ZIP`，JVM 需要转换后的本地资源，实际题库 UI 测试同样需要本地导入。不能把跳过描述为真实练习测试通过。

独立进程恢复验证（仅本任务可丢弃模拟器，操作会创建测试尝试）：

```powershell
python tools/device_smoke.py --adb .local/android-sdk/platform-tools/adb.exe --serial emulator-5570 --allow-disposable-emulator
```

其 UI 转储、数据库副本和截图包含版权内容，全部保留在忽略的 `artifacts/`。只提交不含题目内容的测试汇总及构建日志。

标注的真实进程恢复测试使用合成文本和专用数据库。先在可丢弃模拟器安装本地 debug 与 androidTest 构建，再执行：

```powershell
python tools/annotation_process_smoke.py --adb .local/android-sdk/platform-tools/adb.exe --serial emulator-5570 --allow-disposable-emulator
```

此测试通过原生选择菜单保存高亮，结束进程后以新的测试进程读取并检查黄色标记，不清空用户的 `practice.db`。

最终实际结果见 [集成验收报告](docs/checkpoints/integration-report.md)，各阶段见 [CP2](docs/checkpoints/checkpoint-2.md)、[CP3](docs/checkpoints/checkpoint-3.md)、[CP4](docs/checkpoints/checkpoint-4.md)、[CP5](docs/checkpoints/checkpoint-5.md)。[CP1](docs/checkpoints/checkpoint-1.md) 是已验收的历史记录，不代表当前功能状态。已知限制见 [KNOWN_ISSUES.md](KNOWN_ISSUES.md)。

## 结构

`MainActivity` 负责入口和后台暂停；`navigation` 负责导航；`viewmodel` 负责 StateFlow、显示偏好和串行保存命令；`ui/screens` / `ui/components` 负责原生页面；`domain/model` / `domain/grading` 负责模型及独立评分；`data/assets` / `data/repository` / `data/local` 负责资源校验、事务及 Room；`audio` 负责单一播放器；`tools` 提供转换和验证工具；`app/src/test` / `app/src/androidTest` 提供 JVM 和设备测试。

每阶段保留独立 commit。按用户最新授权，阶段之间不再等待人工确认；完成核心开发与集成验证后停止，等待代码审查和后续最终 APK 打包指令。
