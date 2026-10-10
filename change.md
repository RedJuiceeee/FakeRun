# 本次任务交接

日期：2026-10-10。当前交付：FakeRun 1.0.2 / versionCode 3。

## 当前任务及范围

用户明确不公开原 APK 内的百度地图 AK，要求增加应用内手动填写入口、简要教程与 SHA-1，再编译打包 v1.0.2，并说明如何生成正式版签名。延续已授权的仓库推送/Release 工作，本轮已完成实现、打包、相关验证、源码推送及无预置 AK 安装包发布。没有生成用户的正式私钥，不把 debug 包称为正式签名包。

## 完成内容

- 准备页新增 AK 输入/保存/清除、配置教程、百度控制台入口、实际 APK SHA-1 展示与复制。AK 仅保存在应用私有存储，输入按密码显示；未填写时不初始化地图，同意隐私后动态传入 SDK。
- 构建移除 BAIDU_MAP_AK 的读取和 Manifest 占位符，旧本机密钥即使留在 local.properties 中也不会打包。升级后需手动填写 AK，不从旧嵌入密钥迁移。
- 地图已初始化后改变 AK，销毁当前地图并提示完整关闭重开，避免沿用进程内鉴权；运行期间拒绝修改。
- 版本升级为 1.0.2 / code 3，沿用原 debug 证书以支持覆盖更新；增加可选 signing.properties 正式签名配置与无真实密码的示例。
- 补充 Android Studio/keytool 生成签名、查询 SHA-1、构建/核验正式 APK、签名切换与私钥备份教程。没有签名配置时生成 unsigned release APK，不会冒用 debug 证书。
- 已将完整源码与教程推送至 https://github.com/RedJuiceeee/FakeRun，分支 master。实现提交/发布标签目标：53ccfbf105bbcc5ee48671b78eda0c2a5f487ace。
- v1.0.2 已公开发布为 Latest，isDraft=false、isPrerelease=false；附件为 FakeRun-1.0.2-debug.apk 与 SHA256SUMS-1.0.2.txt。旧含 AK 的 v1.0.1 草稿已删除，未公开。

## 修改文件

- app/build.gradle.kts、AndroidManifest.xml：版本、无预置 AK、可选正式签名。
- ui/MainActivity.kt、ui/SigningIdentity.kt、data/MapSettingsRepository.kt、activity_main.xml、values/ids.xml：运行时 AK 配置、教程、私有保存与实际签名。
- .gitignore、local.properties.example、signing.properties.example：隔离本机配置、签名及其备份，保留安全示例。
- tests/device/MapConfigurationTest.kt、DeviceAcceptanceTest.kt：配置入口/动态鉴权测试，地图验收改为检查运行时 AK。
- tests/manual/release-1.0.2.md：详细真实结果、失败修正原因与手机复核步骤。
- README.md、plan.md、docs/release-1.0.1.md、docs/release-1.0.2.md、docs/signing.md、docs/third-party.md：当前操作规则、发布、签名与官方接口来源。
- change.md：本次最终交付记录。代码路径以上按项目内职责简写。

## 验证证据

最终必要构建命令：gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug --offline --console=plain。BUILD SUCCESSFUL，52 秒；9 项算法测试，0 失败/错误/跳过；debug、unsigned release、设备测试 APK 构建通过；lint 0 错误、71 警告。首次 ClipboardManager 导入冲突修正后重跑；设备测试窗口与按钮定位修正后仅重编译、重跑受影响测试。没有无理由重复已通过的生产构建或算法测试。

模拟器 Pixel_10_Pro / sdk_gphone16k_x86_64 / Android 17 / API 37：配置入口保存/清除与实际 SHA-1、动态 AK 鉴权、地图绘制/GCJ-02 覆盖物三项测试分别通过。动态鉴权码为 0；实际地图点击 4 个方形节点，WGS84 首点偏差 <3 米。测试临时密钥文件及本轮新增模拟器私有 AK 配置已清除，模拟器已关闭；原始输出不提交。

APK：app/build/outputs/apk/debug/FakeRun-1.0.2-debug.apk，80,506,144 字节。SHA-256：D6EA866D9DE25F145FE7376D9D4E7E63D2A429D066644F2C455CEAAB058E4657。v2 签名通过；SHA-1：24:7D:FB:FE:FD:58:DD:5D:3F:5F:CF:7C:DC:11:8E:AA:A0:FA:A8:6F。aapt 确认包名、1.0.2/code 3、min 26/target 36 与四种 ABI。Manifest 的 AK 为空；扫描 debug 与 unsigned release APK 全部解压条目均未发现原本机 AK。

GitHub 公开附件状态 uploaded，大小与服务端 SHA-256 digest 和本地一致；远端 v1.0.2 标签已获取并核对为实现提交。发布地址：https://github.com/RedJuiceeee/FakeRun/releases/tag/v1.0.2。APK 下载：https://github.com/RedJuiceeee/FakeRun/releases/download/v1.0.2/FakeRun-1.0.2-debug.apk。

文本和差异检查通过，UTF-8 无 BOM、LF。真实 AK、APK/构建输出、设备原始日志、签名私钥和密码未进入 Git；生产代码与普通 APK 不依赖 tests/。

## 未完成事项、明确阻碍及所需外部条件

本次实现/打包/安全替代发布任务已完成，没有发布阻碍。公开的是不预置 AK 的 debug 签名包，仍需使用者自己申请并在应用内填写 AK。

用户手机的 1.0.2 未安装实测；AK 更换后实际点击“关闭应用”及运行期间拒绝更换分支尚未单独设备实测。原两圈/后台/息屏/第三方兼容性结论按原版本保留，本轮未重复无变化场景。1.0.1 用户反馈“核验通过”不自动代表 1.0.2 真机验收。

正式签名的私钥、密码与百度签名绑定由用户管理。本轮未提供这些条件，因此未生成正式签名安装包；已交付完整教程和配置支持，unsigned release 构建已验证。更换正式签名通常不能覆盖现有 debug 安装，卸载会删除私有 AK/路线，当前无路线导出能力。

## 下一执行者的准确下一步

1. 让用户安装公开的 1.0.2 debug APK，按准备页教程填写 Android SDK AK，核对 SHA-1、地图鉴权与重开后的持久保存；手机复核步骤见 tests/manual/release-1.0.2.md。
2. 若要正式发布，按 docs/signing.md 由用户创建并备份长期私钥，填写未提交的 signing.properties，运行 assembleRelease 与 apksigner 核验，并用正式证书 SHA-1 在百度控制台绑定 AK；不要提交私钥/密码，也不要将 unsigned 包标为可安装。
3. 后续只针对新增反馈/风险改代码和验证，不重复无变化场景，不把模拟器结果扩大为所有真机或第三方兼容性。
