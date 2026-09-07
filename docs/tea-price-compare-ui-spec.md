# 奶茶比价 App — UI 设计规范

> 范围：覆盖 `tea-price-compare`（Android 原生，Kotlin / AppCompat / Material Components）的视觉、交互、刘海&挖孔&手势条适配与状态机。
> 输出形式：纯规范文档（文字 + ASCII 线框 + 颜色/字号/间距 token 表），**不修改任何代码**。
> 版本：v0.1（设计稿，落地前可微调）

---

## 0. 设计目标

| 维度 | 目标 |
|---|---|
| 风格 | 现代活泼多彩（Material You 大圆角 + 高饱和强调色） |
| 主交互 | 收藏夹 **一键比价**（长按卡片 / 点底部"比价"按钮 / 卡片右上角 FAB） |
| 平台 | 京东、美团（首期两家） |
| 设备 | Android 8.0+（minSdk 26），兼容刘海屏 / 挖孔屏 / 手势导航 / 三段式导航 |
| 暗色 | 全局支持深色模式（DayNight） |
| 可访问 | 字号 / 对比度 / TalkBack 全部满足 WCAG AA |

---

## 1. 设计令牌（Design Tokens）

采用三层结构：**Primitive → Semantic → Component**。所有 UI 只能引用 **Semantic** 与 **Component** 层，**禁止硬编码颜色 / dp / sp**。

### 1.1 Primitive（原始值）

#### 1.1.1 颜色 Primitive

| Token | Light | Dark | 用途 |
|---|---|---|---|
| `--primitive-coral-500` | `#FF5A5F` | `#FF7A7F` | 品牌主色（强调、CTA） |
| `--primitive-coral-600` | `#E8484E` | `#FF5A5F` | 品牌主色按压 |
| `--primitive-coral-100` | `#FFE3E4` | `#3A1F20` | 主色浅底（背景块） |
| `--primitive-amber-500` | `#FFB020` | `#FFC04D` | 京东色（logo 取色） |
| `--primitive-amber-100` | `#FFF1D6` | `#3A2E18` | 京东浅底 |
| `--primitive-teal-500` | `#1ABC9C` | `#22D3B5` | 美团色（logo 取色） |
| `--primitive-teal-100` | `#D1F5EC` | `#163A33` | 美团浅底 |
| `--primitive-violet-500` | `#7C5CFF` | `#9A82FF` | 辅助强调（"省"角标） |
| `--primitive-ink-900` | `#0F172A` | `#F8FAFC` | 主文字 |
| `--primitive-ink-700` | `#334155` | `#CBD5E1` | 次文字 |
| `--primitive-ink-500` | `#64748B` | `#94A3B8` | 辅助文字 / hint |
| `--primitive-ink-300` | `#CBD5E1` | `#475569` | 分割线 / 边框 |
| `--primitive-ink-100` | `#F1F5F9` | `#1E293B` | 卡片底（浅模式） |
| `--primitive-ink-50`  | `#F8FAFC` | `#0B1220` | 页面底（浅模式） |
| `--primitive-success-500` | `#16A34A` | `#22C55E` | 成功 / "更便宜" |
| `--primitive-warning-500` | `#F59E0B` | `#FBBF24` | 警告 |
| `--primitive-danger-500`  | `#EF4444` | `#F87171` | 错误 / 删除 |
| `--primitive-overlay-60`  | `#00000099` | `#000000CC` | 蒙层 |

#### 1.1.2 间距 Primitive（4 倍数）

`--space-0` `0`、`--space-1` `4dp`、`--space-2` `8dp`、`--space-3` `12dp`、`--space-4` `16dp`、`--space-5` `20dp`、`--space-6` `24dp`、`--space-8` `32dp`、`--space-10` `40dp`、`--space-12` `48dp`、`--space-16` `64dp`。

#### 1.1.3 圆角 Primitive

