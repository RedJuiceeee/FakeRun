# 固定测试输入

`coordinates.csv` 来自 wandergis/coordtransform 的 JavaScript `wgs84togcj02` 实现，提交 `606c6f3b57b6f1d60458793fea39928d2b11b637`，使用 Node 直接执行原文件生成，北京、上海、广州、成都、深圳五个固定点。没有调用本项目 Kotlin 正逆函数生成预期值。许可证见 `docs/coordtransform-LICENSE.txt`。

算法测试使用合成闭合方形路线，不包含个人路线。主复合场景 seed 为 `123456`，其余测试在调用处显式固定 seed。

这些样例验证算法移植数值一致性，不能替代百度 SDK 点击/覆盖物或实际地理精度的设备验收。
