# 给接手的 AI

1. 先读 `examples/course.json`，把用户提供的课表转换为同一结构。学期起始日期和预览日期都用周一。星期用 1 到 7 表示；课次用 `weeks` 明确列出，单双周展开成周次。值班是普通日程条目。
2. 按日期先应用取消和补课，再按当天第一项日程算起床时间。`wake_overrides` 优先。使用提前量规则时，读取 `lead_minutes` 和 `no_class_wake`；要用固定时刻规则，则显式提供 `fixed_wake` 的 `class_start`、`early_wake`、`other_wake`。固定规则比较当天第一项日程的开始时间是否恰好等于 `class_start`。预览与腕表都应使用用户选择的时区。
3. 用 `python tools/run.py plan <课表> <周一> --slots <可用槽数>` 生成一周预览。只有用户需要备用提醒时才附加 `--backup-minutes <分钟>`。先核对每天的时间和槽数，再考虑设备写入；遇到跨午夜或槽数不足应把冲突交给用户解决。
4. `src/` 包含课表计算、时间掩码、FIT 增量和协议验证。`android/` 包含 BLE 适配层。`tools/run.py check` 用合成数据跑完回归。排程和设备接入以 [Android 集成说明](docs/android-integration.md) 的身份验证、备份和回执要求为准。
5. 用户的真实课表、已配对设备序列号、实际槽位清单和 FIT 备份均由用户在本地保存。仓库只用虚构示例。报告结果时分别标明预览通过、测试通过、手表确认写入和真实响铃。
