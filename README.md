# YK 象棋

Android 中国象棋分析器：本地棋局、UCI 引擎、`bhobk` 开局库，以及与第三方棋盘的屏幕连线。

核心目标是**极简与高效**。运行时局面不依赖 FEN 或字符串着法；FEN、ICCS 和 PGN 只存在于协议、导入导出边界。

## 位棋盘核心

核心位于 [`core`](app/src/main/java/com/yk/xiangqi/core)。`Board` 用 128 位位棋盘（两个 `long`）容纳 90 个交叉点，归属位板与各兵种位板独立保存。每步后镜像棋盘，走法生成始终面向当前行棋方。

`Position`、`Move`、`GameState` 是不可变值，UI 通过 `GameAction` 归约状态。规则覆盖九宫、河界、马腿、象眼、炮架、将帅照面、将军、应将、将死和困毙，并提供：

- `computeSimpleGameResult()`：只判断基础终局，供 UI 与搜索快路径使用。
- `computeGameResult()`：额外处理重复局面、长将/长捉、六十回合规则与子力不足和棋。

当前不是通用棋类框架。若要支持 ChessDB、XQB 或其他开局库格式，应基于真实数据契约重新设计，而不是在现有 `bhobk` 实现上叠加抽象。棋谱仅支持标准 ICCS PGN，包含 `{}` 注释。

## 完整 UCI 协议

[`UciEngine`](app/src/main/java/com/yk/xiangqi/engine/UciEngine.java) 管理单个串行 UCI 子进程：

1. `uci` 握手并读取全部引擎 Option。
2. 写入 `Threads`、`Hash`、`MultiPV` 及其他 Option，使用 `isready` 确认生效。
3. 使用 `position fen … moves …` 同步局面，支持完整 `go` 参数、`stop` 与 `bestmove`。
4. 每 200ms 只向 UI 投影最新一条完整 `info`，避免引擎输出造成 UI 压力。

支持内置或导入的 `arm64-v8a` UCI 引擎和可选 NNUE 网络。

## 开局库与连线

`bhobk` 开局库使用固定 Zobrist 表查询正向与镜像键，并合并候选。对 `vkey` 为 SQLite `REAL` 或缺失有效索引的坏库，可建立同名 `.idx` 旁路索引；索引只保存原表 `id` 与规范化键，原库始终保持只读。

Android 连线流程：

```text
MediaProjection / ImageReader
  → 最后一帧稳定等待
  → RGBA 双线性采样与归一化
  → ONNX CPU 推理
  → ObservedBoard
  → LinkState 纯归约
  → 同步局面 / 外部走子 / 失步提示
```

框选棋盘的 9×10 交叉点中心后可微调。预处理复用采样表、ROI 和模型输入缓冲，不创建 `Bitmap` 或单格对象。自动走子使用无障碍服务发送两次独立点击，单击持续时间与两击等待可配置。

## 构建

要求 Android 9（API 28）及以上的 `arm64-v8a` 设备，以及 JDK 17 / Android SDK。

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:perftTest
```

项目面向本地侧载，不面向 Google Play；Release 当前使用默认 debug keystore，正式发布前应替换为独立发布签名。

## 许可证

项目代码与自有资源（包括 `piece_classifier.onnx`）采用 [GNU GPL v3.0](LICENSE)。Pikafish 与其 NNUE 权重按各自许可证处理；特别是 `pikafish.nnue` 的非商业限制不随 GPL 改变。

## 交流
qq群: 1094907554