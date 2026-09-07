# 比价 Agent 恢复与单次用量预算设计

## 1. 背景

当前美团自动化流程依赖固定的无障碍文本、资源 ID、屏幕位置和页面结构。当美团页面变化、商品名称不完全一致、商品所在分类未展开或店铺结果结构变化时，纯代码路径容易得到“找不到商品”或“找不到店铺卡片”。

本设计引入一个失败触发式 Agent：正常流程仍由确定性代码执行；只有出现失败或歧义时，才将经过裁剪的页面状态交给 Agent 判断下一步恢复动作。Agent 不直接操作坐标、不提交订单、不支付，也不能绕过本地预算和动作校验。

## 2. 目标与非目标

### 目标

- 在商品未找到、店铺匹配不确定、页面结构变化等情况下，允许 Agent 判断是否滚动、切换分类、尝试相似名称、跳过当前店铺或停止。
- 每次一键比价有独立、可审计的 token 和费用预算。
- 每次 DeepSeek 调用记录 API 返回的实际 usage，并计算美元成本和可配置汇率下的人民币成本。
- 没有可验证价格时，页面必须明确显示失败或部分结果，不能显示“比价完成”或虚构最低价。
- 将完整查询结果保存为一次快照，首页可以展示最近一次真实查价结果。

### 非目标

- Agent 不直接执行任意坐标点击。
- Agent 不提交订单、不点击支付、不改变用户订单内容。
- 本阶段不让 Agent 参与每个正常页面步骤。
- 本阶段不建立每日或账户级累计预算；预算范围限定为一次完整的一键比价。

## 3. 推荐架构

采用失败触发式 Agent，而不是全程 Agent 或一次性生成完整计划。

~~~text
用户输入
  ↓
确定性美团流程
  ↓
成功 ─────────────→ 读取并校验价格
  ↓ 失败/歧义
整理页面观察值
  ↓
AgentRecoveryPlanner
  ↓ 结构化 RecoveryDecision
RecoveryExecutor 校验并执行有限动作
  ↓
验证页面状态
  ├─ 成功 → 继续查价
  ├─ 失败且预算未超 → 再请求 Agent
  └─ 达到限制 → 停止并展示限制原因
~~~

### 3.1 组件职责

- MeituanAutomator：负责启动、输入、点击、滚动、等待、页面读取和结算边界控制。
- AutomationObservation：描述当前路线、阶段、失败代码、可见内容、已尝试动作和页面预期状态。
- AgentRecoveryPlanner：调用 DeepSeek，将观察值转换为一个受限恢复决策。
- RecoveryDecision：严格 JSON 数据结构，不包含任意坐标或可执行脚本。
- RecoveryExecutor：校验动作白名单、调用本地自动化能力，并验证动作后的页面状态。
- QueryBudget：维护本次查询的调用次数、token、费用和恢复步数。
- UsageLedger：保存每次模型调用的实际 usage、价格版本和费用结果。
- ComparisonSnapshot：保存一次完整查价结果及其预算消耗。

## 4. Agent 输入与输出契约

### 4.1 输入

不将完整无障碍树直接发送给 Agent，而是先裁剪为与当前失败相关的观察值：

- 原始店铺关键词和商品关键词；
- 当前路线：买券、外卖或自取；
- 当前阶段：搜索、店铺、分类、商品、购物车或结算；
- 失败代码和本地失败说明；
- 已尝试的关键词、分类、滚动和店铺；
- 当前页面可见文本、content description、resource ID、控件类型和必要的位置摘要；
- 最近一次动作及其验证结果；
- 本次查询剩余调用次数、token、费用和恢复步数。

输入必须裁剪长度，优先保留当前区域、候选商品、候选店铺、按钮和价格相关文本。初版不发送截图；如未来引入视觉模型，必须单独计量图片 token。

### 4.2 输出

Agent 必须返回 JSON，动作只能来自白名单：

- RETRY_CURRENT
- SCROLL_AND_SCAN
- SWITCH_CATEGORY
- SEARCH_VARIANT
- OPEN_CANDIDATE
- SKIP_STORE
- ASK_USER
- STOP

示例：

~~~json
{
  "action": "SEARCH_VARIANT",
  "keywords": ["空山栀子茶", "空山栀子饮品"],
  "reason": "页面存在相近商品名称，建议先尝试同一商品的常见扩展名",
  "confidence": 0.86,
  "expected_state": "PRODUCT_LIST"
}
~~~

本地执行器必须拒绝未知动作、缺少必要参数、超出当前路线的动作、任意坐标动作，以及任何下单或支付动作。Agent 不能返回价格作为事实；价格只能由页面文本或控件经过本地校验后产生。

## 5. 失败恢复策略

### 5.1 商品未找到

PRODUCT_NOT_FOUND 可以触发以下有限恢复顺序：

1. 继续扫描当前分类或滚动商品列表；
2. 切换页面中可见的相关分类；
3. 使用页面中已出现的相似商品名，或对原关键词生成少量候选变体；
4. 仍未找到则跳过当前店铺。

Agent 不得凭空把不同商品当成同一商品。低置信度时返回 ASK_USER 或 STOP。

### 5.2 店铺未找到

STORE_CARD_NOT_FOUND 可以让 Agent 从当前可见候选名称中重新选择，或建议使用已识别的完整店铺名称重新搜索。不能扩大到无关品牌或任意店铺。

### 5.3 价格未找到

