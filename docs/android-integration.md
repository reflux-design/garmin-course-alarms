# 接入 Android 并验证原生闹钟

协议分层、蓝牙特征、文件收发与回执顺序见 [连接与传输研究](transport.md)。

将 `src/` 下除 `PlanCli.java` 外的 Java 文件与 `android/` 下的适配层加入宿主应用。适配层要求 Android 13+ 和蓝牙连接权限 `BLUETOOTH_CONNECT`。它通过 BLE 寻找一块已配对、名称为 `Forerunner 255` 的设备，再比对 FIT 内的设备编号和产品。宿主负责录入课表、呈现整周预览、持久化状态和调度后台工作。

1. **登记设备。** 使用用户的设备编号、获准管理的槽位、获准替换的已启用槽位和用户确认过提示方式的闹钟模板，创建 `AlarmProfile`。其他槽位会被排除在增量之外。额外槽在登记时应为空闲或关闭。用新的可信设置备份核对一次槽位，尤其是设备上手工改过闹钟以后。保存原始 FIT 到应用私有目录。首次登记时，`saveEnrollment` 必须原子落盘，失败需抛异常。
2. **算周计划。** 在 `cn.local.garminassistant` 包内实例化 `CourseEngine`，使用 `NativeWakeWeek.forWeek` 做预览，使用 `NativeWakeWeek.desired` 决定当前应安装的自然周。星期掩码按周一第 0 位至周日第 6 位。备份和主闹钟共用已授权槽位，合并后以不同时间数量占槽。用 `AlarmTimes.combine` 检查槽数和跨日，再调用下面的写入入口。传入 `week.groups` 原始主闹钟分组，由写入入口计算备用时间。
3. **调度并写入。** 在后台线程串行调用 `NativeAlarmClient.applyWeek(context, enrollmentBytes, profile, week.groups, backupMinutes, saveEnrollment, stillCurrent, logger)`。`stillCurrent` 每次读取当前配置，确认用户仍启用同步、目标周和时间未变。用自然周、每天的主时间、备用间隔和设备配置共同作为已应用计划标识。未变化时跳过写入。原实现从周日晚准备下一周，并为可能响铃的旧主闹钟和备用闹钟保留窗口，参见 `NativeWakeWeek.refreshAt` 和 `holdUntil`。保持一次传输在途，失败后延迟重试。宿主需要在启动、设置变化和定时任务到期时复核计划。
4. **核对。** 传输需要与本次上传令牌匹配的成功状态，同时需要成功的 `SYNC_COMPLETE`。二者齐全后才记录已同步。蓝牙下载到的设置可能是缓存；真实验收应在表端查看星期和时间，或通过手表 USB 读取最新设置，并观察一次实际响铃。断连期间手表按已存星期重复，只有下次连接并成功更新后才执行新一周的课表。

按相同的检查命令可选编译 Android 适配层。把以下路径换成所装 Android SDK 的 `android.jar`。

```sh
python tools/run.py --android-jar /path/to/android.jar check
```