`--radius-xs` `6dp`、`--radius-sm` `10dp`、`--radius-md` `14dp`、`--radius-lg` `20dp`、`--radius-xl` `28dp`、`--radius-full` `999dp`。

#### 1.1.4 字号 Primitive（sp）

| Token | size / line-height | 用途 |
|---|---|---|
| `--font-display` | 28 / 36 | 页面大标题（"奶茶比价"） |
| `--font-title`   | 20 / 28 | 卡片标题（订单名） |
| `--font-subtitle`| 16 / 24 | 分组标题 / 平台名 |
| `--font-body`    | 14 / 20 | 正文 |
| `--font-caption` | 12 / 16 | 辅助说明 / 时间 |
| `--font-num-xl`  | 28 / 32 tabular-nums | 价格主数字 |
| `--font-num-md`  | 18 / 24 tabular-nums | 价格副数字 |

#### 1.1.5 阴影 Primitive

`--elev-0` 无、`--elev-1` `0 1 2 rgba(15,23,42,.06)`、`--elev-2` `0 4 12 rgba(15,23,42,.08)`、`--elev-3` `0 8 24 rgba(15,23,42,.12)`、`--elev-4` `0 16 48 rgba(15,23,42,.18)`。

### 1.2 Semantic（语义别名）

| Semantic | Light → Primitive | Dark → Primitive | 用途 |
|---|---|---|---|
| `--color-bg-page` | ink-50 | ink-50 | 页面底 |
| `--color-bg-card` | `#FFFFFF` | ink-100 | 卡片底 |
| `--color-bg-elev` | ink-100 | `#0F172A` | 二级浮起 |
| `--color-text-primary` | ink-900 | ink-900 | 主文字 |
| `--color-text-secondary` | ink-700 | ink-700 | 次文字 |
| `--color-text-tertiary` | ink-500 | ink-500 | 辅助 |
| `--color-text-on-primary` | `#FFFFFF` | `#FFFFFF` | 主色按钮文字 |
| `--color-divider` | ink-300 | ink-300 | 分割线 |
| `--color-brand` | coral-500 | coral-500 | 品牌主色 |
| `--color-brand-pressed` | coral-600 | coral-600 | 主色按压 |
| `--color-brand-soft` | coral-100 | coral-100 | 主色浅底 |
| `--color-jd` / `--color-jd-soft` | amber-500 / amber-100 | 同 | 京东 |
| `--color-meituan` / `--color-meituan-soft` | teal-500 / teal-100 | 同 | 美团 |
| `--color-success` | success-500 | success-500 | 成功 / 更便宜 |
| `--color-warning` | warning-500 | warning-500 | 警告 |
| `--color-danger` | danger-500 | danger-500 | 错误 / 删除 |
| `--color-overlay` | overlay-60 | overlay-60 | 蒙层 |

### 1.3 Component（组件级）

下面只列关键项，其余组件按相同模式补充：

| Token | 指向 |
|---|---|
| `--button-primary-bg` | `--color-brand` |
| `--button-primary-bg-pressed` | `--color-brand-pressed` |
| `--button-primary-fg` | `--color-text-on-primary` |
| `--button-primary-radius` | `--radius-full` |
| `--button-primary-height` | `48dp` |
| `--card-radius` | `--radius-lg` |
| `--card-padding` | `--space-4` |
| `--card-bg` | `--color-bg-card` |
| `--card-elev` | `--elev-2` |
| `--input-radius` | `--radius-md` |
| `--input-height` | `48dp` |
| `--input-border` | `--color-divider` |
| `--input-border-focus` | `--color-brand` |
| `--chip-radius` | `--radius-full` |
| `--chip-height` | `24dp` |
| `--status-dot-size` | `8dp` |

---

## 2. 字体 / 图标

- 中文：`Source Han Sans / 思源黑体` 或系统默认（`?attr/fontFamily` + `sans-serif-medium`）。
- 数字：等宽数字 `tabular-nums`，避免价格抖动。
- 图标：Material Symbols（Outlined，20/24dp），已包含在 `com.google.android.material:material` 中。

