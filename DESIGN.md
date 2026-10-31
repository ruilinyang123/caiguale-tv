---
version: alpha
name: "Apple"
description: "Apple 的 DESIGN.md 中文参考模板，保留原始 design token 与专业术语，覆盖 color system、typography、layout、components、motion 与 interaction states。"
language: zh-CN
sourceLanguage: en
---

## 概览

Apple 的 web 体验是"以近乎隐形的 UI 承托 product photography"的典型范式。页面由一组 edge-to-edge product tiles 组成，light canvas 与 dark canvas 交替出现，每个 tile 都围绕 hero headline、一句 tagline、两个细小的 blue pill CTA 和极其精细的 product render 展开。没有任何 UI 元素与 product 抢占注意力。Typography 自信但克制；color 要么是 pure white，要么是 off-white parchment，要么是 near-black tile；interactive elements 始终由一种安静的 blue 统一承载。

整体 density 即使放在现代 SaaS 标准里也非常低。每个 tile 大约占据一个 viewport，不使用 decorative chrome：没有 borders、没有 gradients、没有 decorative frames，也没有 headline shadow。Elevation 只在 product image "落在 surface 上"时出现：一层柔和的 `rgba(0, 0, 0, 0.22) 3px 5px 30px` drop-shadow 用来增加 product 的物理重量。最终效果更像一个 museum gallery：墙面退场，artifact 成为主角。

## Colors

### Brand & Accent
- **Action Blue** (`#0066cc`): 唯一的 brand-level interactive color。所有 text links、blue pill CTA、focus ring root 都使用它。
- **Focus Blue** (`#0071e3`): Action Blue 的略亮版本，专用于 button 的 keyboard focus ring。
- **Sky Link Blue** (`#2997ff`): 用在 dark surfaces 上的 in-copy links。

### Surface
- **Pure White** (`#ffffff`): 主导性的 canvas。
- **Parchment** (`#f5f5f7`): 标志性的 off-white，用于交替的 light tiles 与 footer。
- **Pearl Button** (`#fafafc`): 接近 white 的 secondary/ghost button fill。
- **Near-Black Tile 1** (`#272729`): 主 dark-tile surface。
- **Pure Black** (`#000000`): 只用于 video player backgrounds、global nav bar background。

### Text
- **Near-Black Ink** (`#1d1d1f`): 所有 headline、body paragraph 的主色。
- **Body On Dark** (`#ffffff`): Dark tiles 上的所有 text。
- **Body Muted** (`#cccccc`): Dark tiles 上的 secondary copy。
- **Ink Muted 48** (`#7a7a7a`): Disabled button text 与 legal fine-print。

### Hairlines & Borders
- **Hairline** (`#e0e0e0`): utility cards 上的 1px hairline border。
- **Divider Soft** (`#f0f0f0`): secondary buttons 的 "border" tone。

## Typography

### Font Family
- **Display**: `SF Pro Display, system-ui, -apple-system, sans-serif`
- **Body / UI**: `SF Pro Text, system-ui, -apple-system, sans-serif`
- 跨平台 fallback：`Inter`（Google Fonts）是最接近的开源替代。

### Hierarchy

| Token | Size | Weight | Line Height | Letter Spacing | Use |
|---|---|---|---|---|---|
| hero-display | 56px | 600 | 1.07 | -0.28px | Hero headline，标志性的 "Apple tight" tracking |
| display-lg | 40px | 600 | 1.10 | 0 | tile headline |
| display-md | 34px | 600 | 1.47 | -0.374px | Section heads |
| lead | 28px | 400 | 1.14 | 0.196px | Product tile subcopy |
| body | 17px | 400 | 1.47 | -0.374px | Default paragraph |
| caption | 14px | 400 | 1.43 | -0.224px | Secondary captions, button text |
| button-utility | 14px | 400 | 1.29 | -0.224px | Utility/nav button labels |

### Principles

- **Display sizes 使用 negative letter-spacing**，形成标志性的 "Apple tight" cadence。
- **Body copy 是 17px，而不是 16px。**
- **Headline 使用 weight 600，而不是 700。**
- **Weight 500 刻意缺席。** Scale 是 300 / 400 / 600 / 700。

