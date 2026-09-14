# 完整备份格式 v1

扩展名 `.expensebackup`，内容是普通 ZIP，只包含 `manifest.json` 和 `database.json`，均为 UTF-8 JSON。

`manifest.json` 必要字段：

| 字段 | 含义 |
|---|---|
| format | `cn.student.expensetracker.backup` |
| backupVersion | 1，文件格式版本 |
| databaseVersion | 1，原始数据库 schema 版本 |
| appVersion | `1.0.0` |
| exportTime | 导出时刻，epoch 毫秒 |
| recordCount | 所有持久化表行数之和，包含分类和设置 |
| tableCounts | categories、expenses、multiUse、usageRecords、settings 各表行数 |
| sha256 | `database.json` 原始字节的 SHA-256 十六进制值 |

`database.json`：

| 数组 | 数据 |
|---|---|
| categories | id、name、isDefault、createdAt |
| expenses | id、name、amountCents、categoryId、purchaseDate、note、type、createdAt、updatedAt |
| multiUse | expenseId、multiUseType、startDate、endDate、totalUses、referenceSinglePriceCents |
| usageRecords | id、expenseId、usageDate、note、createdAt |
| settings | key、value |

所有字段必需存在，可选值以 `null` 表示；不使用缺字段自动补零来掩盖损坏。金额单位为分，日期字段为 epochDay，时刻字段为 epoch 毫秒。ID 原样保存，不在恢复时重新生成。

读取步骤：限制容器体积 → 验证 ZIP 目录/条目/CRC/解压长度 → 验证 JSON 结构与版本 → 验证 SHA-256 → 解码 → 验证行数、ID 唯一性、外键、金额、类型、次数及数值范围 → 返回预览。此阶段不修改数据库。

用户确认后，仓库再次验证，再开启一个事务，按依赖顺序清空并插入所有表。提交成功才报告完成。版本 1 不支持合并。

未来升级应为旧备份保留独立 DTO 和迁移函数，将其转换为当前 `LedgerSnapshot` 后统一验证，不能仅修改版本号后强行读取。云传输和加密可封装在输入/输出流外层，保留当前校验和事务边界。