---

## 3. 关键页面布局

> 所有页面根布局为 `androidx.constraintlayout.widget.ConstraintLayout`，使用 `ViewCompat.setOnApplyWindowInsetsListener` 处理系统栏 inset（详见 §6）。纵向 ScrollView / RecyclerView 全部 `clipToPadding=false` + `paddingBottom=gestureBarHeight + 24dp`，确保最后一项不被手势条遮挡。

### 3.1 首页 MainActivity（收藏夹 + 一键比价）

```
┌─────────────────────────────────────┐
│         Status Bar (24+)            │  ← 透明, text 跟随主题
├─────────────────────────────────────┤
│  奶茶比价 🍵              ⚙️  🔧  │  ← TopAppBar 高 56, 横屏 48
├─────────────────────────────────────┤
│  ╭─────────────────────────────╮   │
│  │  ●  无障碍服务已开启         │   │  ← StatusCard, elev-1
│  │  闲置时可自动比价     →      │   │
│  ╰─────────────────────────────╯   │
│                                     │
│  我的收藏 (3)         [全部比价]    │  ← SectionHeader
│  ┌───────────────────────────┐    │
│  │ 🧋 下午茶奶茶            📊 │    │  ← FavoriteCard, 圆角 20
│  │ 喜茶  ·  一点点  · 蜜雪    │    │     高度 96, padding 16
│  │ JD ¥18  MT ¥15  省 ¥3   │    │     长按 = 直接比价
│  └───────────────────────────┘    │
│  ┌───────────────────────────┐    │
│  │ 🧋 加班续命大杯          📊 │    │
│  │ 瑞幸  ·  一点点           │    │
│  │ JD ¥22  MT ¥19  省 ¥3   │    │
│  └───────────────────────────┘    │
│                                     │
│           (RecyclerView, weight=1)  │
│                                     │
├─────────────────────────────────────┤
│           ＋  新增收藏               │  ← StickyBottomBar, 高 80 (含手势条)
└─────────────────────────────────────┘
          Gesture Bar (24, handle only)
```

#### 3.1.1 组件清单

| 区域 | 组件 | 关键 token |
|---|---|---|
| TopAppBar | `MaterialToolbar` | 背景 `--color-bg-page`，标题 `--font-title`，图标 24dp |
| StatusCard | 自定义 `MaterialCardView` | `--card-bg`、`--card-elev` 1，radius `lg`，padding `space-4` |
| 状态点 | 8dp 圆点 | 颜色根据 `isEnabled` 在 success-500 / danger-500 切换 |
| SectionHeader | 横向 `LinearLayout` | 左 16sp bold，右 14sp brand color |
| FavoriteCard | `MaterialCardView` | radius `lg`，elev `2`，padding `space-4` |
| 价格 Chip | 横向 3 chip | 圆角 `full`，高 28，背景 `--color-jd-soft` / `--color-meituan-soft`，文字 `--color-jd` / `--color-meituan` |
| "省 ¥X" 角标 | `Chip` (assist) | 背景 `--color-brand-soft`，文字 `--color-brand`，加粗 |
| 比价入口 | 卡片右上角 `IconButton` (24dp) | 图标 `insights` |
| 长按手势 | `OnLongClickListener` → 启动比价流程 | Ripple 反馈 |
| StickyBottomBar | `ConstraintLayout` 底部 | 高度 80（56 按钮 + 24 底），按钮 `--button-primary-*` |
| FAB 备选 | 右上角 extended FAB | 与 StickyBottomBar 二选一（推荐 Sticky，触达更稳） |

#### 3.1.2 状态机（首页）

