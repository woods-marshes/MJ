# MJ

在任意应用的输入框中输入 `mj` 并发送，屏幕上会全屏播放一段**透明背景的蜘蛛侠动画**（带音效）。
灵感来自 iOS 越狱插件 [Tiktok-MJ-for-Wechat](https://github.com/qiu7c/Tiktok-MJ-for-Wechat)，本项目的目标是在 **Android 无 Root** 设备上用无障碍服务实现同样的效果。

## 演示效果

本仓库暂未录制演示动图。原项目 README 中附有两张开启插件后的实拍截图（无 GIF），可前往 [Tiktok-MJ-for-Wechat 的 README](https://github.com/qiu7c/Tiktok-MJ-for-Wechat#readme) 直观查看效果。

也可以安装后直接使用主界面的「测试动画」按钮，在应用内即可预览动画与音效。

## 工作原理

无障碍服务监听输入与点击事件，检测到"输入框内容为 `mj` 且发生发送动作"时，通过 `WindowManager` 挂载一个 `TYPE_ACCESSIBILITY_OVERLAY` 全屏透明窗口播放动画（该窗口类型无需"显示在其他应用上层"权限）。

透明动画的实现：双画面视频（左半边灰度蒙版 + 右半边彩色画面）由 `MediaCodec` 逐帧解码，OpenGL 片元着色器把左半边灰度作为 Alpha、右半边作为颜色实时合成，`SurfaceView` 以透明格式置顶叠加——GPU 渲染，无需预处理素材。

"发送"的判定分三层：

| 层 | 信号 | 覆盖场景 |
|---|---|---|
| 1. 点击直击 | 点击事件的文本/描述/子树/id 匹配"发送/Send" | 微信等 View 体系应用 |
| 2a. 清空特征 | 发送后输入框被一步清空或露出 hint | Google Messages 等 Compose 应用 |
| 2b. 窗口变化 | 武装包名内持有焦点的 mj 输入框消失 | B 站评论弹窗、知乎/QQ 搜索等"发送即销毁输入框"场景 |

防误触机制：

- 焦点窗口切到**其他应用**（切走 / HOME / 下拉通知栏）→ 保持武装但**不触发**；切回来后输入框还在的话可以继续发送
- 离开过武装应用后，"输入框消失"不再推断为发送（抖音等应用切走时会自行关闭输入框），重新输入 mj 即重新武装
- 武装状态 15 秒自动过期；逐字删除、继续输入、拼音上屏/粘贴都不会触发

## 动画素材来源

动画视频素材取自开源项目 [Tiktok-MJ-for-Wechat](https://github.com/qiu7c/Tiktok-MJ-for-Wechat)（`Assets/source/` 下的双画面蒙版源文件），**蜘蛛侠形象及相关版权归 Marvel / Disney 所有**，素材仅用于学习交流。

## 构建

依赖：JDK 17+，Android Studio 或 Android SDK（compileSdk 37）

```bash
./gradlew assembleDebug   # 调试包：app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease # 发布包（无签名配置时回退 debug 签名，可直接安装）
```

## 使用

1. 安装 APK，首次打开会显示免责声明，确认后不再弹出
2. 点击 **去系统设置开启** 授予无障碍服务
3. （MIUI / HyperOS 建议）点击 **自启动管理 / 省电策略** 两个按钮完成保活设置，避免无障碍服务被系统回收
4. 在任意应用（微信、QQ、B 站、抖音、知乎…）的输入框输入 `mj` 并发送
5. 主界面 **测试动画** 按钮可在不依赖其他应用的情况下验证动画与音效，**动画音效** 开关控制播放时是否带声音

## 免责声明

本应用通过无障碍服务监听输入与点击事件，检测到发送 `mj` 后，在本地全屏播放蜘蛛侠动画与音效。

- 完全本地运行：不联网、不上传、不收集任何数据
- 不修改系统、不破坏设备
- 请确认仅从 GitHub 官方仓库下载：[woods-marshes/MJ](https://github.com/woods-marshes/MJ)
- 仅供学习交流使用，请于下载后 24 小时内删除
- 蜘蛛侠形象及相关版权归 Marvel / Disney 所有，动画素材来自开源项目 [Tiktok-MJ-for-Wechat](https://github.com/qiu7c/Tiktok-MJ-for-Wechat)

## 已知限制

- 离开过武装应用后再回来发送，需要重新输入一遍 `mj`（应用切走时可能自行销毁输入框，无法区分是否发送）
- 输入 mj 后全选删除 / 粘贴覆盖其他内容，会被视为放弃或发送（前者不触发、后者可能误触发）
- 应用内点链接拉起**外部浏览器**执行搜索不会触发（包名不同）
- 纯游戏 / 自绘无语义界面无法检测

## CI/CD

GitHub Actions（`.github/workflows/ci.yml`）：

- push 到 `main` / PR：编译 debug APK + 单元测试，APK 上传为 Artifact
- 推送 `v*` 标签：编译 release APK 并自动创建 GitHub Release

可选正式签名：在仓库 Secrets 中配置 `MJ_KEYSTORE_BASE64`（jks 的 base64）、`MJ_STORE_PASSWORD`、`MJ_KEY_ALIAS`、`MJ_KEY_PASSWORD`；未配置时使用 debug 签名（可直接安装）。

## 隐私

本应用通过无障碍服务接收的事件（文本内容、点击的节点、窗口变化）**仅在设备内存中用于本地判定**，不落盘、不上传、不联网。

## License

[MIT](LICENSE)
