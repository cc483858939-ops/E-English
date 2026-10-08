# 文字选择、复制与永久高亮

日期：2026-10-08。基于 `4207383` 增量实现，不修改播放器、答案保存、批改、历史或自由拖拽面板的业务逻辑。本轮只交付源码。

## 交互与定位

- 检查当前 Compose BOM `2025.08.01` / Foundation `1.9.0` 后保留原依赖。采用 `AndroidView` + 可选择 `TextView`，沿用 Android 原生手柄和浮动菜单，提供复制、高亮、取消高亮及全选。依据实际 `selectionStart/selectionEnd` 的 UTF-16 区间复制及标注，关闭异步智能选区扩展，不通过搜索单词推测位置。
- `AnnotatableText` 只原位更新自有背景/前景 span，不重新设置字符内容或每帧滚动。黄色背景 `#FFE29A` 配深色前景 `#29230E`，在深色模式也可读。结束选择后标记仍显示。
- 原文以 `Part + transcript + segment:index + en/zh + SHA-256` 定位，单语完整原文与双语对应段落使用相同键。重复出现的词、重复段落和两种语言不混用位置。
- 英文/中文模式支持跨行、跨段选择，保存时按段拆分，段间分隔符不保存标记。双语模式按单个语言段选择；页面明确提示跨段选择应切换英文或中文。跨语言段一次性拖选不支持。
- 题干和选项复用相同组件及标记架构，键为 `question / questionId/prompt` 或 `questionId/option:optionId`，语言 `und`。选项的短按更新原来的答案，长按及滚动不会提交选项；已提交试卷仍不可修改答案，文字可选择及标注。
- 高亮区间取并集，重复添加不重复记录；部分取消执行区间差集，保留左右剩余部分及其创建时间。跨段修改在同一 Room 事务内执行。存储失败通过通用提示反馈，不记录选中文字。