| 状态 | 触发 | 视觉变化 |
|---|---|---|
| 加载中 | `onCreate` → `store.list()` 异步化 | 卡片区显示 3 个 shimmer 占位（`--color-bg-elev` 闪烁） |
| 空数据 | 收藏 = 0 | 中央插画 + 文字"还没有收藏，点下面新增你的第一杯奶茶吧" |
| 无障碍未开启 | 首次启动 | StatusCard 红色 + 主按钮变成"去开启"（跳系统设置） |
| 比价中 | 卡片右上角 spinner | 卡片轻微降透明度 0.7，禁用点击 |
| 错误 | API 异常 / 超时 | Snackbar 顶部，红色，文案"比价失败：xxx" |

### 3.2 新增 / 编辑订单 FavoriteOrderEditActivity

```
┌─────────────────────────────────────┐
│  ←  新增收藏                  保存  │  ← TopAppBar, 返回键 48dp 命中区
├─────────────────────────────────────┤
│  名称                                │
│  ┌───────────────────────────────┐ │
│  │ 下午茶奶茶                    │ │  ← TextInputLayout outlined
│  └───────────────────────────────┘ │
│                                     │
│  比价的平台                          │  ← SectionHeader
│                                     │
│  ┌─ 京东 ─────────────────── ¥─┐  │
│  │ 店铺搜索词                 │  │
│  │ ┌───────────────────────┐  │  │
│  │ │ 喜茶                   │  │  │
│  │ └───────────────────────┘  │  │
│  │ 饮品名称                   │  │
│  │ ┌───────────────────────┐  │  │
│  │ │ 多肉葡萄               │  │  │
│  │ └───────────────────────┘  │  │
│  └────────────────────────────┘   │  ← 平台分组, border-left 4dp
│                                     │     用 --color-jd
│  ┌─ 美团 ─────────────────── ¥─┐  │
│  │ ...同上, border-left teal  │  │
│  └────────────────────────────┘   │
│                                     │
│  ┌───────────────────────────┐    │
│  │  预览比价                   │    │  ← 次级按钮 outline
│  └───────────────────────────┘    │
│  ┌───────────────────────────┐    │
│  │       保存                  │    │  ← Primary
│  └───────────────────────────┘    │
│                                     │
│        24  (handle bar safe area)   │
└─────────────────────────────────────┘
```

#### 3.2.1 组件清单

| 区域 | 组件 | 关键 token |
|---|---|---|
| TopAppBar | `MaterialToolbar` | 标题 16sp medium，左返回 + 右"保存"文字按钮 |
| SectionHeader | TextView | 12sp 灰，paddingTop `space-4` |
| 名称输入 | `TextInputLayout` (outlined) | label "名称"，hint 空 |
| 平台分组 | `MaterialCardView` | radius `md`，elev `0`，border-left 4dp（颜色随平台） |
| 平台名 | TextView | 14sp medium，配平台色 |
| 副输入 | `TextInputLayout` (outlined) | 48dp 高，圆角 `md`，focus 时 border 转 brand |
| 预览按钮 | outlined MaterialButton | 高度 48，圆角 `full`，stroke 1.5dp brand |
| 主按钮 | filled MaterialButton | 高度 48，圆角 `full`，bg brand |

#### 3.2.2 校验

| 字段 | 规则 | 错误文案 |
|---|---|---|
| 名称 | 必填，≤ 20 字 | "给奶茶起个名字吧" |
| 店铺 / 饮品 | 至少 1 个平台全填 | "至少填一个平台的店铺和饮品" |
| 平台选择 | 至少 1 个 | 隐藏式：未填的 platforms 保存时丢弃 |

### 3.3 设置页 SettingsActivity

