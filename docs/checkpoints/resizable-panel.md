# 原文精听自由拖拽面板验证报告

日期：2026-10-08。基于 c88dd9d 的增量修改，仅调整精听页的布局和布局状态。

## 实现与范围

- `TranscriptScreen.kt` 在播放器及语言栏下方测量实际剩余高度，默认展开比例 45%。
  高度连续跟随拖动位移，松手保持当前比例，没有档位吸附、重型动画或 BottomSheet。
  上限约 75%；下限以 25% 为起点，同时为操作栏、原文及题目滚动区预留空间，因此
  小屏幕上的实际下限会高于 25%。通常两侧至少各有 48dp 的可用阅读/滚动空间。
  进一步受限时，题号导航移入题目滚动区并缩小最低视口，避免两侧互相覆盖。
- 新增 `ResizableQuestionPanel.kt`，只有展开卡片中央的灰色横条区域接受纵向拖动。
  横条视觉尺寸 32×4dp，触摸区域 64×48dp，包含 testTag、可访问描述、当前比例和
  无障碍调整动作。内容区普通滑动只滚动内容；展开/收起仍由独立按钮控制。
- 使用 `rememberSaveable` 保存当前比例、上次展开比例、展开状态和题号；语言模式
  沿用现有 SavedStateHandle。收起显示紧凑标题栏，再次展开恢复用户此前调整的比例。
  保存比例而非设备像素高度，重建后根据剩余区域重新计算尺寸。
- 原文与题目分别持有原来的独立 ScrollState。明确设置两个视口高度，并对内容尾部
  做相应补偿，使拖拽过程中最大滚动范围不随视口增加而截断已有偏移。只在一次拖拽
  或展开/收起结束后进行必要的偏移恢复，不在每帧强制滚动，正常阅读手势不受抢占。
  切题仅重置题目内部滚动，原文位置保持；同题缩放、收起再展开保留内部阅读位置。
- 继续复用 QuestionCard 和 PracticeViewModel.selectAnswer。没有改动正常答题页、
  ListeningAudioController、ExoPlayer、Room、评分、历史快照或资源读取；没有答案副本。

## 实际验证

使用 D 盘现有 SDK、Gradle 缓存及 `cp1-api36 / emulator-5570 / API 36 x86_64`。
Windows 通过 `tools/verify.ps1` 调用 gradlew.bat，执行与以下任务等效的 Gradle 检查：

```text
testDebugUnitTest
assembleDebug
assembleDebugAndroidTest
connectedDebugAndroidTest
```

测试结果由实际 JUnit XML 汇总至 [resizable-results.json](evidence/resizable-results.json)。
成功日志为 [构建](evidence/resizable-build.log)、[小屏](evidence/resizable-small.log)、
[正常屏幕](evidence/resizable-normal.log)。

| 执行/环境 | 实际结果 |
| --- | --- |
| testDebugUnitTest | 12 passed，0 failed/error/skipped |
| assembleDebug、assembleDebugAndroidTest | BUILD SUCCESSFUL |
| connectedDebugAndroidTest：1080×1920、420dpi、字体 1.0 | 24 passed，0 failed/error/skipped |
| connectedDebugAndroidTest：720×1280、320dpi、字体 1.3 | 24 passed，0 failed/error/skipped |

全部设备用例实际执行，20 个既有测试保留，新增 4 个测试并扩展长题目压力测试。
两轮 connected 运行分别用时 2m 2s、2m 15s，没有设备测试失败或跳过。
另外实际安装本地测试构建，在两种配置下通过 ADB 触摸横条、收起和展开，复核画面：
灰色横条清楚可见，播放器、语言栏、原文和面板之间没有重叠，收起后为紧凑标题栏。
截图保留在忽略的 `artifacts/resizable-visual/`，未加入公开仓库。

## 修改文件

- `app/src/main/java/com/eenglish/listening/ui/screens/TranscriptScreen.kt`
- `app/src/main/java/com/eenglish/listening/ui/components/ResizableQuestionPanel.kt`（新增）
- `app/src/androidTest/java/com/eenglish/listening/ShellNavigationTest.kt`
- `app/src/androidTest/java/com/eenglish/listening/IntensiveLayoutTest.kt`
- `README.md`、本报告，以及 `evidence/resizable-{build,small,normal}.log` 和
  `evidence/resizable-results.json`。

## 测试覆盖

四个新增设备用例与扩展的长题目压力用例验证：

- 手指未松开时高度已连续增加；上/下拖动、61.3% 任意位置松手后保持高度、上下边界；
  根据实际语义边界及布局尺寸断言，没有只给状态变量赋值。
- 拖到约 65% 后收起/展开恢复比例；第 27 题选 B、中文模式、收起状态进行 Activity
  重建，题号、答案、模式、展开状态及再次展开的高度保持。
- 原文中部、最后一段反复拖动和展开/收起，偏移误差不超过 2px；切题保留原文位置。
  普通内容滑动不改变面板高度。超长题干/选项滚动后再缩放或收起，内部偏移同样保持。
  长文本仅在测试代码中生成，没有导入虚构试题。
- 正常答题页选答案，进入原文拖拽并改答案，返回后与 Room 一致；真实 MP3 播放中
  连续拖动十次，保持同一音频控制器、播放状态、非回退进度及固定播放器位置，未创建
  新尝试。两种屏幕配置下实际断言播放器、语言栏、两侧内容、48dp 横条与系统栏不重叠。

原有全部回归用例保留，已有断言没有降低，包括提交后的选项禁用、历史记录只读、
漏答确认、幂等提交、10/10 与 8/10 批改、Room 重开恢复、后台暂停及正常答题页固定控件。

首轮编译发现嵌套 Compose 接收者内隐式引用 `maxHeight` 的作用域错误；改为显式局部
高度值后重新编译成功。首轮失败日志保留于本地 `artifacts/resizable-build-first-failure.log`。

## 已知边界与交付

真机、API 26 实际运行及扬声器/耳机听感仍为 **NOT VERIFIED**。本轮没有重新执行
飞行模式或真实进程强制结束检查，已有集成报告中的结果不冒充本轮验证。
本地 SDK XML v4/v3 提示仍为非阻塞工具警告；没有禁止检查或删除失败测试。

提交源码、报告和不含试题内容的日志/汇总。版权题库、音频、截图、数据库和本地测试
APK 保留在忽略目录；本轮不发布或交付新 APK，不创建 Release。
远端新增的 `fad56660e2553bd04272c2fa23fb09c2fc89e293` 只修改自动打包工作流，已保留并
同步。本次源码提交使用 `[skip ci]` 避免触发该自动 APK 上传任务；本地测试结果如上，
没有将未执行的 CI 描述为通过。此提交标记遵循 [GitHub 官方规则](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs)。
