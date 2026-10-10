# 第三方来源、版本与许可

核对日期：2026-10-10。

## 坐标转换

来源：[wandergis/coordtransform](https://github.com/wandergis/coordtransform)，作者 Wandergis / 记忆的残骸；实际采用提交 `606c6f3b57b6f1d60458793fea39928d2b11b637`。移植该提交 `index.js` 中的 WGS84→GCJ-02 核心公式到 Kotlin，未使用 BD-09 转换。完整 MIT 文本保存在 `coordtransform-LICENSE.txt`，同时打包到 APK assets。

修改：使用不同坐标类型；输入边界由本应用限制；逆转换以近似值为初值，最多 20 次迭代，以正向回代残差小于 0.2 米结束。不调用百度在线逆转换，不声称绝对地理精度或百度认证。

独立固定样例来自同一指定提交的原 JavaScript 程序，直接用 Node 执行生成，保存在 `tests/fixtures/coordinates.csv`。样例证明 Kotlin 移植数值一致，不证明 SDK 点击/覆盖物对接。百度 SDK 中 GCJ-02 全局模式应按[坐标说明](https://lbsyun.baidu.com/docs/android?title=androidsdk%2Fguide%2Ftool%2Fcoordinate)验收。

## 百度地图

固定依赖 `com.baidu.lbsyun:BaiduMapSDK_Map:8.2.0`，来自 Maven Central，已解析确认；传递依赖 `base:8.2.0`、`common:1.0.43` 为地图组件必需项。AAR 自带四种 ABI 原生库，不另复制不一致版本的 SO/JAR。

官方 [Android Studio 集成文档](https://lbsyun.baidu.com/docs/android?title=androidsdk%2Fguide%2Fcreate-project%2Fandroidstudio)列出此版本；[隐私初始化接口](https://lbsyun.baidu.com/docs/android?title=androidsdk%2Fguide%2Fcreate-project%2Fattention)要求同意后、initialize 前设置 `setAgreePrivacy`。本应用在 initialize 后、MapView 创建前设 `CoordType.GCJ02`。没有添加检索、定位、导航或工具 SDK。

Maven POM 的 license 元数据标注 Apache-2.0；地图服务及 SDK 的实际使用还须遵守百度开放平台当期服务、隐私与开发者协议，不能用 POM 元数据推导服务无限免费。参考[产品说明](https://lbsyun.baidu.com/docs/android?title=androidsdk%2Findex)、[开发者权益](https://lbsyun.baidu.com/solutions/privilege)、[隐私政策](https://lbsyun.baidu.com/index.php?title=openprivacy)。AK、额度和签名绑定由用户控制台管理。本轮使用用户配置的 AK 验证了鉴权成功与 GCJ-02 实际点击，完整接入证据和视觉检查结论见 `tests/manual/verification.md`。不带 AK 的构建不能用于地图接入验收。

## GoGoGo 实现对照

参考 [ZCShou/GoGoGo](https://github.com/ZCShou/GoGoGo)，核对提交 `de0d596190c57b8ca71481f60ce6b9e50af5107f` 的 `app/src/main/java/com/zcshou/service/ServiceGo.java`。该仓库标注 GPL-3.0-only。本轮用于核对系统 test provider 的创建、属性、高度和 GPS 卫星附加字段，未复制其代码、资源或引入其依赖。FakeRun 独立使用 Android 公共 API，保留设备原来源/功耗/精度属性并声明高度/速度/方向能力；按用户要求使用 55 米高度基准与 7 颗模拟卫星默认值，允许配置，另实现连续高度误差与弱信号精度档位。具体差异与未采用项见 `tests/manual/gogogo-reference.md`。

## 构建与 Android 组件

| 组件 | 固定版本 | 来源/许可 |
| --- | --- | --- |
| AGP | 8.10.1 | Google Maven，Apache-2.0；[API 36 / Gradle 兼容表](https://developer.android.com/build/releases/agp-8-10-0-release-notes) |
| Gradle Wrapper | 8.12 | Gradle 官方仓库 / 分发，Apache-2.0，原脚本版权头保留；分发 SHA-256 写入 wrapper properties |
| Kotlin | 2.1.20 | JetBrains / Maven Central，Apache-2.0 |
| AndroidX Activity | 1.10.1 | Google Maven，Apache-2.0 |
| AndroidX Lifecycle | 2.9.1 | Google Maven，Apache-2.0 |
| Kotlin Coroutines | 1.10.2 | Maven Central，Apache-2.0 |
| JUnit | 4.13.2 | 仅测试，EPL-1.0 |
| AndroidX Test runner / ext JUnit | 1.6.2 / 1.2.1 | 仅设备测试，Apache-2.0 |

系统定位仅使用 Android 公开 API，参考 [LocationManager](https://developer.android.com/reference/android/location/LocationManager)、[Location](https://developer.android.com/reference/android/location/Location)、[location 前台服务规则](https://developer.android.com/develop/background-work/services/fgs/service-types#location)。API 31 起创建 test provider 使用 ProviderProperties，旧版本使用公开旧重载。

版本锁定为实际实现选择，不使用动态 `+`。构建输出中百度基础依赖产生 D8 栈映射警告，是否影响特定设备仍以实测为准。
