# GoGoGo 参考实现与新版试包

日期：2026-10-10。版本：FakeRun 1.0.1 / versionCode 2。

## 参考结果与实现选择

核对 [ZCShou/GoGoGo 的 ServiceGo.java](https://github.com/ZCShou/GoGoGo/blob/de0d596190c57b8ca71481f60ce6b9e50af5107f/app/src/main/java/com/zcshou/service/ServiceGo.java)，固定提交 `de0d596190c57b8ca71481f60ce6b9e50af5107f`。其核心同样是 `addTestProvider`、`setTestProviderEnabled`、`setTestProviderLocation`，分别输出 gps/network；没有在这份服务代码中看到修改第三方 SDK 或清除 mock 标记的实现。用户反馈该工具可改变步道乐跑定位，本轮没有设备对照验证，不能推断不同版本、手机上的普遍兼容性。

| 项目 | GoGoGo 服务 | FakeRun 1.0.0 | FakeRun 1.0.1 |
| --- | --- | --- | --- |
| GPS 属性 | 卫星来源、HIGH 功耗、FINE 精度 | 卫星来源、LOW 功耗、FINE 精度 | 保留设备原来源/功耗/精度，声明高度/速度/方向能力 |
| network 属性 | 网络/基站来源、LOW 功耗、COARSE 精度 | 无网络/基站需求、LOW 功耗、FINE 精度 | 保留设备原来源/功耗/精度，声明高度/速度/方向能力 |
| 输出 | 两种 provider 分别填充 Location | 两种 provider 共用快照和时间戳 | 沿用同步 WGS84、速度、方向、精度与时间戳 |
| 频率 | 线程休眠约 100 ms 后输出 | 单调时钟 5 Hz | 按规划保持 5 Hz |
| 高度/卫星 extras | 默认高度 55 米，GPS satellites=7 | 无未建模高度、无卫星字段 | 高度基准默认 55 米，可配置并叠加 ±1.5 米连续误差；GPS 卫星数默认 7，可配置 |
| 精度与弱信号 | GPS accuracy=1 米、network=2 米 | 同步水平精度 4～8 米 | 默认保持 4～8 米；卫星配置 <4 时使用 20～40 米弱信号档位 |
| 清理 | 启动前、销毁时尝试删除同名 provider | 只移除本次已创建的 provider | 保持自有资源清理 |

属性是定位来源的能力和选择条件；单个 `Location.accuracy` 是位置的米级精度，两者不是同一个字段。用户本轮追加要求覆盖原高度/卫星限制，`plan.md` 已同步更新。高度是 WGS84 椭球高度的模拟观测，不是地图海拔或真实地形；高度不改变水平路程和速度。初始误差为零，每 15 秒独立 seed 锚点平滑插值，暂停和驻留仍连续更新；正常垂直精度为 `max(3 米, 水平精度×0.75)`，弱信号档位与水平精度相同。卫星数量是本次固定的配置，不随机跳变。

实现基于 [Android LocationManager 公共 API](https://developer.android.com/reference/android/location/LocationManager)：API 31+ 查询 `hasProvider` / `getProviderProperties`，保留来源/功耗/精度属性并启用三种输出能力后调用 `addTestProvider`；API 26～30 读取 `LocationProvider`，使用旧重载。没有定位来源时明确拒绝；network 缺失提示关闭相应开关并重新生成预览，属性未知时提示稍后重试。不用猜测属性继续输出。

与参考项目一样，卫星字段仅为 GPS `Location.extras["satellites"]`。系统 GNSS 卫星状态、NMEA、原始测量回调没有被替换；如果目标“GPS信号弱”由这些接口或其 SDK 自己的判断产生，补充 Location 字段仍可能无效。未加入 Root、Hook 或 mock 隐藏。

这修正了可确认的 provider 属性偏差；目前没有证据证明该偏差是步道乐跑未采用位置的根因。原版本的真机系统输出证据见 [旧版定位兼容记录](location-compatibility.md)，不能算作新版实测结果。

## 无 ADB 的手机测试步骤

1. 将交付的 `FakeRun-1.0.1-debug.apk` 复制到手机直接覆盖安装。包名和本机调试签名沿用旧版；无需卸载或清除已保存路线。若系统报签名冲突，保留旧应用数据，先核对安装包来源。
2. 开启系统定位、授予精确定位，在开发者选项选择 FakeRun。确认百度地图正常显示；保持“同时模拟 network”开启。
3. 绘制与实际位置明显不同的闭合路线，先用默认一圈、零次停留、0% 步行、55 米高度、7 颗卫星生成预览并开始。检查状态持续运行、GPS WGS84 数值随轨迹变化，高度在 53.5～56.5 米内平滑变化，水平精度在 4～8 米内。
4. 打开地图应用核对位置，再关闭并重新打开步道乐跑，进入此前出问题的准备页，等待 20～30 秒并点击回到当前位置。记录是否采用所选起点、是否仍报“GPS信号弱”。准备页结果即可判断本次兼容尝试，不需要上传成绩。
5. 返回 FakeRun，单独检查平滑暂停、继续、立即结束。另生成卫星数 3 的短场景，核对预览/运行显示弱信号档位、水平/垂直精度 20～40 米；这是诊断档位，不保证第三方显示对应提示。结束后等待地图应用取得新的真实定位；卫星接收和网络条件会影响恢复时间。
6. 最后按 [完整验收步骤](acceptance.md) 执行两圈、两次停留、20% 步行与后台/息屏场景。记录设备/系统/目标版本、结果与错误文案，避免保存或提交个人路线与原始位置日志。

## 自动检查与外部条件

最终命令：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline --console=plain`。结果 **BUILD SUCCESSFUL，52 秒**。9 项算法测试实际执行，0 失败、0 错误、0 跳过；新增检查覆盖高度初值/范围/锚点连续性、固定 seed 重现、正常/弱信号精度、配置范围，以及两种信号档位不改变水平路径/速度。普通 APK 和设备测试 APK 构建通过。Lint 0 错误、61 警告，未隐藏错误；警告包含既有中文硬编码、唤醒锁及测试旧 API 等。

交付文件 `app/build/outputs/apk/debug/FakeRun-1.0.1-debug.apk`，与 `app-debug.apk` 相同，80,575,776 字节。SHA-256：`D163D638D6EC4353DDD11458F88A606C1A0421DEAE3C0161473F582731140BD7`。`apksigner verify --verbose --print-certs` 验证通过（v2 签名）；签名 SHA-1 沿用旧版 `24:7D:FB:FE:FD:58:DD:5D:3F:5F:CF:7C:DC:11:8E:AA:A0:FA:A8:6F`。`aapt dump badging` 确认包名 `com.fakerun.app`、版本 1.0.1 / code 2、min 26 / target 36，包含 arm64-v8a 等四种 ABI。使用已有本地 AK，未更换签名或输出密钥；新版百度鉴权仍须手机确认。

新增设备测试 `DeviceAcceptanceTest#nativeProviderCharacteristicsAndSynchronizedOutput` 检查覆盖时来源/功耗/精度保留与输出能力启用、结束后原属性恢复，监听 gps/network 的本次新时间戳，核对 WGS84、速度、方向、精度、mock、高度/垂直精度、仅 GPS 带卫星字段。原静态与完整场景读取器同步更新高度和卫星断言；JSON 检查新增参数保存、旧文件迁移与非法卫星小数拒绝。这些设备验证源码已编译，**未运行**。测试只放 `tests/`，普通 APK 不依赖该目录。

首轮构建对应仅修正 provider 属性的中间版本。用户随后增加高度/卫星要求后重建；最终检查发现 JSON `getInt` 会截断卫星小数，修正后再次运行受影响测试与构建，以上证据属于最终源码。未因重复验证而重跑同一版本。

用户已断开 ADB 并指定自行测试。本轮不连接或安装到设备；新增设备测试、新版手机完整场景、百度鉴权与步道乐跑兼容性均尚未验证。若准备页仍不采用，按旧记录继续取得可用的 SDK 诊断；不要将本轮试包描述为已修复兼容问题。
