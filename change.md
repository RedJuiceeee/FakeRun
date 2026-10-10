# 本次任务交接

日期：2026-10-10。当前应用：FakeRun 1.0.1 / versionCode 2。

## 当前任务及范围

用户反馈“核验通过”，要求检查 `.gitignore`、推送到 `RedJuiceeee/FakeRun`、检查正式版本安装包并创建 Release；随后明确确认发布已核验的 debug APK，Release 中注明调试签名。

## 完成内容

- 核对原工作区干净、既有版本和构建记录；仅存在已核验 debug APK，没有正式 release 签名包或正式签名配置。
- 补全忽略规则，排除签名配置、环境文件及其备份、签名证书/私钥、AAB/APKS/签名旁文件、原生构建缓存、内存转储、路线与导出目录。配置示例和 Gradle Wrapper JAR 保持可跟踪；新增二进制后缀使用 binary 属性。
- 全部历史提交检查未发现当前本地 AK、私钥块或实际本机目录；构建输出、APK、真实配置和设备原始日志均未跟踪。
- 已将完整源码历史及发布文档推送到 `https://github.com/RedJuiceeee/FakeRun.git`，分支 `master`。发布内容提交：`09e0a3a9cd53e88751429f1afdd7a2c995be1186`。
- 已创建 `v1.0.1` Release 草稿，上传 `FakeRun-1.0.1-debug.apk` 和 `SHA256SUMS.txt`；草稿目标为上述发布提交。草稿尚未公开，标签尚未核对为已创建。

## 修改文件

- `.gitignore`：补全敏感配置、构建产物及个人数据目录忽略规则。
- `.gitattributes`：补全新增二进制后缀属性。
- `README.md`：加入预定 Release 地址、调试签名说明和用户核验反馈范围。
- `docs/release-1.0.1.md`：发布说明、签名/校验信息、版本内容和验证范围。
- `change.md`：本轮推送结果、草稿状态及公开发布阻碍。

## 验证证据

- `scripts/check-text.ps1` 与 `git diff --check` 通过：文本 UTF-8 无 BOM、LF。忽略规则命中真实配置、签名配置、环境文件、APK/AAB、签名旁文件、路线导出；示例配置及 Wrapper JAR 未忽略。
- `apksigner verify --verbose --print-certs` 通过，v2 签名，证书为 Android Debug；签名 SHA-1：`24:7D:FB:FE:FD:58:DD:5D:3F:5F:CF:7C:DC:11:8E:AA:A0:FA:A8:6F`。
- `aapt dump badging` 确认包名 `com.fakerun.app`、1.0.1 / code 2、minSdk 26 / targetSdk 36 和四种 ABI。
- APK 80,575,776 字节，SHA-256：`D163D638D6EC4353DDD11458F88A606C1A0421DEAE3C0161473F582731140BD7`。GitHub 草稿附件状态 uploaded，大小与服务端 digest 一致；校验文件亦已上传。
- `git push --set-upstream origin master` 成功，建立跟踪。远端此前为空，未改写历史。
- 本轮无应用源码或构建配置变化，未重复算法测试或重建 APK。沿用本版本此前 9 项算法测试、debug 构建、设备测试 APK 编译和 lint 结果（0 错误、61 警告）。用户核验通过不扩展为未提供的逐项第三方兼容结论。

## 未完成事项、明确阻碍及所需外部条件

公开发布 Release 被自动审批拒绝：已核验 APK 内含本地配置的百度地图 Android SDK AK，公开附件会使该 AK 可被提取；现有发布授权未被审批认定为明确授权公开该敏感内容。AK 数值、签名私钥和密码未写入源码或发布文档。Release 保持草稿。

草稿地址：`https://github.com/RedJuiceeee/FakeRun/releases/tag/untagged-5982b5a015cf8f89ca11`（需要仓库访问权限）。预定公开地址：`https://github.com/RedJuiceeee/FakeRun/releases/tag/v1.0.1`。

所需外部条件：用户明确授权公开发布含现有百度地图 AK 的已核验 APK；或明确改为无 AK 安装包。后一方案需要重建，地图不可直接使用，也不再是本次已核验的同一文件。正式签名包仍需另行提供正式签名配置和百度 AK 签名绑定。

## 下一执行者的准确下一步

1. 先取得上述具体内容的公开授权，不绕过自动审批。GitHub CLI 无持久登录，但 Git Credential Manager 已有可用账号凭据；可仅在进程内供 CLI 使用，不输出或保存令牌。
2. 若授权公开原包，读取草稿并确认目标提交、两个附件状态、APK 大小和 digest；执行 `gh release edit v1.0.1 --repo RedJuiceeee/FakeRun --draft=false --latest`，重新通过审批。
3. 发布成功后核对公开地址、isDraft=false、附件下载地址、远端 v1.0.1 标签及目标提交；获取标签并更新本交接为真实完成状态，提交并推送。
4. 若用户要求移除 AK，先按新要求准备包和相关验证，再更新附件、校验值和发布说明；不要冒用旧包核验结果。
