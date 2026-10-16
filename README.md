# TerraLeveler

TerraLeveler 是一个 Minecraft Fabric **客户端**模组：控制你自己的玩家，在生存模式下把一片地形平整到指定高度，或者给一个区域补上照明。它只用正常的走路、挖掘、放置和开箱操作，每一步都等服务器确认后才继续，可以随时暂停、恢复和停止。

*TerraLeveler is a client-side Fabric mod for Minecraft 26.3 that drives your own player to level terrain to a target height (rectangle or a whole island) and to light up an area with torches. The in-game text is currently Chinese only.*

## 功能

**平地机**

- 选区：手拿普通苹果，左键设角点一、右键设角点二；或开启“自动识别岛屿范围”，以玩家所在陆地为起点，按周围连续水面识别整座岛（大岛会自动分区施工）。
- 把目标高度以上的方块挖掉，再在目标高度铺一层泥土 / 草方块地表；不会灌满地下空洞或流体。
- 自动寻路，必要时搭临时脚手或临时柱子到达高处，施工结束时回收；脚手数量受“脚手预算”限制，并记录在本地账本里，重进游戏后可以接着回收。
- 处理流体：填掉目标高度以上的水，尽量先找到并堵住选区外流进来的源头。
- 背包里有水桶时，从高处下来用水桶落地。
- 遇到箱子、潜影盒、熔炉等方块实体时可以暂停报告（“保护容器和方块实体”）。

**区域照明**

- 用苹果选两个角点框出三维区域，在方块光不高于阈值的可放置地面上放火把，火把可从背包里的潜影盒取出。
- 施工中会反击攻击范围内的敌对生物、自动进食、掉进水或岩浆后自救，血量过低时暂停并在恢复后继续。

**通用**

- 工具耐久到保护值时换工具，可以从指定名称的“工具盒”潜影盒里取替换工具。
- 挖到的泥土等材料超过上限时存进背包里的潜影盒，铺地缺土时再取出（通过 QuickShulker 开盒，界面不会弹出）。
- 自动反击、自动进食、清除挡路的普通动物（宠物、改名或拴住的动物、村民等不会被攻击）。
- 状态栏显示当前状态和进度百分比；暂停时在聊天栏提示原因，需要处理时有提示音。
- 可选的诊断日志，遇到问题时方便排查。

## 支持版本与依赖

本分支（`mc-26.3`）面向 Minecraft 26.3；Minecraft 26.1.2 的版本在 `main` 分支。两个版本的功能相同。