## Layout

- **Base unit:** 8px。结构布局对齐到 8/12/16/20/24。
- **Section vertical padding:** 80px；tiles edge-to-edge 堆叠，由 color change 形成分隔。
- **Max content width:** product grids 约 1440px；text-heavy sections 约 980px。
- **Footer** 例外：刻意提高 density，让完整信息架构在一眼内可见。

## Elevation & Depth

| Level | Treatment | Use |
|---|---|---|
| Flat | No shadow, no border | Full-bleed tiles, footer |
| Soft hairline | 1px `rgba(0, 0, 0, 0.08)` border | Utility cards |
| Product shadow | `rgba(0, 0, 0, 0.22) 3px 5px 30px 0` | Product renders，system 中唯一真正的 shadow |

**Shadow philosophy.** 只使用**一层** drop-shadow，只应用在 product imagery 上。UI elevation 来自 surface-color change 与 sticky bars 的 backdrop-blur。

## Shapes

| Token | Value | Use |
|---|---|---|
| none | 0px | Full-bleed product tiles |
| sm | 8px | Dark utility buttons |
| lg | 18px | Store utility cards |
| pill | 9999px | Primary blue pill CTAs, search input |

## Components

### Buttons

**`button-primary`** — 标志性的 Apple action。Background Action Blue `#0066cc`，text white，rounded pill，padding 11px × 22px。Active state 用 `transform: scale(0.95)`。

**`button-secondary-pill`** — Ghost pill。Background transparent，text Action Blue，1px solid Action Blue border，rounded pill。

**`button-dark-utility`** — Global nav actions。Background near-black `#1d1d1f`，text white，rounded 8px。

### Cards & Tiles

**`product-tile-light`** — Full-bleed light tile。Background white，text near-black，vertical padding 80px。Centered stack：product name → tagline → CTA → product render。

**`product-tile-parchment`** — 与 light tile 相同，但使用 parchment `#f5f5f7`。用于打断连续 white tiles。

**`product-tile-dark`** — Full-bleed dark tile。Background `#272729`，text white，用于交替 dark band。

**`store-utility-card`** — Background white，1px hairline border，rounded 18px，padding 24px。

### Footer

Background parchment `#f5f5f7`，text muted。Link columns 使用宽松 leading (2.41) 保持可扫描。Vertical padding 64px。

## Do's and Don'ts

### Do
- 所有 interactive element 使用 Action Blue `#0066cc`。Single accent 不可破坏。
- Headlines 使用 weight 600 + negative letter-spacing。
- Body copy 使用 17px / 400 / 1.47。
- 用 light 与 dark tiles 交替构建 full-bleed section rhythm。
- 将 pill 保留给 primary blue CTA 以及所有应被识别为 "action" 的元素。

### Don't
- 不要引入第二个 accent color。
- 不要给 cards、buttons 或 text 添加 shadow；shadow 只留给 product imagery。
- 不要使用 gradients 作为 decorative backgrounds。
- 不要将 body copy 设置为 weight 500。
- 不要给 full-bleed tiles 加 rounded corner。

## Responsive Behavior

| Width | Key Changes |
|---|---|
| ≤ 419px | Single-column tiles；hero typography 降到 28px |
| 420–640px | Single-column stack；hero h1 降到 34px |
| 641–735px | Tiles 使用更紧的 padding（48px） |
| 736–833px | Global nav 折叠为 hamburger |
| 834–1023px | 3-column grids 变为 2-column |
| 1024–1068px | Product tiles 使用 2/3 width |
| 1069–1440px | Full layout；store grids 为 4–5 column |
| ≥ 1441px | Content 锁定在 1440px |

### Touch Targets
- Minimum 44 × 44px。

## Iteration Guide

1. 一次只关注一个 component。
2. 始终使用 token refs，不要 inline hex。
3. Display headlines 保持 600 + negative letter-spacing。Body 保持 400 at 17px。
4. 单一 drop-shadow 只给 product photography 使用。
5. 拿不准 emphasis 时，优先切换 surface（light → dark tile），不要先添加 chrome。
