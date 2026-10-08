# Listening Practice V0.1 — 核心功能集成验收报告

日期：2026-10-08。Checkpoint 1 已由用户正式验收；后续按照连续开发授权完成
CP2、CP3、CP4、CP5 和整体测试。未创建 Release，未上传或交付最终安装 APK。

经过验证的实现与测试代码提交：
`282617a52a3481b030b24dcba51cf63ea3e8c22a`。
之后的报告提交只更新文档与脱敏验证证据；最终远端 HEAD 在交付回复中提供。

## 分阶段提交

| 阶段 | 内容 | Git commit |
| --- | --- | --- |
| CP1 | 已验收的工程、导航及本地构建验证 | `f471389b78db63e29cedc7dd52dafc1dfca4dcd6` |
| CP2 | 实际源结构校验、严格转换、领域模型与资源 Repository | `6e5a6d411c0ee7758353c878fe6134adba2adaee` |
| CP3 | 真实试题列表、单选、滚动、本地 MP3 播放 | `e0baade9b28fd0cc0d0220100ede8a7eb5b2916d` |
| CP4 | 英文/中文/双语原文、共享播放器及后台暂停 | `52c5557cdf1298badb0c905d59a61390a691c013` |
| CP5 | Room 保存、事务批改、幂等提交、重启恢复与历史 | `86e807df07c9c1ac3b95367025c7e8d7e341ee21` |
| 集成补充 | HTML 交叉校验、独立进程恢复工具、版权检查、历史草稿保护测试 | `282617a52a3481b030b24dcba51cf63ea3e8c22a` |

每阶段均完成相关编译与测试后提交。各阶段当时的功能边界与结果保留在
checkpoint-2/3/4/5.md，不用最终结果倒填早期未完成的功能。

## 实际执行结果

Windows 本机通过 `gradlew.bat` 执行与用户要求的 `./gradlew` 对应任务。
SDK Platform 36、Build Tools 35.0.0、Platform Tools 37.0.1，以及 Gradle 缓存、
临时文件、下载与 AVD 均沿用 D 盘 `.local/`，未使用 CI 代替本地测试。

| 执行 | 实际结果 | 公开证据 |
| --- | --- | --- |
| `python -m unittest discover -s tools -v`，提供真实 ZIP | 9 passed，0 skipped | evidence/final-import-tests.log |
| `testDebugUnitTest`，强制重跑 | 12 passed，0 skipped/error/failure | evidence/final-build.log、final-results.json |
| `assembleDebug`，强制重跑 | BUILD SUCCESSFUL | evidence/final-build.log |
| `connectedDebugAndroidTest` | 15 passed，0 skipped/error/failure | evidence/final-offline-connected.log |
| `lintDebug` | 成功，0 error、41 warning | evidence/final-lint.log、final-results.json |
| 正常安装/冷启动、实际 force-stop/relaunch | 答案、已提交成绩、历史和新尝试均通过 | evidence/final-device-smoke.log、final-results.json |
| `python tools/check_public_tree.py` | 无私有资源或真题文字进入 Git 索引 | evidence/public-tree-check.log |

最终强制构建/评分测试执行用时 1m24s；最后一次完整设备测试执行用时 1m32s。
测试结果从 JUnit XML 提取，没有用 Gradle 的 SKIPPED 配置任务冒充测试通过。
最终设备测试包含补充的“旧成绩页返回已有草稿”，没有创建多余尝试或覆盖历史。

## 集成检查

实际使用 API 36 default x86_64 模拟器、WHPX。常规运行使用 1080×1920 / 420dpi；
完整离线套件使用 720×1280 / 320dpi（360×640dp）与 1.3 倍字体。
设备飞行模式开启，Wi-Fi 与移动数据均关闭，`Active default network: none`。
网络和显示设置在测试结束后恢复，任务模拟器关闭。

