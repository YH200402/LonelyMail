# LonelyMail

一个给 Minecraft 服务器用的**离线邮件系统**插件：玩家离线时也能收到物品，上线后在游戏内从邮箱领取。

> 为 CatServer 1.12.2 (Forge + Bukkit) 编写，Java 8。

## 它能解决什么

服务器里经常有"东西发不出去"的场景：

- 活动奖励、补偿、赔偿，玩家当时不在线
- 世界 BOSS / 空投的奖励，最后一击的人刚好下线了
- 删档/转档时的福利发放
- 管理员想给某个玩家塞点东西，人不在

发邮件就完事了。人上线后收到提示，打开邮箱领取，背包满也不会吞。

## 功能

- **离线收件**：玩家不在线也能寄，数据直接落盘
- **GUI 邮箱**：54 格界面，左键领取、右键看附言、一键领取
- **可视化写信**（管理员）：
  1. 聊天栏输入正文
  2. 物品编辑界面：把要寄的物品拖进去
  3. 选收件人：点头像 / 搜索玩家名 / 一键群发（二次确认）
- **未读提醒**：上线自动提示「你有 N 封邮件」
- **背包保护**：领不完的部分留在邮箱，不会掉地上
- **自动过期**：默认保留 30 天，可配置
- **API / 命令**：其他插件或脚本可以直接寄信

## 界面

```
┌─────────────────────────────┐
│  邮件列表            ✦ = 未读 │
│  每封显示：编号 / 时间 / 寄件人 │
│           附言 / 附件件数      │
├─────────────────────────────┤
│ [关闭]   [一键领取]   [邮箱规则] │
└─────────────────────────────┘
```

管理员在邮箱界面还会多一个「写邮件」按钮（第 48 格）。

## 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/mail` | `lonelymail.use`（默认所有人） | 打开邮箱 |
| `/mail compose` | `lonelymail.admin`（默认 OP） | 可视化写信 |
| `/mail send <玩家> <物品键> <数量> [附言]` | `lonelymail.admin` | 命令行寄 SX 物品 / 原版材质 |
| `/mail senditem <玩家> [附言]` | `lonelymail.admin` | 寄出手上的物品 |
| `/mail list [玩家]` | `lonelymail.admin` | 查看待领取邮件 |
| `/mail clear <玩家>` | `lonelymail.admin` | 清空邮箱 |

玩家默认只有收信权限，**看不到写邮件按钮**，也拦得住控制台寄信。

## 权限

```yaml
lonelymail.use:    # 收信、打开邮箱，default: true
lonelymail.admin:  # 写信、群发、查/清邮箱，default: op
```

## 下载

编译好的 jar（23 KB），两种方式任选：

- **Releases 页**（推荐）：[LonelyMail v1.0.0](https://github.com/YH200402/LonelyMail/releases/latest) → 点 `LonelyMail-0.1.0.jar`
- **直接链接**：<https://github.com/YH200402/LonelyMail/releases/download/v1.0.0/LonelyMail-0.1.0.jar>
- 仓库内备份：[dist/LonelyMail-0.1.0.jar](dist/LonelyMail-0.1.0.jar)（点页面右上角 Download raw file）

## 安装

1. 把 jar 丢进 `plugins/`
2. 启动服务器，生成 `plugins/LonelyMail/config.yml`
3. 重载或重启

```yaml
# config.yml
gui-title: '&8「 &b邮箱 &8」&7 你的信件'
expire-days: 30     # 邮件保留天数
max-mails: 60       # 每人最多保留几封
```

## 数据

```
plugins/LonelyMail/data/<玩家UUID>.yml
```

每人的邮件存在自己的文件里，离线写入，好备份、好迁移。

> 改 `data/*.yml` 前请先停服，否则会被内存数据覆盖。

## 其他插件调用

```java
LonelyMail mail = (LonelyMail) Bukkit.getPluginManager().getPlugin("LonelyMail");
mail.send(player.getUniqueId(), itemStack, "世界事件", "空投奖励");
```

或者派发控制台命令：

```
mail send <玩家> <物品键> <数量> <附言>
```

## 从源码构建

需要 JDK 8+ 和一份 CatServer / Spigot 1.12.2 的 jar 作为 classpath：

```bash
mkdir -p build/classes
javac -source 8 -target 8 -encoding UTF-8 \
  -cp "/path/to/CatServer.jar" \
  -d build/classes \
  $(find src -name '*.java')
cp src/plugin.yml src/config.yml build/classes/
cd build/classes && jar cf ../../LonelyMail.jar .
```

## 兼容性

- Minecraft 1.12.2（CatServer / Spigot / Paper）
- Java 8
- 不依赖任何第三方库，不注册额外命令通道
- 已在实际服务器上跑了完整流程（离线寄信 → 上线提示 → 领取 → 背包满保护）

## License

MIT
