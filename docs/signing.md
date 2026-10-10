# 生成正式版签名与安装包

FakeRun 1.0.2 提供两种构建方式：debug 安装包用于本轮测试；正式发布使用自己的长期签名私钥。AK 在应用内填写，任何构建均不预置 AK。

## 方法一：Android Studio

选择 **Build → Generate Signed App Bundle or APK → APK**，点击 **Create new** 创建密钥库，设置存放位置、密钥别名与密码，选择 release 构建。密钥有效期建议至少 25 年。按向导生成签名 APK，安装后从应用内复制实际 SHA-1，再在百度控制台创建匹配的 Android SDK AK。官方步骤见 [为应用签名](https://developer.android.com/studio/publish/app-signing)。

## 方法二：命令行与本项目配置

1. 在项目根目录执行以下命令，交互填写密码与证书信息（命令不会包含密码）：

   ```powershell
   keytool -genkeypair -v -storetype JKS -keystore release.jks -alias fakerun -keyalg RSA -keysize 2048 -validity 10000
   ```

2. 将 `signing.properties.example` 复制为 `signing.properties`，填写四项。示例中的路径为项目相对路径；也可使用绝对路径，Windows 使用 `/`，盘符冒号写为 `\:`。

   ```properties
   storeFile=release.jks
   storePassword=自己设置的密钥库密码
   keyAlias=fakerun
   keyPassword=自己设置的密钥密码
   ```

3. 查询正式签名 SHA-1（交互输入密钥库密码）：

   ```powershell
   keytool -list -v -keystore release.jks -alias fakerun
   ```

4. 构建正式签名包：

   ```powershell
   .\gradlew.bat :app:assembleRelease
   ```

   配置完整时生成 `app/build/outputs/apk/release/app-release.apk`；没有 `signing.properties` 时仅生成 `app-release-unsigned.apk`，该文件不能直接安装。缺字段、错误密码或不存在的私钥文件会使构建失败，不回退成调试签名。

5. 用 Android SDK Build Tools 中的 `apksigner` 核验：

   ```powershell
   apksigner.bat verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
   ```

   `apksigner.bat` 不在 PATH 时使用 SDK `build-tools/35.0.0` 中的完整工具路径。确认版本、签名与 SHA-1 后安装；百度 AK 的签名绑定使用这份正式证书的 SHA-1。

## 升级与保管

正式签名和现有 debug 签名不同，Android 通常不能直接覆盖安装。切换前先保存需要保留的数据，卸载 debug 版会删除其私有 AK 与路线；本应用当前没有路线导出功能，不应把“保存路线”视为卸载后的备份。后续正式版升级须沿用同一长期签名与递增 versionCode。

`release.jks` 和真实 `signing.properties` 已被 Git 忽略。请将私钥与密码单独备份，避免丢失后无法升级；签名配置中的明文密码仅保存在自己的本机，不提交或附在 Release 中。本任务没有替你创建正式私钥，也没有编造正式签名 SHA-1。
