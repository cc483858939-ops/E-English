# 原文与题目同屏精听模式验证报告

日期：2026-10-08。

## 实现

TranscriptScreen 顶部固定共享 AudioControls，中间保留英文、中文、双语模式与
独立原文滚动，底部增加可展开/收起的题目卡片。展开卡片最多占剩余阅读区域
的一半，题干、选项和提交后的答案对照在卡片内部独立滚动。收起后只显示题号、
已选答案或未作答状态以及展开入口。支持上一题、下一题和题号选择框；边界按钮禁用。

题目直接使用 PracticeUiState.questions，答案直接使用 PracticeUiState.answers。
选择操作连接已有 PracticeViewModel.selectAnswer，经原来的 Room 保存流程提交。
没有新增答案副本、评分实现、数据库结构或播放器实例。提交后复用 QuestionCard
的只读与结果展示，使用尝试创建时冻结的题目/标准答案保护历史记录。

题号、展开状态和阅读滚动使用可保存的页面状态；切换题目仅重置题目内部滚动。
收起卡片时在原文尾部补偿视口增加的空间，展开/收起完成测量后恢复阅读偏移，
防止阅读到末尾时 ScrollState 被临时缩小的滚动范围截断。没有对这些操作调用
音频 load/seek、创建新尝试或修改已选答案。页面处理系统栏安全区域。

## 实际验证

使用现有 D 盘 Android SDK 和 Gradle 缓存，在 API 36 x86_64 任务模拟器执行。
Windows gradlew.bat 通过 tools/verify.ps1 运行对应 Gradle 任务。
结果由 JUnit XML 提取，见 evidence/intensive-results.json；成功日志为
evidence/intensive-small.log 和 evidence/intensive-normal.log。

| 执行/环境 | 实际结果 |
| --- | --- |
| testDebugUnitTest | 12 passed，0 failed/error/skipped |
| assembleDebug、assembleDebugAndroidTest | BUILD SUCCESSFUL |
| connectedDebugAndroidTest：1080×1920、420dpi、字体 1.0 | 20 passed，0 failed/error/skipped |
| connectedDebugAndroidTest：720×1280、320dpi（360×640dp）、字体 1.3 | 20 passed，0 failed/error/skipped |
| 已有测试构建实际安装、原文第 24 题展开/收起画面复核 | 两种配置均通过，控件无重叠 |

新增四项设备测试覆盖：

- 21–30 手动切换、首尾边界、指定题号跳转、完整题干和选项访问、单选修改；
  从 Room 查询实际已提交的选择，验证与正常答题页双向一致，同一尝试不产生重复记录。
- 原文末尾连续展开/收起，阅读位置保持；顶部播放器位置固定，实际 MP3 触摸拖动、
  播放和暂停正常；播放中收起/展开不重置进度。Activity 重建保留题号、模式、展开
  状态、阅读偏移和答案。
- 提交后的卡片只读，显示用户答案与标准答案；禁用选项的触摸及直接调用均不能
  修改答案；创建新草稿后再查看旧成绩，原来的已提交 Room 记录保持不变。
- 超长题干和超长选项仅在卡片内部滚动，原文仍保留至少一半阅读区域，原文位置
  和播放器不移动。这一项使用仅存在于测试代码的布局压力样本，没有导入假试题。

完整套件还覆盖既有答题页固定播放器、原文三种模式、多次往返、后台暂停、答案
恢复、漏答确认、幂等提交、10/10 与 8/10 批改、新练习和历史保护。数据库清理只
针对隔离测试数据库，没有改动用户记录的持久化实现。

初次小屏回归为 19 passed、1 failed：末尾阅读位置在重新展开后向前跳了 148px。
修复测量期间的偏移截断后重新编译，以上两轮完整测试全部通过。原断言保留，
没有删除测试或降低标准；失败日志留在本地 artifacts/intensive-small-initial-failure.log。

## 验证边界与交付

真机、API 26 实际运行及扬声器/耳机听感仍为 **NOT VERIFIED**。本次未重新执行
飞行模式和真实进程 force-stop 检查；既有结果保留在先前集成报告中，不冒充本轮验证。
没有开发时间轴自动定位、高亮或自动滚动。

本地构建用于测试，源码与脱敏结果提交 Git。题库、MP3、设备截图、数据库副本和
测试 APK 都在忽略目录；没有上传含版权资源的 APK，没有创建 Release 或交付新安装包。
