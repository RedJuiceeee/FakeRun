# 本次任务交接

日期：2026-10-10。当前交付：FakeRun 1.0.2 / versionCode 3，正式签名安装包。

## 当前任务及范围

用户通过 Android Studio 构建正式签名版，要求重新上传现有 Release 并删除旧 debug 包。本轮已完成正式包核验、附件替换、说明与校验文件更新；未修改应用功能、版本或既有标签，也未重新编译用户提供的包。

## 完成内容

- 找到用户导出的 app/release/app-release.apk，保留原文件；创建同内容发布副本 FakeRun-1.0.2-release.apk。
- 核验正式证书、版本、ABI、非调试属性和无预置 AK，并确认运行内容与此前 release 构建一致。
- 上传正式 APK，核对 GitHub 服务端摘要和大小后，替换 SHA256SUMS-1.0.2.txt 与发布说明，再删除旧 FakeRun-1.0.2-debug.apk 远端附件。本地 debug 文件保留。
- v1.0.2 仍为公开 Release；标题改为“FakeRun 1.0.2（正式签名版）”，附件最终只有正式 APK 与新校验文件。既有标签目标 53ccfbf105bbcc5ee48671b78eda0c2a5f487ace 保持不变。
- Android Studio 导出的 app/release/ 目录整体忽略，生成元数据和 baseline profiles 不进入 Git；不读取或上传签名私钥和密码。

## 修改文件

- .gitignore：忽略 app/release/ 的全部导出产物。
- README.md：正式下载地址、正式 SHA-1 与签名切换说明。
- docs/release-1.0.2.md：正式包名称、大小、校验值、验证范围和 AK 绑定指引。
- tests/manual/release-1.0.2.md：追加正式签名包核验记录，保留此前 debug 验证历史。
- change.md：本次实际交付结果。

## 验证证据

- apksigner verify --verbose --print-certs 通过，v2 签名，RSA 2048 位；证书与此前 Android Debug 不同。
- 正式 SHA-1：19:5A:13:B1:E2:FA:4D:40:FA:F8:15:46:E0:AC:AC:4A:48:60:5F:F1。
- APK 79,678,475 字节；SHA-256：6311BEE4160CE4E70A395FE2D0AD3477C97DDBDDD18908A07AD4770C145BAF0B。
- aapt 确认 com.fakerun.app、1.0.2 / code 3、minSdk 26 / targetSdk 36、四种 ABI；未启用 debuggable，Manifest 的 AK 为空。扫描全部解压条目未发现原本机 AK。
- 与此前 unsigned release 对比：仅签名块与 Git 构建元数据不同，其余 192 个条目（DEX、Manifest、资源、原生库、assets）完全一致。正式包的 Git 元数据为用户构建时的 324dd91；旧 unsigned 包为构建时的 2871e53。初次严格比较发现这一元数据差异，核对后明确排除该条目再验证运行内容一致，未静默忽略代码差异。
- GitHub 正式 APK digest、大小及 uploaded 状态一致；新校验文件 digest 与本地一致；旧 debug 附件已不存在，最终附件数量为 2，isDraft=false。
- 本轮没有应用源码/构建配置变化，不重新编译或重复已通过算法与模拟器场景；此前结果的准确版本范围见 tests/manual/release-1.0.2.md。
- 文本 UTF-8 无 BOM、LF 与 git diff --check 通过，生成产物和真实密钥未跟踪。

发布地址：https://github.com/RedJuiceeee/FakeRun/releases/tag/v1.0.2。
正式包：https://github.com/RedJuiceeee/FakeRun/releases/download/v1.0.2/FakeRun-1.0.2-release.apk。

## 未完成事项、明确阻碍及所需外部条件

本次替换发布任务已完成，没有阻碍。正式证书下的百度 AK 绑定、安装和手机运行未由本轮实测，不把此前 debug 鉴权与设备结果视为正式签名环境验证。

正式签名与旧 debug 签名不同，无法直接覆盖旧 debug 安装；卸载会删除应用私有 AK、路线和参数，当前没有路线导出能力。正式私钥由用户保管，后续更新须沿用此证书。

## 下一执行者的准确下一步

1. 使用正式 APK，按其 SHA-1 在百度控制台创建或修改 Android SDK AK 绑定，包名保持 com.fakerun.app；应用内填写后核对鉴权。
2. 若用户反馈安装/运行问题，先核对正式包哈希、签名与实际手机结果，只验证新增风险；不要重新上传旧 debug 包，也不要改写现有标签。
3. 后续源码或版本更新按项目规范实施，正式构建由用户长期私钥签署；不提交私钥、密码或预置 AK。
