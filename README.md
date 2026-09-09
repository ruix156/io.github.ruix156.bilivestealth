# BiLiveStealth (B站直播隐身观看)

> 低调看播, 无迹可寻。

一个用于 B 站 (哔哩哔哩) 直播间的 [LSPosed](https://github.com/LSPosed/LSPosed) 模块。
看直播时不留痕迹: 不触发进场特效与欢迎消息、不计入在线人数与榜单,
同时保持登录态弹幕连接 —— **弹幕昵称不再被打码为 `***`**。

本项目基于 [lzghzr/BiLiveInvisible](https://github.com/lzghzr/BiLiveInvisible) (GPL-3.0) 重构扩展,
增加了直播间悬浮实时开关、黑/白名单、"每场首进可见"等能力。

## 功能特性

| 功能 | 说明 | 默认 |
| --- | --- | --- |
| 隐身进场 | 进场上报的 `room_id` 被替换为伪装房间号 (默认 510, 可改), 不触发进场特效/欢迎弹幕 | 开 |
| 匿名心跳 | 房间心跳请求移除 `access_key`, 不计入在线人数/亲密度/高能榜 | 开 |
| 游客弹幕连接 | 弹幕 WebSocket 匿名化 (与原版一致, **会导致昵称打码**, 慎用) | 关 |
| 过滤进场消息 | 本地隐藏他人的进场提示/特效 (实验性) | 关 |
| 黑名单/白名单 | 按房间生效: 黑名单模式 (全部隐身, 列表房间除外) / 白名单模式 (仅列表房间隐身) | 黑名单 |
| 每场首进可见 | 指定直播间一场直播的**首次入场保持正常**, 之后同场次再进均隐身, 进出痕迹更自然 | 关 (需手动添加房间) |
| 悬浮实时开关 | 直播间内悬浮球, 点击展开暗色面板, 所有开关即时生效并持久化 | - |

## 弹幕昵称打码问题 (与原版的主要差异)

B 站对**游客**弹幕连接有隐私策略: 连接约 5 分钟后会下发 `LOG_IN_NOTICE`
("为保护用户隐私, 未注册登陆用户将无法查看他人昵称"), 之后所有弹幕发送者昵称打码为 `***`、UID 归 0。

原版模块移除了弹幕服务器请求的 `access_key` 并将认证包 `uid` 置 0,
导致弹幕连接变成游客身份 —— 这正是"看一会就全是星号"的原因。

本模块的默认策略:

- **弹幕连接保持真实登录态** (保留 `access_key` 与真实 `uid`) → 昵称、牌子正常显示
- 隐身仅作用于进场上报与心跳 → 不产生可见痕迹

> 代价: 若开启"游客弹幕连接"(默认关), 将回到原版行为并出现昵称打码。

## 使用方法

1. 安装并启用模块 (需要 [LSPosed](https://github.com/LSPosed/LSPosed), 新版 Xposed API)
2. 作用域勾选 **哔哩哔哩** (国内版 `tv.danmaku.bili` / 国际版 `com.bilibili.app.in` 均支持)
3. 进入任意直播间, 屏幕右侧可见半透明 **「隐」** 悬浮球 (可拖动)
4. 点击悬浮球展开设置面板 (暗色主题):
   - 各功能开关即时生效并持久化, 重启 B 站后保留
   - `+白名单 / +黑名单 / 移出名单` 针对**当前房间**操作
   - `+首进可见` 将当前房间加入"每场首进可见"名单; `重置首进` 可手动重置
   - 可修改进场上报的伪装房间号 (默认 510)
5. 修改设置后无需重启, 下一次相关请求 (心跳/进场上报) 即按新设置执行

### 关于 "每场首进可见"

- 对名单内的房间, 每场直播的首次入场按正常流程上报 (主播能看到你进场一次), 同场次内再进入均隐身
- 场次判定: 优先依据接口响应中的开播时间 (`live_time`), 识别成功则跨 App 重启也准确;
  识别不到时按 App 进程周期近似 (重启 B 站视为新场次)
- 名单支持在面板内**直接管理**: 条目按**主播昵称**显示 (模块自动从弹幕/房间信息中学习 昵称 映射),
  点击条目旁的「删」即可移除, 无需进入对应直播间; 昵称未知时显示房间号

## 兼容性

- 系统: Android 9 (API 28) 及以上, arm64-v8a
- 框架: LSPosed (libxposed 新 API, minApiVersion 101)
- 目标应用: 哔哩哔哩国内版 / 国际版近期版本
- 核心类通过 [DexKit](https://github.com/LuckyPray/DexKit) 在运行时按特征查找并按版本号缓存,
  B 站小版本更新一般无需跟进适配; 若大版本更新后失效, 强停 B 站后重新进入即可重新查找

## 从源码构建

要求: JDK 17、Android SDK (compileSdk 35)。

```bash
./gradlew assembleRelease
```

产物: `app/build/outputs/apk/release/BiLiveStealth_v<version>.apk`

### 签名说明

构建脚本会查找项目根目录的 `keystore.properties` (格式如下), 存在则使用正式签名, 否则回退 debug 签名:

```properties
storeFile=keystore.jks
storePassword=xxx
keyAlias=xxx
keyPassword=xxx
```

> 注意: 同一模块的多次发布必须使用相同签名, 否则用户无法覆盖安装更新。

### CI 自动构建

仓库自带 [GitHub Actions 工作流](.github/workflows/release.yml):

- 推送任意 tag (建议 `v1.0.0` 格式) 即自动构建并创建 GitHub Release
- 在仓库 `Settings → Secrets → Actions` 配置以下 secrets 即可使用正式签名 (可选):
  - `SIGNING_KEYSTORE_BASE64`: keystore 的 base64 (`base64 -w0 keystore.jks`)
  - `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD`

## 提交到 LSPosed 模块仓库 (Xposed-Modules-Repo)

模块仓库 (modules.lsposed.org) 的收录要求与步骤:

1. 在 GitHub 创建仓库, **仓库名必须为模块包名**: `io.github.ruix156.bilivestealth`,
   仓库 description 填模块名 (如 "B站直播隐身观看")
2. 在 Releases 发布至少一个版本: 上传 APK 作为资产,
   tag 名格式为 `版本号-版本名` (如 `1-1.0.0`; 若先创建 release 再上传 APK, 机器人会自动修正 tag)
3. 在该仓库根目录放置:
   - `README.md` (本文件, 完整说明)
   - `SUMMARY.md` (模块摘要, 显示在模块仓库首页, 本仓库已提供)
4. 到 [Xposed-Modules-Repo/submission](https://github.com/Xposed-Modules-Repo/submission)
   提交 issue, 标题 `[submission] io.github.ruix156.bilivestealth`, 机器人会自动创建模块仓库并邀请你为管理员
5. 将 README/SUMMARY/Release 同步到自动创建的 `Xposed-Modules-Repo/io.github.ruix156.bilivestealth` 仓库即可

## 已知限制

- 若你在某房间开通了**大航海**, 舰长列表由服务端生成, 任何客户端手段都无法隐藏
- 匿名心跳开启期间, 该房间**不涨粉丝团亲密度** (在意可关闭"匿名心跳")
- "过滤进场消息"与"主播 UID 学习"依赖 fastjson 解析层, B 站大版本更新后可能失效 (不影响其他功能)
- 弹幕连接为登录态, B 站服务端理论上可见你的连接 (但直播间内无任何可见展示)

## 免责声明

本项目仅供学习与研究, 请勿用于任何商业或违规用途。
使用本模块产生的一切后果由使用者自行承担。
使用本模块即表示你已知晓并接受: 修改客户端请求可能违反 B 站用户协议, 存在账号被处罚的风险。

## 致谢

- [lzghzr/BiLiveInvisible](https://github.com/lzghzr/BiLiveInvisible) — 隐身机制与思路来源
- [libxposed/api](https://github.com/libxposed/api) — 新一代 Xposed API
- [LuckyPray/DexKit](https://github.com/LuckyPray/DexKit) — 运行时类查找

## 许可证

[GPL-3.0](LICENSE) © 基于 BiLiveInvisible 修改
