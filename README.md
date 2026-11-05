# DangoMusic City Echoes

DangoMusic 的**附属 mod（addon）**，把「都市回响 / City Echoes」这套 3D 歌词演出从本体里拆出来独立交付。

存在的理由是解耦：本体还在活跃更新，演出功能原本合并在它的 jar 里，本体每次重构都可能把演出弄坏。拆成 addon 之后，两者可以各自升级，只要注入点对得上就能继续工作。

- 本体：<https://github.com/Sorihiko/DangoMusic>
- 演出效果出处：<https://github.com/Celine258/idea-from-limbus-company-dancing-lyrics>

## 它做什么

在原版 3D 歌词之外，新增**一种歌词呈现方式**：歌词逐字漂浮在世界里，带波浪、跳跃、倾斜、乱序退场、演唱强调等演出，可以配深辉光、翻译行、彩虹配色。

它**不修改**本体的播放器、歌单、音源等任何既有功能——只在设置页里多出一个标签页。

## 运行要求

| 项目 | 要求 |
|---|---|
| Minecraft | 1.20.1（Forge 47.x）或 1.21.1（NeoForge 21.1.x） |
| Java | 17（1.20.1）/ 21（1.21.1） |
| DangoMusic | **必须** 1.7.0 或更高，版本号需与加载器对应 |
| 客户端 | 纯客户端 mod，服务端不需要安装 |

## 安装

1. 先装好 DangoMusic 本体；
2. 把 `DangoMusic-CityEchoes-<version>+<mc>-<loader>.jar` 放进 `mods/`；
3. 进游戏打开 DangoMusic 的音乐界面 → 设置，标签栏会多出第三个「City Echoes / 都市回响」。

所有配置项都在那个标签页里，分组头带模块总开关——关掉即停止渲染，行为和内置模块一致。配置写在本体的 `config/dangomusic/config.json` 里，和本体其它模块同一个文件。

## 实现方式（也解释了它的脆弱点）

addon 不改本体源码，而是用 SpongePowered Mixin 在运行期注入四个点：

| 目标类 | 注入内容 |
|---|---|
| `DangoMusic#init` | 在本体扫描模块之前注册 `CityEchoesModule` |
| `SettingsPage#buildSettings` | 在返回的两个标签页后面追加第三个 |
| `SettingsPage#drawTabs` | 把两个标签换成三个 |
| `SettingsPage#<init>` | 往下拉框文案表里塞入本模块的选项 |
| `I18n#getChinese` | 补充中文词条 |
| `LyricsModule` | 模块启用时接管原版 3D 歌词与 HUD 的渲染 |

代价很明确：这些全是本体的 `internal` / package-private 实现。本体哪天重构了 `SettingsPage`，注入就会失效。

因此所有注入都写了 `require = 0`——**注入失败只会让第三个标签不出现，不会因为 Mixin apply 失败让游戏崩溃**。启动后在 `logs/latest.log` 里搜 `[CityEchoes]` 能看到每个注入点的实际状态。

## 构建

需要 **JDK 21**（Loom 编译 1.21.1 目标用；编译 1.20.1 目标时它会把 toolchain 降到 17，所以只装 21 即可）和 **Gradle 8.11**——仓库自带 wrapper，不用自己装，9.x 与 Architectury Loom 1.13 不兼容。

仓库**不包含**依赖 jar（见 `.gitignore`），克隆后需要自己准备 `libs/`：

```
libs/
├── DangoMusic-1.7.3+1.20.1-forge.jar      # 从 DangoMusic Releases 下载
├── DangoMusic-1.7.3+1.21.1-neoforge.jar   # 同上，加载器对应
└── mixin-patched-0.8.5.12.jar             # SpongePowered Mixin 0.8.5（patched）
```

然后直接构建（wrapper 会拉 **Gradle 8.11**，9.x 与 Architectury Loom 1.13 不兼容）：

```bash
./gradlew build            # 两个目标一起出包
./gradlew :1.20.1-forge:build
```

产物在 `versions/<target>/build/libs/`，`remapJar` 的输出就是可用的 jar。

`init.gradle.kts` 是给国内网络用的：Loom 会用 `exclusiveContent` 把 Forge / Mojang 仓库锁在官方源，普通 `repositories` 加镜像会被绕过，所以它改为在仓库注册后重写 URL 指向 BMCLAPI。直连正常时不需要加：

```bash
./gradlew build --init-script init.gradle.kts
```

首次构建会在 `.gradle/` 下生成缓存，两个目标合计约需数分钟（Loom 要下载并 remap Minecraft 与 NeoForge）。

## 目录

```
common/src/main/kotlin/me/cityechoes/
├── CityEchoes.kt              演出核心：逐字母的运动学、波浪、倾斜、退场
├── DangoBridge.kt             访问本体公开 API 的桥（含 shaded 类型适配）
├── mods/CityEchoesModule.kt   模块本体：44 个 @ModuleValue 配置项 + 两处渲染
├── ui/SettingsModel.kt        把 @ModuleValue 反射成设置页的分组/行模型
├── ui/DangoSettingsBridge.kt  把上面的模型翻译成本体的 Group/Row（反射构造）
├── ui/DangoSettingsZh.kt      中文词条
├── ui/CityLog.kt              统一日志出口
└── mixin/                     4 个注入点
versions/1.20.1-forge/         Forge 入口 + mods.toml + mixin 配置
versions/1.21.1-neoforge/      NeoForge 入口 + neoforge.mods.toml + mixin 配置
```

## 许可证

**GNU General Public License v3.0（GPL-3.0-only）**，全文见 [LICENSE](LICENSE)。

这不是随便选的：本 addon 在运行期通过 Mixin **改写 DangoMusic（GPL-3.0）的字节码**，并在同一 JVM 内链接它的类，按 GPLv3 §5 属于「基于该程序的作品」，分发时必须整体按 GPLv3 授权。

因此：**你可以自由使用、修改、再分发，但如果分发二进制，必须同时提供完整源码。**

第三方组件、版权归属与各自的许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 致谢

- [Sorihiko/DangoMusic](https://github.com/Sorihiko/DangoMusic) — 本体，本 addon 的宿主
- [Celine258/idea-from-limbus-company-dancing-lyrics](https://github.com/Celine258/idea-from-limbus-company-dancing-lyrics) — City Echoes 演出效果的原作

本项目与 Mojang、Microsoft 无关联。"Minecraft" 是 Mojang Synergies AB 的商标。