| 项目 | 要求 |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5 或更高 |
| Java | 25 或更高 |
| [Fabric API](https://github.com/FabricMC/fabric) | 必需（开发时使用 0.163.0+26.3） |
| [MaLiLib](https://github.com/maruohon/malilib) | 必需，0.30.2 或更高（设置界面） |
| [Quick Shulker](https://github.com/MoRanpcy/quickshulker) | 必需，26.3 版（开发时使用 3.1.1-26.3）（打开工具盒、材料盒、火把盒） |
| [Mod Menu](https://github.com/TerraformersMC/ModMenu) | 可选，在模组列表里提供“配置”按钮（开发时使用 21.0.0） |

TerraLeveler 只需要装在客户端。联机时如果要用潜影盒相关功能，服务器也需要安装 QuickShulker；服务器没有时，多余的材料会直接丢弃，缺土时改为挖选区内高于目标高度的土来补充。

## 安装

1. 安装 Fabric Loader（Minecraft 26.3）。
2. 把 TerraLeveler 的 JAR 和上表中的必需前置放进游戏目录的 `mods` 文件夹。
3. 启动游戏。

## 使用

### 打开设置

- 打开 malilib 的配置菜单（malilib 默认快捷键 `A + C`），在顶部选择 TerraLeveler；
- 或者在 Mod Menu 的模组列表里点 TerraLeveler 的“配置”按钮。

在设置里选择工作模式（平地机 / 区域照明），调整各项参数。改动会立即保存。

### 绑定快捷键

TerraLeveler 的快捷键**默认全部不绑定**，第一次使用前请在设置里绑定：

| 快捷键 | 作用 |
|---|---|
| 开始 / 停止施工 | 空闲时开始，施工中或暂停中停止 |
| 暂停 / 恢复 | 暂停或继续当前施工 |
| 记录平地目标高度 | 把脚下完整方块的高度记为目标地面高度（也可以在设置里直接填写 Y） |
| 记录保留物品 | 把主手物品加入或移出自定义保留清单，清单里的物品不会被丢弃 |

聊天提示里会显示你实际绑定的按键。

### 平地（矩形选区）

1. 设置里选择“平地机”模式。
2. 站到想要的地面高度上，按“记录平地目标高度”，或在设置里填写目标 Y。
3. 主手拿普通苹果，左键点一个角，右键点对角，确定矩形选区。
4. 按“开始 / 停止施工”。

### 平地（自动识别岛屿）

1. 在设置里开启“自动识别岛屿范围”，按需要调整岸线保留、是否铺平岛内水塘等选项。
2. 记录目标高度后站在岛上，按“开始 / 停止施工”：第一次显示识别结果预览，再按一次确认开工。

开启岛屿模式时苹果选点不起作用，以岛屿范围为准。

### 区域照明

1. 设置里选择“区域照明”模式。
2. 用苹果左右键设置三维区域的两个角点，按“开始 / 停止施工”。

### 文件位置

以下文件都在游戏目录下（使用启动器的版本隔离时为 `.minecraft/versions/<版本名>/`）：

- `config/terraleveler.json`：设置
- `config/terraleveler-scaffold-ledgers.json`：临时脚手账本（请勿手动清空，否则遗留的脚手无法自动回收）
- `config/terraleveler-island-survey/`：岛屿识别时记录的地形数据
- `logs/terraleveler-diagnostic.log`：诊断日志（可在设置中用“诊断日志”开关；设置里该项的说明会显示本机的完整路径）

从旧名称 WETA（WorldEater Trench Assistant）升级时，旧的 `weta.json` 等文件会在首次启动时自动复制为新名称；请删除 `mods` 里旧的 `worldeater-trench-assistant-*.jar`，否则新旧两个模组会同时运行。

### 注意事项

- 自动施工会频繁转动视角、移动和破坏方块，在开启反作弊的服务器上可能被判定违规。请先确认服务器规则。
- 施工时普通苹果保留给选区使用，不会被自动吃掉。
- 施工过程中手动移动会被视为人工接管并暂停。

## 构建

需要 JDK 25。在项目根目录运行：

```bash
./gradlew directTest build -x test
```

Windows 下使用 `gradlew.bat directTest build -x test`。生成的模组 JAR 位于 `build/libs/terraleveler-<版本>.jar`（同目录下的 `-sources.jar` 是源码包）。

`directTest` 先初始化 Minecraft 的方块和物品注册表，再用 JUnit Platform Console Launcher 运行全部测试。Gradle 标准的 `test` 任务不做这一步，部分测试会因 `Not bootstrapped` 失败，所以构建时用 `-x test` 跳过它。

项目结构：

```text
src/client/java/      客户端源码（cn.terraleveler）
src/client/resources/ Mixin 配置
src/main/resources/   fabric.mod.json 与图标
src/test/java/        单元测试
libs/                 编译期依赖（Mod Menu，仅编译使用，不打包进模组）
tools/Make-Icon.ps1   生成模组图标的 PowerShell 脚本
```

版本号在 `gradle.properties` 的 `mod_version` 中设置。

## 许可证

TerraLeveler 以 [MIT 许可证](LICENSE) 发布，Copyright (c) 2026 cys。

第三方组件：

- `libs/modmenu-21.0.0.jar` 是 [Mod Menu](https://github.com/TerraformersMC/ModMenu)（TerraformersMC，MIT 许可证，许可证文本包含在该 JAR 内的 `LICENSE_modmenu`），仅用于编译可选的 Mod Menu 集成，不包含在 TerraLeveler 的发布 JAR 中。
- Fabric API（Apache-2.0）、MaLiLib（LGPLv3）、Quick Shulker（MIT）是运行时前置，由玩家自行安装，不随本项目分发。

更新记录见 [CHANGELOG.md](CHANGELOG.md)。