| 用户要求 | 实际验证 |
| --- | --- |
| App 可以打开 | 本地安装成功；实际冷启动 Status ok；测试 Activity 正常启动 |
| 真实试题加载、全部 10 题显示 | 本地 JSON/MP3 校验；21–30 每题滚动定位 |
| MP3 播放、暂停、继续、拖动 | Media3 实际准备/解码，时长 >420s，进度推进，暂停和 seek 行为断言 |
| 页面切换保持进度 | seek 至 60s，播放中五次往返，进度持续且 controller 身份一致 |
| 英文、中文、双语 | 与导入文本逐项匹配，切换及末段滚动通过 |
| 准确评分与漏答 | JVM 和实际 UI 的 10/10、8/10、1/10；漏答确认/取消；未答计错 |
| 结束 App 后答案恢复 | 实际 force-stop 后冷启动，真实单选勾选恢复；读取独立 DB/WAL 副本确认 |
| 成绩与历史保存 | 再次结束进程，已提交记录与时间/分数保持；新尝试 ID 不同且旧记录不变 |
| 幂等提交、不可篡改 | 8 个并发事务只产生一个成绩；提交后写入拒绝；UI 单选禁用 |
| 多次切换及生命周期 | 五次往返、Activity 重建、系统返回、后台暂停、手动继续均通过 |
| 飞行模式使用 | 全部 15 项设备测试及最终进程恢复检查在无活动网络时完成 |
| 手机屏幕适配 | 较小屏幕与 1.3 倍字体通过相同设备套件 |
| Gradle 编译和自动测试 | 9 Python、12 JVM、15 Android 测试全通过，构建成功 |

模拟器主机音频输出禁用，所以“MP3 播放通过”指实际播放器解码和进度/控制验证。
真实扬声器或耳机听感、真机和 API 26 运行均 **NOT VERIFIED**。最低 SDK 26
已在配置/Manifest/依赖编译中验证，不等同于在 Android 8 真机执行。
App 彻底关闭后恢复答题记录；不承诺恢复播放位置。页面间共享进度已经实际验证。

## 发现的问题与修复

- CP2 Gradle Kotlin DSL 的单元测试 task receiver 写法错误，修正后重新编译通过。
- Windows PowerShell 将 SDK stderr 警告按错误流处理，验证脚本改为保留原始
  Gradle 退出码；只为脚本进程绕过执行策略，不修改系统策略。
- 独立进程测试最初复用了旧 SQLite 副本/WAL 索引，读取到旧状态。改为每次
  独立副本、明确关闭连接后重跑，三个恢复检查全部通过。没有降低断言标准。
- 额外 lint 发现本机 `local.properties` 的盘符未转义。修复后旧诊断仍被缓存，
  强制重跑 lint 与编译/单元测试，最终成功。未禁用 lint、删测试或创建 baseline。
- lint 仍有 41 warning：固定依赖的新版本提示、CP1 未使用资源、手机竖屏与
  Android 16 大屏方向提示、备份规则建议、占位时间排版提示。详情见已知问题。

所有失败日志保留在本地 artifacts；公开证据包括成功构建、脱敏测试结果与离线套件日志。

## 版权资源与打包准备

原始 ZIP 未修改：SHA-256
`554466cfae4b29ee1ad80c37a0e00b0dc1cf9dd440773f75ac17749facc641b8`。
只导入指定一套试题。标准化 JSON、MP3、原文、翻译、设备截图和数据库副本均在
忽略目录；公开仓库保留完整实现、转换工具、测试源码和不含试题内容的验证证据。
公开代码检查对照本地实际题目/选项/原文片段确认没有泄漏真题文字。

最终本地临时 Debug 构建成功，包含本地离线资源；产物大小与 SHA-256 记录在
final-results.json。合并 Manifest 没有 INTERNET 权限，Media3 仅带网络状态权限。
这些 APK 没有上传 GitHub、没有建立 Release、也没有作为最终安装包交付。
环境已具备后续打包条件；等待用户代码审查和单独的最终 APK 打包指令。

公开 checkout 不含版权题库，必须本地运行转换工具再构建。缺少资源的真实样本
测试会明确 SKIPPED；本次所有对应私有资源测试实际运行，跳过数为 0。