PRICE_NOT_FOUND 只能触发重新抓取、切换已知购物车模式或重新验证页面，不允许 Agent 猜测价格。只有至少一个价格通过本地来源校验时，查询才可进入 SUCCESS 或 PARTIAL。

## 6. 单次查询预算

预算只针对一次完整的一键比价，所有 DeepSeek 调用共享同一个 queryId：

~~~text
maxAgentCalls = 6
maxTotalTokens = 16,000
maxCostUsd = 0.02
maxRecoverySteps = 3
~~~

店铺匹配、商品匹配、恢复判断和价格解析都计入同一预算。每次调用前执行预检查；调用结束后用 API 返回的实际 usage 结算。达到任一限制后不再发起后续模型请求，保留已获取的有效价格并展示预算停止原因。

调用中的请求无法在本地精确预知最终 token，因此调用前的 token 和费用检查属于保守预估；API 返回的 usage 是最终账本依据。应通过裁剪输入、设置有限的 max_tokens 和按剩余预算限制后续调用，避免单次请求无界增长。

## 7. Token 与费用账本

每次 DeepSeek 请求写入一条 UsageLedger：

~~~text
queryId
requestId
phase
model
apiRequestId
startedAt
durationMs

promptTokens
completionTokens
reasoningTokens
cacheHitTokens
cacheMissTokens
totalTokens

priceVersion
billingPeriod
costUsd
exchangeRateUsdToCny
costCny

success
error
~~~

费用计算按输入缓存命中、输入缓存未命中和输出分别计算：

~~~text
costUsd =
  (cacheHitTokens  × cacheHitPrice
 + cacheMissTokens × cacheMissPrice
 + completionTokens × outputPrice) / 1,000,000
~~~

人民币金额使用调用时保存的 exchangeRateUsdToCny 计算，历史记录不随未来汇率变化。价格配置必须包含 priceVersion 和高峰/非高峰计费时段，避免价格调整后无法解释历史费用。

DeepSeek 官方文档：

- [Chat Completions API usage](https://api-docs.deepseek.com/api/create-chat-completion/)
- [Token & Token Usage](https://api-docs.deepseek.com/quick_start/token_usage/)
- [Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/)

## 8. 结果模型与展示

查询状态分为：

- RUNNING：查询中；
- SUCCESS：至少拿到一个可验证价格；
- PARTIAL：部分店铺或方式拿到价格；
- NO_VERIFIED_PRICE：没有任何有效价格；
- BUDGET_EXCEEDED：达到 token、费用、调用次数或恢复步数上限；
- FAILED：流程或网络失败。

只有 SUCCESS 或 PARTIAL 且存在有效价格时，才显示最低推荐。每个候选店铺展示店铺名、距离、买券/外卖/自取价格或明确失败原因。

结果页显示可展开的消耗信息：

~~~text
Agent 调用：3 / 6
Token：4,820 / 16,000
费用：$0.0061 / $0.02
恢复步骤：2 / 3
~~~

ComparisonSnapshot 至少保存：

- 查询关键词和时间；
- 候选店铺；
- 各方式价格、可用状态和失败原因；
- 最低推荐；
- token、美元费用、人民币费用和价格版本；
- 查询最终状态以及是否达到预算上限。

首页收藏订单读取最近一次快照，不再固定显示“尚未查价”。

## 9. 安全与隐私

- API Key 不进入 usage、页面诊断或普通日志；
- 不记录完整 Authorization 请求头；
- 页面观察值只保留本次判断需要的文本和控件摘要；
- 不把用户地址、订单支付信息或完整个人信息发送给 Agent；
- Agent 和执行器均不能触发提交订单或支付；
- 失败时优先显示可解释原因，不显示模型内部原始响应。

## 10. 验证标准

### 单元测试

- usage JSON 能正确解析 prompt、completion、cache 和 total token；
- 费用公式在不同价格版本和计费时段下计算正确；
- 预算达到调用次数、token、费用和恢复步数任一上限时拒绝下一次调用；
- 非法 Agent 动作、任意坐标动作和下单支付动作被执行器拒绝；
- SUCCESS、PARTIAL、NO_VERIFIED_PRICE、BUDGET_EXCEEDED 状态转换正确；
- 没有有效价格时不会产生最低推荐。

### 录制状态测试

使用脱敏后的无障碍页面观察值验证：

- 商品名称不完全一致时能选择有限搜索变体；
- 商品位于其他分类时能提出切换分类；
- 店铺没有目标商品时能跳过当前店铺；
- 页面结构变化且无法恢复时能停止并报告原因。

### 真实设备验收

- 正常成功路径不额外调用 Agent；
- 失败路径调用次数和费用均落在单次预算内；
- 页面变化后 Agent 只能产生白名单动作；
- 至少有一个真实价格才显示最低推荐；
- 达到预算上限后不再触发 DeepSeek 请求；
- 首页能显示最近一次有效查价快照。

## 11. 实施顺序

1. 将 DeepSeekClient 从只返回文本改为返回文本加 usage；
2. 加入 UsageLedger、价格配置和 QueryBudget；
3. 先修正结果状态和“全部失败仍显示完成”的问题；
4. 抽象 AutomationObservation 和 RecoveryDecision；
5. 接入失败触发式 AgentRecoveryPlanner；
6. 加入白名单执行器和动作后验证；
7. 持久化 ComparisonSnapshot 并改造结果页和首页卡片；
8. 按单元、录制状态和真实设备三层完成验收。

