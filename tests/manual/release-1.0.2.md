# 1.0.2 AK 配置与打包验证

日期：2026-10-10。包名 `com.fakerun.app`，1.0.2 / code 3。本轮更改 AK 来源、配置界面及可选正式签名配置，不更改轨迹、注入算法或服务行为。

## 已验证

- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug --offline --console=plain`：BUILD SUCCESSFUL，52 秒；9 项算法测试，0 失败/错误/跳过；debug、unsigned release、设备测试 APK 构建通过；lint 0 错误、71 警告。
- 首次编译因新按钮中的 ClipboardManager 通配导入冲突失败，明确限定 android.content 类型后重跑上述受影响构建。最终生产 APK 生成后没有再改生产源码。
- APK：`app/build/outputs/apk/debug/FakeRun-1.0.2-debug.apk`；80,506,144 字节。SHA-256：`D6EA866D9DE25F145FE7376D9D4E7E63D2A429D066644F2C455CEAAB058E4657`。
- `apksigner verify --verbose --print-certs`：v2 签名通过，Android Debug 证书；SHA-1：`24:7D:FB:FE:FD:58:DD:5D:3F:5F:CF:7C:DC:11:8E:AA:A0:FA:A8:6F`，与此前交付相同。
- `aapt dump badging`：包名、1.0.2 / code 3、minSdk 26 / targetSdk 36、四种 ABI 正确。`aapt dump xmltree`：`com.baidu.lbsapi.API_KEY` 为空字符串。
- 扫描 debug APK 与 unsigned release APK 的每个解压 ZIP 条目，均不包含原本机 AK。生产构建没有读取 BAIDU_MAP_AK 的入口，`local.properties` 仅供 SDK 目录等本机构建配置使用。
- 本机现有 Pixel_10_Pro 模拟器，`sdk_gphone16k_x86_64`，Android 17 / API 37，覆盖安装成功。没有操作用户手机。
- `MapConfigurationTest#emptyKeyAndDialogPersistence`（传入上述 expectedSha1）：通过，1 项；未填写 AK 时无 MapView，空值不能保存，输入保存去除首尾空白，重新读取配置正确，内部空白拒绝，配置入口清除成功，实际签名指纹与外部 apksigner 一致。
- `MapConfigurationTest#runtimeApiKeyAuthenticates`（checkRuntimeAk=true）：通过，1 项；已有 AK 仅临时传入本机模拟器，再由私有存储供 SDK 初始化，鉴权码 0，有 MapView。临时传输文件已删除，密钥未输出/打包/提交。
- `DeviceAcceptanceTest#mapDrawingAndOverlayDisplay`（checkMap=true）：通过，1 项，11.693 秒；GCJ-02 模式下实际点击 4 个方形节点，WGS84 首点偏差 <3 米，覆盖物截图生成。
- 配置入口测试初次因窗口未就绪失败；唤醒模拟器仍失败，说明不是锁屏根因。加入有上限的活动窗口等待、视图 ID 报告后，又发现“清除 AK”匹配到了教程文本；改为精确匹配可点击按钮，重编译设备测试并通过。只重跑失败/修改的设备测试，不重复生产构建和已通过算法。
- 文本/差异检查通过，UTF-8 无 BOM、LF；真实 AK、日志、签名文件与产物仍被忽略，签名配置示例可跟踪。生产构建不依赖 `tests/`。

## 未验证与手机检查步骤

1. 手机安装 1.0.2，先确认未预置个人 AK，准备页的 SHA-1 与本轮交付一致。旧版嵌入的 AK 不自动迁移，需手动填写。
2. 按应用内教程申请 Android SDK AK，确认鉴权成功；关闭重开后仍可读取自己的 AK。
3. 已启用地图后更换/清除 AK，确认地图停止显示、提示重开；点击关闭并手动重开后确认新 AK/空 AK 生效。本轮没有通过自动化实际点击会终止进程的“关闭应用”按钮。
4. 运行期间尝试保存/清除 AK，应明确要求先结束模拟；结束后再修改。本轮仅代码检查该拒绝分支，未单独实测。
5. 本轮没有用户正式私钥，正式签名流程仅提供配置与教程；release unsigned 构建已验证，真实正式证书构建/安装/百度绑定需用户执行。

既有两圈、后台/息屏、暂停恢复和第三方目标兼容性记录按对应版本保留，本轮不重复无变化场景，也不宣称本版本已通过全部真机场景。