```
┌─────────────────────────────────────┐
│  ←  设置                              │
├─────────────────────────────────────┤
│  AI 识别                            │
│  ┌───────────────────────────────┐ │
│  │ DeepSeek API Key            │ │  ← List item, 56dp
│  │ sk-xx...xx12         编辑  →  │ │
│  └───────────────────────────────┘ │
│                                     │
│  通用                                │
│  ┌───────────────────────────────┐ │
│  │ 暗色模式    [系统] [浅] [深]  │ │  ← SegmentedButton
│  ├───────────────────────────────┤ │
│  │ 通知与提示   ○                │ │  ← Switch
│  └───────────────────────────────┘ │
│                                     │
│  关于                                │
│  ┌───────────────────────────────┐ │
│  │ 版本                  0.1.0   │ │
│  │ 隐私                       →  │ │
│  │ 开源协议                   →  │ │
│  └───────────────────────────────┘ │
└─────────────────────────────────────┘
```

#### 3.3.1 API Key 子页（推 push）

`TextInputLayout` outlined + `passwordToggleEnabled`，右下角"保存"filled 按钮，顶部红色说明 "Key 仅保存在本机，不会上传"。

### 3.4 调试页 AccessibilityDumpActivity

```
┌─────────────────────────────────────┐
│  ←  界面调试                          │
├─────────────────────────────────────┤
│  当前页面节点      [刷新] [复制]    │
│  ┌───────────────────────────────┐ │
│  │ class     text    res-id ... │ │  ← 等宽字体, 12sp
│  │ android.v │       text=...    │ │
│  │ ...                          │ │
│  └───────────────────────────────┘ │  ← ScrollView
│                                     │
│  [导出 JSON] [清空]                 │  ← Bottom bar
└─────────────────────────────────────┘
```

风格保持与全站统一（卡片 + 主色按钮），但内容区用等宽字体 `monospace`，底色 `--color-bg-elev` 区分。

---

## 4. 组件规格（Component Specs）

### 4.1 Button

| Variant | 高度 | 圆角 | 字号 | 默认态 | 按压态 | 禁用态 |
|---|---|---|---|---|---|---|
| Primary | 48 | full (999) | 14 medium | bg brand / fg on-primary | bg brand-pressed / fg on-primary | bg ink-300 / fg on-primary |
| Outline | 48 | full | 14 medium | bg 透明 / fg brand / stroke 1.5 brand | bg brand-soft | stroke ink-300 / fg ink-500 |
| Text | 40 | full | 14 medium | 透明 / fg brand | bg brand-soft | fg ink-300 |
| Icon | 40 | full | — | 透明 / fg ink-700 | bg ink-100 | fg ink-300 |
| Destructive | 48 | full | 14 medium | bg danger / fg on-primary | bg danger-darkened 10% | bg ink-300 |

- 命中区：所有按钮最小 48×48dp（Material guideline）。
- 加载中：替换文字为 `CircularProgressIndicator`（size 20，stroke 2.5），颜色取按钮 fg。

### 4.2 Card（MaterialCardView）

| 状态 | 圆角 | 阴影 | 边框 |
|---|---|---|---|
| Default | lg (20) | elev-2 | 无 |
| Pressed | lg | elev-1 | 1dp brand |
| Disabled | lg | elev-0 | 1dp ink-300，opacity 0.6 |

### 4.3 TextInputLayout（outlined）

| 状态 | label | 边框 | helper |
|---|---|---|---|
| Empty | hint 居中 | 1dp ink-300 | — |
| Focus | label 浮上、brand 12sp | 2dp brand | — |
| Filled | label 浮上、ink-700 12sp | 1dp ink-300 | — |
| Error | label 浮上、danger 12sp | 2dp danger | 12sp danger 文字 |

高度 56（label 12 + 内文 20 + padding 上下 12），圆角 `md`。

### 4.4 Chip / Tag

圆角 `full`，高度 24/28/32（sm/md/lg），12sp medium，背景色由语义决定（"省"用 brand-soft + brand）。

### 4.5 StatusDot + StatusText

水平 `LinearLayout`：8dp 圆点 + 4dp gap + 14sp 文字。颜色映射：`online` → success-500、`off` → ink-500、`error` → danger-500。

### 4.6 BottomSheet（比价结果）