相关稳定 API：[TextView](https://developer.android.com/reference/android/widget/TextView)、[Compose 文字交互](https://developer.android.com/develop/ui/compose/text/user-interactions)、[Room 数据库迁移](https://developer.android.com/training/data-storage/room/migrating-db-versions)。

## 数据库迁移与恢复

`PracticeDatabase` 从版本 1 升级为 2。`MIGRATION_1_2` 只新增 `highlights` 表：

```text
partId, scope, textId, language, contentHash, startOffset, endOffset, createdAt
```

前七个字段组成主键。保存偏移与哈希，不保存原文或选中文字，也不引用 sessionId。保留原有 `sessions`、`answers`、索引、外键及题目快照；没有 `fallbackToDestructiveMigration`。

保留版本 1 schema 并提交版本 2 schema。真实 `MigrationTestHelper` 用版本 1 schema 创建已完成记录、草稿、答案及合成题目快照，升级到版本 2 后验证完整 schema 和旧数据，再验证新增标记表可写。

标记经 Room Flow 驱动显示，ViewModel 串行保存操作。退出页面、重新练习及查看历史都不清空 Part 标记。文件重开测试与外部进程结束测试使用各自专用测试数据库，不清空用户的 `practice.db`。

## 验证

使用 D 盘原有 SDK / Gradle 缓存及 API 36 x86_64 的 `emulator-5570`，实际执行 `testDebugUnitTest`、`assembleDebug` 和两轮完整 `connectedDebugAndroidTest`。结果从真实 JUnit XML 汇总至 [annotations-results.json](evidence/annotations-results.json)。

| 验证 | 实际结果 |
| --- | --- |
| JVM 单元测试 | 20 passed，0 failed/error/skipped（原有 12 + 新增 8） |
| assembleDebug | BUILD SUCCESSFUL；仅本地验证构建 |
| 720×1280、320dpi、字体 1.3 | 36 passed，0 failed/error/skipped；完整 Gradle 检查耗时 3m 20s |
| 1080×1920、420dpi、字体 1.0 | 36 passed，0 failed/error/skipped；完整设备检查耗时 3m 26s |
| 独立进程 write → force-stop → read | 两个阶段均 OK (1 test)，进程不存在检查通过，新进程恢复原生黄色 span |

36 个设备用例包括原有 24 个和新增 12 个，原有断言完整保留。两种配置都实际长按文字、拖动系统选择手柄及点击菜单，并执行原有固定播放器、面板边界、字体适配、滚动位置、答案/评分/历史/Activity 重建回归。

成功日志：[小屏幕与构建](evidence/annotations-small.log)、[正常屏幕](evidence/annotations-normal.log)、[进程写入](evidence/annotations-process-write.log)、[新进程读取](evidence/annotations-process-read.log)。没有以 Gradle 缓存或任务名称中的 SKIPPED 代替设备测试结果；上述设备数来自实际 JUnit 结果。

开发过程中修复了原生接口调用编译错误，以及测试的浮动菜单窗口匹配、触屏输入源/手柄垂直间距、主线程 Activity/ViewModel 获取和系统剪贴板预览遮挡问题。失败诊断日志保留在忽略的 `artifacts/`，没有删除失败用例或降低原有断言。最终两轮完整回归均成功。

新增验证覆盖：

- JVM：并集、差集、重复词准确偏移、跨段拆分、语言/内容版本/Part 隔离、UTF-16 与多行、结构不匹配拒绝。
- Room：并发重复高亮、部分取消、文件关闭重开、跨段事务回滚、新练习与历史保留标记、版本 1→2 完整迁移。
- UI：实际手指长按、原生结束手柄触屏拖动跨行、系统菜单精确复制、原位黄色 span、页面重入、部分取消、重复与重叠、深色配色、三模式共享段落标记、题干/选项复制和高亮。
- 回归：播放中选中文字及标记不重置音频，原文位置保持，横条继续拖动；原生选项文字短按与 Room 答案一致，长按复制不改变答案。保留全部原有评分、答案恢复、历史只读、导航、重建、固定播放器及拖拽面板测试。
- 独立进程：原生菜单写入合成文本标记，ADB force-stop 后检查进程不存在，再由新进程打开专用数据库，验证黄色 span 和原字符内容。

精确区间组合测试使用 Android 标准无障碍选区调整 API；另有独立用例通过触屏事件实际拖动原生系统手柄，检查跨行后的真实选区与剪贴板一致。复制后的系统剪贴板预览由测试关闭，避免其遮挡底部卡片。没有在生产代码中隐藏系统剪贴板预览。

## 修改文件

- UI：新增 `ui/components/AnnotatableText.kt`，修改 `QuestionCard.kt`、`TranscriptScreen.kt` 和 `navigation/ListeningApp.kt`。
- 数据：新增 `domain/annotation/Highlights.kt`、`data/local/HighlightEntity.kt`、`data/repository/AnnotationRepository.kt`、`viewmodel/AnnotationViewModel.kt`；修改 `PracticeDatabase.kt`、`ListeningApplication.kt`，新增版本 2 schema。
- 测试：新增 `HighlightTest`、`AnnotationFixtures`、`AnnotationTouch`、`AnnotationRepositoryTest`、`AnnotationUiTest`、`AnnotationProcessTest` 和仅 debug 的空白测试 Activity；扩展 `ShellNavigationTest`。合成文字只位于测试代码。
- 构建：增加同版本 Room 测试依赖及 schema 测试资源目录，没有升级 Compose 或其他生产依赖。
- 工具/文档：`tools/annotation_process_smoke.py`、README、已知问题、本报告及脱敏测试日志/汇总。

## 边界

真机、API 26 实际运行、实际扬声器/耳机听感为 **NOT VERIFIED**。标记段落内容改变时，旧哈希标记保留但不套用到新文本；不进行猜测式位置迁移。本轮不新增逐句时间轴同步，也未重新执行飞行模式测试。

公开提交不包含版权题库、音频、数据库、截图或 APK。推送提交使用 `[skip ci]`，避免触发仓库现有自动 APK 上传工作流；没有创建 Release。
