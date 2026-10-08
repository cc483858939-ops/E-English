# 听力答题页固定播放器验证报告

日期：2026-10-08。

播放器移出题目 LazyColumn，放在页面顶部的紧凑固定区域；题目列表使用
weight(1f) 独立滚动。查看原文位于固定顶栏，提交按钮继续固定在底部。
答题页处理状态栏、导航栏与横向安全区域，并消费 Scaffold 的内容内边距。

AudioControls 增加仅在答题页启用的紧凑模式。继续调用已有共享
ListeningAudioController；未修改播放器实例管理、ViewModel、Repository、Room
表结构、保存逻辑、评分或历史记录逻辑。原文页沿用原来的播放器布局。

## 已执行验证

使用现有 D 盘 SDK、Gradle 缓存及 API 36 x86_64 模拟器。结果由 JUnit XML
提取，见 evidence/fixed-player-results.json；没有把配置任务的 SKIPPED 当作测试通过。

| 检查 | 实际结果 |
| --- | --- |
| testDebugUnitTest | 12 passed，0 failed/error/skipped |
| assembleDebug | BUILD SUCCESSFUL |
| connectedDebugAndroidTest：1080×1920、420dpi、字体 1.0 | 16 passed，0 failed/error/skipped |
| connectedDebugAndroidTest：720×1280、320dpi（360×640dp）、字体 1.3 | 16 passed，0 failed/error/skipped |
| 实际 APK 安装、启动及手势滚动截图检查 | 两种配置均到达第 30 题，播放器、原文入口与提交按钮可见 |

新增回归测试逐题滚动 21–30，确认播放器位置不变、控件始终可见；在第 30 题
选择答案后，实际播放、暂停、触摸拖动进度条并继续播放。进入原文再返回，确认
controller、尝试 ID、答案和暂停位置保持。还检查播放器、题目区域与提交按钮
边界互不重叠，控件触摸区域不覆盖时间文字，内容避开系统栏。

完整设备测试同时覆盖原文模式切换、多次页面往返、Activity 重建、后台暂停、
答案修改及保存、漏答确认、重复提交、10/10 与 8/10 评分、重新练习及历史保护。
测试清理仅作用于隔离测试数据库；未清理用户的答题记录。

第一轮小屏测试发现时间文字与进度条触摸区域重叠，15 项通过、1 项失败。
增加间距后重新编译，以上两轮完整设备测试均通过。失败日志保留在本地
artifacts/fixed-player-small-initial-failure.log，没有删除测试或降低断言标准。
成功执行日志见 evidence/fixed-player-small.log 和 evidence/fixed-player-normal.log。

真机、API 26 实际运行和扬声器/耳机听感仍为 **NOT VERIFIED**。本次完成的是
模拟器布局和实际 Media3 解码/进度控制验证。没有新增 APK 发布或 GitHub Release；
版权资源、设备截图、数据库副本与本地 APK 均保留在忽略目录。