- `BottomSheetDialog`，`peekHeight = 50%` 屏高，`state = STATE_HALF_EXPANDED`。
- 顶部拖拽 handle 圆角 2×16dp 灰色，水平居中。
- 内容：标题（订单名 18sp bold）→ 两行平台价格（每行：平台色 logo + 店名 + 价）→ 分隔 → "省 ¥X，比 X 家便宜" 总结 → "重新比价" 文字按钮 + "完成" 主按钮。

### 4.7 Snackbar

- 顶部位置（避免被 BottomSheet / 软键盘遮）。
- 圆角 `md`，背景 `ink-900`，文字 white。
- Action 文字 brand-500。
- 时长：默认 4s，错误 6s。

### 4.8 ProgressIndicator

- 圆形：size 20 / 36 / 48，stroke 2.5 / 3 / 4，颜色 brand / on-primary。
- 线性：高度 4，圆角 2，颜色 brand / brand-soft 底。

---

## 5. 颜色 / 主题规则

### 5.1 主题

- 继承 `Theme.Material3.DayNight.NoActionBar`（需将 `com.google.android.material:material` 加到 `app/build.gradle.kts`，并把 `themes.xml` 父级改为 `Theme.Material3.*`；先不改代码，**这里只描述**）。
- 颜色覆盖：把 §1.2 全部塞进 `colors.xml`（light）+ `colors-night.xml`（dark）。
- 状态栏：`WindowCompat.setDecorFitsSystemWindows(window, false)` + `window.statusBarColor = Color.TRANSPARENT` + `WindowInsetsControllerCompat.isAppearanceLightStatusBars = !isDark`。

### 5.2 配色原则

