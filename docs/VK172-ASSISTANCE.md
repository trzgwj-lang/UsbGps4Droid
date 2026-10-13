# VK-172 辅助定位测试版

本 fork 在原 UsbGps4Droid 上增加 u-blox 7 GPS 辅助数据传送，无需 Root。
这是开发测试版；协议测试通过不代表已在实体 VK-172 上验证搜星提速。

界面、设置、通知、权限提示和辅助定位事件已提供完整简体中文，随安卓系统语言自动切换。卫星原始 NMEA 数据和厂商返回的型号保持原样。

## 安装和使用

1. 安装 debug APK。本测试版包名以 `.vk172` 结尾，可以和原版并存；请停止原版服务，避免争用 USB。
2. 在安卓开发者选项中将测试版设为「模拟位置信息应用」，授予定位和 USB 访问权限。
3. 在应用设置中选择 VK-172，停止 GPS 服务后启用「VK-172 / u-blox 7 辅助定位 → 启用 GPS 辅助定位」。
4. 确保安卓日期和时间正确。只有安卓已可靠校时，才启用「向 GPS 发送安卓时间」。它不会修改安卓系统时间。
5. 如有有效的 **旧版 AssistNow Online token**，填入「旧版 AssistNow 访问凭证」，并启用「下载新的 GPS 星历」。不要把 token 发到 GitHub issue、提交记录或聊天里。
6. 启动 GPS。日志中的 `[辅助定位 +…秒]`（英文系统显示 `[Assist +…s]`） 显示版本检测、星历传送、读取到有效星历以及首次有效位置。设置页显示最近一次事件。修改辅助选项后重新启动 GPS 服务。

没有 token 时，仍可正常使用 GPS、时间辅助和短期星历缓存。在线功能会明确提示跳过，不会伪装下载成功。

## 功能与限制

- 每次 USB 连接通过 `UBX-MON-VER` 查询硬件/固件；仅硬件版本 `00070000` 的 u-blox 7 启用注入。最多探测三次，不回应或不匹配则继续普通定位。
- 同一 USB 输入流按校验和拆分 UBX 和 NMEA；二进制消息不会交给文本定位解析器。
- 可选发送新生成的 `UBX-AID-INI` UTC 时间，声明 10 秒不确定度。在线服务的时间消息会另外扩大不确定度，覆盖下载和传送延迟。此版不自动断言手机时间可信，也不注入可能已经移动过的旧位置。
- 向旧版服务请求 `gnss=gps;format=aid;datatype=eph,alm,aux`。仅接受校验正确、长度正确的 AID-INI / EPH / ALM / HUI；HTML、错误文本、MGA、新版本配置/重置命令一律拒绝。
- HTTPS 请求仅发往固定 u-blox 官方域名，不跟随重定向；限制超时和响应大小。不记录含 token 的 URL、异常详情或服务器响应正文。
- 在当前连接首次定位前尝试一次在线请求。重连也受 5 分钟节流限制；失败后保持正常定位。持续连线不会重复请求；需要重启服务才能再次尝试。
- 星历仅在轨道参考时刻 TOE 前后 90 分钟内使用，并检查卫星健康状态和 IODE/IODC 一致性。缓存接收年龄也不得超过 90 分钟；时间回退时拒绝回传。时间检查使用当前 GPS-UTC 差 18 秒；未来闰秒变化须更新实现。保守时窗不等于卫星广播有效性的完整判定。
- 每 60 秒查询一次接收器星历并保存在应用私有文件中。重新收到相同数据不会延长缓存接收年龄。缓存按 GPS 卫星保存，**不回放保存的 AID-INI 时间**。
- 普通广播星历缓存不适合隔夜启动；长时间断电后需要新的辅助数据或重新接收卫星广播。本版不实现 AssistNow Offline / Autonomous 预测轨道。
- 「帧已发送」不等于接收器已经采用。u-blox 7 AID 数据不能一概套用 CFG-ACK 确认机制；本版随后查询有效星历并记录定位时间。接收器星历也可能来自卫星，所以日志不能单独证明提速由注入造成。
- 耗时从 USB 连接建立算起，不是硬件冷启动计时；不含启动应用、等待 USB 授权的时间。
- 启用此模式时禁止原有 SiRF 配置命令，不重置接收器、不清除辅助数据、不改变星座选择。本版辅助数据只针对 GPS。
- 未取消 USB 授权弹窗，不保证免操作开机定位。已调整 Android 8+ 开机服务的前台启动方式；仍需目标设备实测。
- token 存在独立私有偏好文件；整个测试应用禁用 Android 备份，并额外排除凭证文件。没有把凭证写进 APK 或仓库。

## 星历服务现状

u-blox 旧版 AssistNow Online/Offline 已于 **2026-05-31** 结束常规维护和支持，官方公告并非立即停服通知。
本版使用旧协议兼容路径，但没有内置凭证，也没有用有效账号验证当前服务是否可用。
新 AssistNow 的 token/数据格式不应假设兼容 u-blox 7。实际投入使用前，先验证账户能获得 `format=aid` 的 GPS 数据。

- [u-blox 7 官方协议](https://content.u-blox.com/sites/default/files/products/documents/u-blox7-V14_ReceiverDescriptionProtocolSpec_(GPS.G7-SW-12001)_Public.pdf)：第 15、34、38.8 节。
- [旧版 AssistNow 接入指南](https://support.thingstream.io/hc/en-gb/articles/19628231152540--Legacy-AssistNow-Online-Offline-User-Guide)
- [服务调整公告](https://content.u-blox.com/sites/default/files/documents/PCN_AssistNowOnlineOffline%20-%20UBXDOC-199822977-208025%20-%20C1-Public.pdf)

## 构建和验证

JDK 17、Android SDK Platform 33。使用 Gradle 7.6.4 / Android Gradle Plugin 7.4.2，依赖来自 Google Maven 和 Maven Central。
保留最低 Android 4.0 / target API 27；这不是新版安卓完整迁移或 Play 上架版本。旧安卓系统的 TLS/证书条件可能影响在线请求。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

静态检查保留旧支持库和旧 target SDK 的升级提示（见 `app/lint.xml`），不代表已经完成现代安卓迁移。

测试覆盖混合数据流分包、损坏/超长帧恢复、UBX 校验和、下载数据白名单、UTC 时间格式、轨道过期/健康检查、GPS 周翻转、缓存持久化及时钟回退。

实体测试建议：相同天线位置、USB 供电条件下，交替进行辅助关闭和开启的多轮冷启动；记录硬件/固件、是否收到有效星历和首次定位耗时。
仅拔插 USB 不一定清除模块备份数据，因此不能把热启动结果当作冷启动提升。日常使用不要反复发冷启动重置命令。

后续必须在目标安卓设备和 VK-172 上验证 USB 写入、辅助数据实际采用、断开重连及开机行为。没有实测结果前，不承诺秒级定位。
