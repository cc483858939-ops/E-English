# Luna 执行任务：按已修订契约修复 v2 离线校验

在 `D:\code\E-English` 工作。先读取 [layout v2 契约](layout-schema-v2-contract.md) 的新增规则和第 4 节新增验收变体，再读取 [合成样例](examples/layout-v2/README.md)。这些规则已经确定；本任务不需要重新设计。

只允许修改以下三份实现文件：

- `docs/schemas/layout-v2.schema.json`
- `tools/layout_v2_validator.py`
- `tools/test_layout_v2_validator.py`

不要修改契约、合成样例生成器或生成文件、Android、v1 文件、真实资料、现有用户改动。不要暂存、提交或推送。若规则或样例有冲突，记录确切位置后停止该条，不自行改规范。

## 必须实现

1. **旧多选兼容**：legacy `MULTIPLE_CHOICE` 只可使用一个覆盖其完整 `groupId` 成员集合的 `multipleChoice` ResponseRule；该规则不得包含其他 legacy 类型。违反时返回 `LEGACY_INTERACTION_MISMATCH`，不能把旧多选拆为单选或匹配后仍通过。
2. **匹配控件**：只有 `multipleChoice` ResponseRule 的 `choiceList` 可绑定多个 Slot。单选、匹配的每个 `choiceList` 恰好一个 Slot。违反时 `SLOT_PLACEMENT_MISMATCH`。
3. **匹配复用可满足性**：`reuse: "forbidden"` 时，根据每题实际 `acceptedAnswers`（空则 `correctAnswer`）和 option ID，检查是否存在覆盖该规则所有题目的无重复选项分配。无解时 `MATCHING_REUSE_CONFLICT`；存在替代答案可使其有解时接受。保持旧 `Grader` 与已存答案不变。
4. **双空连接词**：Text Block 只有普通五字段形状，或额外包含 `role: "answerConnector"` 与 `questionId` 的七字段形状；`instructions` 只能用普通 Text。每个 legacy `answerSeparator` 问题恰有一个标记的连接词 Text，文字、题目 ID 与旧 Part 完全匹配，来源仍须通过普通文字来源校验。其两个槽必须是 `slot` Blocks，按展示遍历顺序为槽 0、连接词、槽 1。缺少、重复、文字或题目 ID 不匹配返回 `ANSWER_CONNECTOR_MISMATCH`；槽顺序颠倒返回 `SLOT_PLACEMENT_MISMATCH`。无双空的题不能出现该角色。
5. **审核来源路径**：Text `resourceRange.resource` 仍只允许 `content.html`/`content.txt`。`ReviewRecord.sourceResource` 允许安全的 POSIX 相对归档路径；按契约拒绝绝对路径、反斜杠、空路径组件、`.`、`..`、NUL 与盘符前缀。它始终只是 `source_bytes` 的键，不进行文件系统访问。接受 `archive/path/map.png` 对应的正确来源字节；不接受 `../map.png`。
6. **测试**：保留原 18 个失败变体和八类通过样例，新增契约第 4 节全部修复变体。对真实不可用的私有 ZIP 仍标记跳过。运行全套 `tools` 测试并报告准确计数。标准 JSON Schema Draft 2020-12 引擎若本机没有，就明确写 `NOT VERIFIED`；JSON 解析与 `$ref` 检查不能替代它。

当前合成 `layout.json` 已按修订契约给 `double-connector` 增加 `role` 和 `questionId`；旧实现拒绝此样例是预期的修复起点。完成后只交付修改、测试命令与结果，不继续 Android 或真实题库工作。
