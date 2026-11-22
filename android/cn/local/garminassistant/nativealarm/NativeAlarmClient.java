package cn.local.garminassistant.nativealarm;

import android.content.Context;
import android.os.Build;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class NativeAlarmClient {
    private NativeAlarmClient() {}

    public interface SaveEnrollment {
        void save(byte[] data) throws Exception;
    }

    /** Call from a worker. A successful return requires both device completion receipts. */
    public static void applyWeek(Context context, byte[] enrollment, AlarmProfile profile,
            Map<Integer,Integer> primary, int backupMinutes, SaveEnrollment saveEnrollment,
            BooleanSupplier stillCurrent, Consumer<String> log) throws Exception {
        if (Build.VERSION.SDK_INT < 33) throw new IOException("Android 13 or later required");
        final Map<Integer,Integer> agreedPlan = new TreeMap<>(primary);
        AlarmTimes.combine(agreedPlan, backupMinutes, profile.managedSlots.size());
        if (!stillCurrent.getAsBoolean()) throw new IOException("Alarm plan superseded");
        NativeAlarmConnection.Job job = (protocol, download, upload) -> {
            byte[] observed = protocol.readSettings(download);
            NativeAlarmFit.verifyDevice(observed, profile.unit);
            byte[] baseline = enrollment == null ? observed : enrollment;
            NativeAlarmFit fit = new NativeAlarmFit(baseline, profile);
            byte[] delta = fit.week(agreedPlan, backupMinutes, System.currentTimeMillis() / 1000);
            if (enrollment == null) saveEnrollment.save(baseline.clone());
            if (!stillCurrent.getAsBoolean()) throw new IOException("Alarm plan superseded");
            protocol.uploadSettings(delta, upload, profile, backupMinutes);
        };
        NativeAlarmConnection connection = new NativeAlarmConnection(context, log, job, 0x2811);
        connection.run();
        if (!connection.completed() && connection.redirectCharacteristic() == 0x2810) {
            connection = new NativeAlarmConnection(context, log, job, 0x2810);
            connection.run();
        }
        if (!connection.completed()) throw new IOException("Watch connection unavailable");
    }
}
