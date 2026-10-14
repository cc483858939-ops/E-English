# Luna 执行任务：实现已冻结的 layout v2 离线契约

请在 `D:\code\E-English` 工作。先读取 [layout-schema-v2-contract.md](layout-schema-v2-contract.md) 和 [合成样例](examples/layout-v2/README.md)。这两处是本任务的规范，不需要设计新模型。仓库当前有用户自己的改动；先查看 `git status --short`，保留所有已有改动。

## 允许修改

- 新建 `docs/schemas/layout-v2.schema.json`。
- 新建独立的 `tools/layout_v2_validator.py` 和 `tools/test_layout_v2_validator.py`。

不要改契约文档与合成样例；若发现矛盾，只记录文件、字段和冲突，交回审查。也不要改 `app/`、`part.json` 真实资料、原始 ZIP、现有 v1 Schema/校验器、导入器、评分器、数据库或题库包。不要生成真实题库包，不要暂存、提交或推送。

## 按序执行

1. 以规范中的确切对象形状、闭合字段、枚举和上限实现 JSON Schema 2020-12。JSON Schema 负责单文档结构；跨 `part.json`、来源和图片的关系交给 Python 校验器。
2. Python 校验器使用规范第 3 节给定的 `validate_layout_v2(...)` 函数签名，接收原始 `layout.json`、`part.json` bytes，来源资源 bytes，已由调用方核验的图片哈希/尺寸元数据，以及审核记录。它按规范的固定顺序完成结构和交叉校验，返回已验证的布局或稳定原因码；错误中不打印答案和来源内容。不引入新的 Python 依赖。
3. 用现有 `docs/examples/layout-v2/` 做通过样例。按规范第 4 节逐项制作失败变体，每个变体只改指定位置，断言指定原因码。再跑现有 v1 校验器测试，证明没有回归。
4. 报告执行命令、通过/失败结果、修改文件列表和任何无法照字面实现的规范冲突。遇到冲突时只报告具体字段与样例位置，不添加默认值、兼容分支、额外枚举或放宽规则。

## 停止边界

完成后停止在**离线契约实现**。Android 解码、UI 渲染、真实资料转换和生产包安装是后续任务，必须等本阶段审查通过。当前 App 不支持导入 schema v2，不能把 Python 样例通过说成 App 集成通过。
