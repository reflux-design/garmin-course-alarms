# 佳明课表闹钟参考实现

从课表、调课记录和值班时间计算周一至周日的起床安排。把相同时间的星期合并后，Android 适配层可以将整周写入 Forerunner 255 的原生闹钟。备用闹钟由使用者选择间隔，默认关闭。

这个仓库提供可运行的离线预览、回归检查和可集成的 Android 源码。已有实现的写入路径在 Forerunner 255（产品编号 3992，固件 29.05）上实测过。这里抽取的通用配置版本经过合成数据和模拟传输验证，接到新的设备后仍需确认槽位和时钟。

## 预览

需要 Python 3.9+ 和 JDK 17。首次执行会从 Maven Central 下载并校验一个 JSON 依赖。下列示例使用虚构课表，8 个槽位和 8 分钟备用间隔也仅用于演示。

```sh
python tools/run.py check
python tools/run.py plan examples/course.json 2030-09-02 --slots 8 --backup-minutes 8
```

输出是 JSON，包含每天的主闹钟、可选备用闹钟、每个时间的星期掩码及所需槽数。两条命令都可以在无手表的环境运行。

- 改用户规则或接新课表时，先看 [AGENTS.md](AGENTS.md) 和 [示例课表](examples/course.json)。
- 接入 Android 应用并写入自己的设备时，看 [Android 集成说明](docs/android-integration.md)。