- 主色 `--color-brand` 仅用于：主 CTA、关键状态、强调文字。**不**用于大面积背景。
- 平台色（jd / meituan）**不**做整体页面主色，只用于平台标识 / 比价区分。
- 卡片 vs 页面：浅模式页面底 `--color-bg-page` (ink-50) 比卡片 `--color-bg-card` (#FFFFFF) **深一档**，形成"白卡浮在灰底"层次。深色模式相反。

---

## 6. 刘海 / 挖孔 / 导航条 / 手势条 适配

> 这是这个项目最容易被忽略但又最容易出 bug 的部分，单独一节。

### 6.1 总原则

1. **所有页面根布局**套 `ViewCompat.setOnApplyWindowInsetsListener`，把系统栏 insets 喂到内 padding，**不要**用 `android:fitsSystemWindows="true"`（会和 CoordinatorLayout 互相干扰）。
2. **所有可滚动容器**（RecyclerView / NestedScrollView / ScrollView）必须：
   - `android:clipToPadding="false"`
   - `android:paddingTop` 至少 状态栏 inset
   - `android:paddingBottom` 至少 手势条 / 导航条 inset + 16dp
3. **横屏**：页面用 `ConstraintLayout` 不要 `LinearLayout` 撑满；左右各留 `displayCutout` safe inset 的一半。
4. **挖孔 / 刘海**：
   - `AndroidManifest.xml` 的 `<application>` 或 `<activity>` 加 `android:windowLayoutInDisplayCutoutMode="shortEdges"`（API 28+）— **短边刘海** 允许内容延伸到刘海两侧（用于全屏沉浸式，如比价结果 BottomSheet 全屏态）。
   - 普通页面（首页 / 编辑 / 设置）保留 `default`（即 `windowLayoutInDisplayCutoutMode="default"`），状态栏由系统处理。
   - `compileSdk = 35`（当前已设）：API 30+ 的 `WindowMetrics.bounds` 可直接拿到 cutout insets，不需要反射。

### 6.2 具体页面 inset 方案

| 页面 | TopAppBar | 内容容器 | 底部按钮 / BottomSheet |
|---|---|---|---|
| 首页 | 高度 56，paddingTop = statusBars + navBars（如有横屏） | RecyclerView paddingTop = topAppBar 高度 + 16，paddingBottom = 24 | StickyBottomBar paddingBottom = gestureBars + 8 |
| 编辑 | 同上 | ScrollView paddingTop = 56 + 16，paddingBottom = 24 | 主按钮容器 paddingBottom = gestureBars + 16 |
| 设置 | 同上 | List paddingTop = 56，paddingBottom = 24 | — |
| 调试 | 同上 | ScrollView paddingTop = 56 + 8，paddingBottom = 24 | 底部按钮 paddingBottom = gestureBars + 8 |
| 比价 BottomSheet | handle 顶部 inset = 0（系统会处理） | 内容 paddingTop = 16，paddingBottom = gestureBars + 16 | — |

### 6.3 三段式导航 vs 手势条

- 检测方式：`RootViewCompat.getInsets(...).getInsets(WindowInsetsCompat.Type.systemBars()).bottom > 24dp` → 三段式，否则手势。
- 实现：写一个 `ViewExt.kt`（**未实现，只描述**）：

```kotlin
// pseudocode
val sys = ViewCompat.getRootWindowInsets(view)!!
val bars = sys.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
val gesture = sys.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
val nav = bars.bottom
val isGesture = nav < 24
val bottomInset = if (isGesture) gesture else nav
```

### 6.4 软键盘

- 含 `EditText` 的 Activity（编辑页 / API Key 子页）：
  - `android:windowSoftInputMode="adjustResize"`
  - 内容用 `ScrollView`，键盘弹起时主按钮**随之上推**（`adjustResize` + `windowSoftInputMode=adjustResize`，已自带）。
  - API 30+ 推荐 `WindowCompat.setDecorFitsSystemWindows(window, false)` + `ViewCompat.setWindowInsetsAnimationCallback`，避免 adjustResize 在新版上的兼容性 bug。

### 6.5 横屏（foldable / 平板 / 横屏手机）

- `configChanges="orientation|screenSize|screenLayout"`（避免 Activity 重建闪烁）。
- 首页横屏：TopAppBar + 左侧 StatusCard + 右侧 2 列 RecyclerView（`GridLayoutManager spanCount=2`）。
- 编辑页横屏：左右分栏（左侧 名称 + 京东组，右侧 美团组 + 主按钮）。
- BottomSheet 在横屏：宽度固定 480dp，居中。

### 6.6 平板（sw600dp）

- `values-sw600dp/dimens.xml`：卡片间距 24，页面左右内边距 48。
- 字体保持 sp 不变，**只**变间距和容器宽度。

---

## 7. 微交互 / 动效

| 场景 | 动效 | 时长 / 曲线 |
|---|---|---|
| 卡片按压 | Ripple + elev 2→1 | 150ms standard |
| 长按触发比价 | 卡片缩放 0.97 + 触发比价 | 200ms emphasized |
| 价格 chip 出现 | fade + translateY 8→0 | 250ms decelerate |
| BottomSheet 上推 | Material 标准 | 300ms |
| 状态点闪烁（比价中） | alpha 1↔0.3 | 1200ms 循环 |
| 新增 / 删除卡片 | LayoutAnimation fade | 200ms |

> 实现：用 `MotionLayout` 或 `androidx.dynamicanimation`。`LongPress` 反馈建议叠加 `HapticFeedbackConstants.LONG_PRESS`。

---

## 8. 可访问性

- 文字最小 12sp（caption），主文字 14sp。
- 对比度：主文字 vs 卡片底 ≥ 7:1，次文字 ≥ 4.5:1，"省"角标文字 vs brand-soft 底 ≥ 4.5:1。
- 触摸目标 ≥ 48×48dp（卡片整体即为命中区，长按也算）。
- `contentDescription`：所有 IconButton 必须有（比价 / 编辑 / 删除 / 调试），按钮文字直接读 text。
- TalkBack 顺序：TopAppBar → StatusCard → SectionHeader → 卡片列表（每张卡片合并为 1 个可聚焦项：标题 + 价格 + 比价）。
- 字号缩放：支持系统字号 0.85x / 1.0x / 1.15x / 1.3x，使用 `sp` 即可；卡片高度不写死，用 `wrap_content` + `minHeight`。

---

## 9. 文案规范

| 场景 | 文案 | 说明 |
|---|---|---|
| 主页标题 | "奶茶比价" | 简短直接 |
| 空状态 | "还没有收藏，去点下面新增你的第一杯奶茶吧" | 第二人称、行动指引 |
| StatusCard 关闭 | "无障碍服务未开启 · 去开启 →" | 状态 + 行动 |
| StatusCard 开启 | "无障碍服务已开启 · 闲置时自动比价" | 状态 + 价值 |
| 比价结果标题 | "比 X 家便宜 ¥X" | 用"便宜"而非"省"（更口语） |
| 比价错误 | "比价失败：网络不太给力，再试一次？" | 不暴露技术细节 |
| 删除确认 | "确定要删除『下午茶奶茶』吗？" | 用引号包裹名字 |

- 不用感叹号（除"完成！"等明确成功）。
- 不用 emoji 在主文字（仅标题装饰 1 个 🍵）。
- 数字一律半角，金额 ¥ + 数字之间无空格。

---

## 10. 验收清单（落地前自查）

- [ ] 所有页面在 `sw360dp` / `sw411dp` / `sw600dp` / `sw840dp` 四档断点无溢出 / 不被裁切
- [ ] 刘海屏（Pixel 6 Pro）、挖孔屏（小米 12）、水滴屏（华为 P50）三台机器 截图无遮挡
- [ ] 三段式导航（Android 8 / 9 模拟器）底部按钮与导航条间距 ≥ 8dp
- [ ] 手势条（Android 10+ 模拟器）底部按钮与 home indicator 间距 ≥ 8dp
- [ ] 横屏 / 折叠屏展开态无内容丢失
- [ ] 浅色 / 深色 / 高对比度（系统设置）三档颜色全部对比度达标
- [ ] 系统字号 0.85x / 1.3x 不出现文字裁切
- [ ] TalkBack 焦点顺序与视觉顺序一致
- [ ] 所有按钮最小命中区 48dp
- [ ] 软键盘弹起时主按钮可见且可点击

---

## 11. 待用户确认 / 后续可调

1. 顶部 FAB vs Sticky Bottom：当前推荐 Sticky Bottom。
2. 比价结果用 BottomSheet 还是新页面：当前推荐 BottomSheet（轻量、可对比）。
3. 是否需要"按平台筛选"顶部 Tab：当前不加入，留作 v0.2。
4. 是否需要历史比价趋势：当前不加入，留作 v0.2。
5. 是否切换到 Material3：建议切换（Material2 已进入维护期）；本设计以 Material3 为目标语法。
6. 是否需要新增 `com.google.android.material:material` 依赖：是（目前只引了 `appcompat`，缺 Material 组件）。**不改代码，仅记录。**

---

## 附录 A：ASCII 资源占用示意（首页线框尺寸）

```
首页 MainActivity (360 x 800 dp)
┌──────────────────────────────────┐
│ Status Bar                 24dp  │
├──────────────────────────────────┤
│ TopAppBar                  56dp  │
├──────────────────────────────────┤
│ StatusCard  margin 16          80dp
│                                  
│ SectionHeader            32 + 8dp
│                                  
│ FavoriteCard x 3          96 x 3
│ gap 12                               
│ RecyclerView (weight=1)           
│                                  
├──────────────────────────────────┤
│ StickyBottomBar         80 (含手势)
└──────────────────────────────────┘
  Gesture Bar handle       24 (透明)
```

> 总内容高：24 + 56 + 80 + 40 + 3*96 + 2*12 + 80 + 24 ≈ **768 dp**，800 - 768 = 32 dp 富余。允许卡片自适应。
