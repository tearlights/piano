# ADR-0011：结构化乐谱修订持久化与 `.gpiano` v2

日期：2026-08-12

状态：已接受

实现进展：Room v7、标准 MusicXML 修订文件、持久撤销、`.gpiano` v2、v1 导入与损坏包拒绝均已完成设备验证。未来新增位置映射、练习版本或 MIDI 数据时继续按本 ADR 升级格式。

工具链补充（2026-08-12）：为兼容 Kotlin 2.2/KSP2，Room runtime、KTX 与 compiler 由 2.6.1 同步升级到稳定版 2.8.4。数据库版本仍为 7，实体、表结构、迁移和备份格式均不因本次依赖升级而改变；升级后重新生成并核对 schema 7，并在保留应用数据的安装路径上验证打开、修订、撤销、导入与导出。

## 背景

当前 Room v6 只保存原始曲谱库数据，`.gpiano` v1 只覆盖部分 `Score` 字段和单文件原谱。已经验证的 MusicXML 音高校正只存在内存中，退出工作区后会丢失。直接把完整 MusicXML 放进 Room 会放大数据库和迁移成本；只写文件又无法可靠表达版本关系、当前修订和恢复顺序。

## 决策

Room 升级到 v7，新增 `score_structures` 与 `score_revisions` 两张表；MusicXML 正文保存在 App 私有目录，数据库只保存稳定标识、相对路径和修订关系。

```text
Score（可选原谱）
  └─ ScoreStructure
       ├─ currentRevisionId
       └─ ScoreRevision 0（原始/OMR 草稿）
            └─ ScoreRevision 1（用户校正）
                 └─ ScoreRevision n（用户或 AI 派生）
```

### `ScoreStructure`

- `id`：稳定 UUID；
- `sourceScoreId`：可选原谱 UUID，删除原谱时置空以保留用户已完成的结构化练习材料；
- `sourceKey`：数据入口的稳定唯一键，用于再次打开同一结构化对象；
- `title`、`formatVersion`、`currentRevisionId`、`createdAt`、`updatedAt`；
- `recognitionStatus`、`recognitionConfidence` 和可选位置映射相对路径，为 OMR 接口保留正式字段。

### `ScoreRevision`

- `id`、`structureId`、`parentRevisionId`、单结构内单调递增的 `revisionNumber`；
- `kind` 区分 `source`、`omr`、`user` 与 `ai`；
- `musicXmlRelativePath` 指向标准 MusicXML 文件；
- `operationJson` 记录可审计的受约束修改，`createdAt` 记录创建时间。

撤销只切换 `currentRevisionId`，不删除历史修订。用户从旧修订继续修改时产生新的分支修订，仍使用新的递增序号。

## 文件与事务规则

1. 新 XML 必须先通过 `MusicXmlScoreParser` 解析与结构校验。
2. 写入同目录临时文件并原子移动为最终文件，再在 Room 事务内插入修订并切换当前指针。
3. 数据库提交失败时删除这次新文件；旧修订和当前指针保持不变。
4. 加载时必须校验相对路径仍位于 App 私有受控目录；缺失或损坏时回退到上一个有效父修订并向用户报告，不静默伪造乐谱。
5. 删除结构化对象时，由仓库协调数据库级联和私有文件目录清理。

文件布局：

```text
structures/<structure-uuid>/revisions/<revision-uuid>.musicxml
structures/<structure-uuid>/source-map.json
```

## `.gpiano` v2

v2 继续使用 ZIP 容器，并将 `library.json.formatVersion` 升为 2。它覆盖当前工程已经存在的曲谱、文件夹、页面顺序、书签，以及新增的结构化乐谱和修订元数据；对应原谱、图片页、MusicXML 和位置映射文件一并打包。

兼容规则：

- v1 必须继续可导入，缺失字段采用安全默认值；
- v2 导入先解压到临时目录、校验版本/条目路径/必需文件和 MusicXML，再移动文件并在单个 Room 事务中写入；
- 高于当前支持版本的包明确拒绝，不尝试猜测导入；
- ZIP 条目禁止绝对路径和 `..` 路径穿越；
- 恢复失败时回滚本次文件替换，不损坏既有曲谱库；
- v2 仍以标准 MusicXML 为可带走成果，不把 `ScoreIR` 或 alphaTab 私有对象作为唯一备份内容。

## 迁移策略

- v6→v7 只新建空表和索引，不改写已有 `scores`、`score_pages`、`bookmarks`、`folders`，因此现有数据无需转换。
- 所有数据库构造统一通过同一个 Provider 注册 1→7 完整迁移链，避免不同页面遗漏迁移。
- 升级后先在保留真实 App 数据的设备上验证 schema 和既有页面，再验证结构化修订跨进程恢复。

## 后果

- MusicXML 文件可直接导出和由其他标准工具读取，数据库保持较小。
- 文件系统与 Room 无法形成真正的单一 ACID 事务，因此仓库必须承担暂存、原子移动和失败补偿；UI 不得直接写修订文件或 DAO。
- `sourceKey` 是数据入口的幂等键，不替代对外 UUID；未来 OMR 接入必须为每次草稿给出稳定来源键。
- 位置映射、派生练习版本和演奏会话会在后续迁移中扩表，但不得改变本 ADR 的标准文件与版本关系边界。
