# FakeRun 1.0.2

版本：1.0.2 / versionCode 3。最低 Android 8.0（API 26），targetSdk 36；四种 ABI。

## 本版变化

- 安装包不预置 AK，不再读取本机 `BAIDU_MAP_AK` 参与构建。
- 准备页新增“填写 AK · 教程与签名 SHA-1”：输入/保存/清除个人 AK，展示并复制实际 APK 包名与签名指纹，一键打开百度 AK 控制台。
- AK 仅保存到应用私有存储；同意隐私后通过百度 SDK 公开接口动态鉴权。地图已初始化时更换或清除 AK 提示关闭重开，运行期间拒绝修改。
- 增加可选 `signing.properties` 正式签名配置和[正式签名教程](signing.md)。

## 安装与 AK 教程

下载 `FakeRun-1.0.2-debug.apk` 安装，使用与原交付包相同的调试签名，可覆盖更新。此附件是 **debug 签名包，不是正式签名包**。

1. 打开准备页，点击“填写 AK · 教程与签名 SHA-1”，复制包名和 SHA-1。
2. 在[百度地图开放平台控制台](https://lbsyun.baidu.com/apiconsole/key)创建 Android SDK 类型应用，包名填 `com.fakerun.app`，SHA-1 填刚复制的指纹。
3. 将自己的 AK 粘贴到应用并保存；同意隐私说明后等待“百度地图鉴权成功”。授予精确定位权限，在开发者选项选择 FakeRun 为模拟位置应用。

本附件 SHA-1：`24:7D:FB:FE:FD:58:DD:5D:3F:5F:CF:7C:DC:11:8E:AA:A0:FA:A8:6F`。自己重新签名/构建时以应用实际显示的 SHA-1 为准。

## 文件校验与验证范围

- APK 大小：80,506,144 字节。
- SHA-256：`D6EA866D9DE25F145FE7376D9D4E7E63D2A429D066644F2C455CEAAB058E4657`。
- v2 签名核验、包名/版本/ABI 核验通过；Manifest 的 AK 为空，扫描 APK 全部解压内容未发现原本机 AK。
- 9 项算法测试通过；debug APK、unsigned release APK、设备测试 APK 构建通过；lint 0 错误、71 警告。
- Android 17 / API 37 模拟器通过 AK 入口空值拒绝、保存/清除、签名指纹核对、动态 AK 鉴权及地图绘制/GCJ-02 覆盖物对接。
- 本轮未安装到用户手机，未重复无变化的两圈模拟、后台/息屏与第三方兼容性场景；不把 1.0.1 用户核验结果当成 1.0.2 真机验收。

未提供正式签名私钥，所以本轮仅提供可安装 debug APK；unsigned release 构建产物不可直接安装，不作为下载附件。旧含 AK 的 1.0.1 草稿不公开。应用保留 mock 标记，第三方位置采用情况依其实现而定。
