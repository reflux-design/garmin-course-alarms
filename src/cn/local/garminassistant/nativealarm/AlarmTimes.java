package cn.local.garminassistant.nativealarm;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

public final class AlarmTimes {
    private AlarmTimes() {}

    /** Add an optional same-day backup to every covered weekday and merge equal times. */
    public static Map<Integer,Integer> combine(Map<Integer,Integer> primary, int backupMinutes, int slots)
            throws IOException {
        if (backupMinutes < 0 || backupMinutes >= 1440 || slots < 1 || slots > 10) {
            throw new IOException("Invalid backup delay or slot budget");
        }
        Map<Integer,Integer> combined = new TreeMap<>();
        int covered = 0;
        for (Map.Entry<Integer,Integer> entry : primary.entrySet()) {
            int minute = entry.getKey();
            int mask = entry.getValue();
            if (minute < 0 || minute >= 1440 || mask < 1 || mask > 127 || (covered & mask) != 0) {
                throw new IOException("Primary alarms must cover each weekday exactly once");
            }
            if (minute + backupMinutes >= 1440) {
                throw new IOException("Backup alarm would cross midnight");
            }
            covered |= mask;
            combined.merge(minute, mask, (a, b) -> a | b);
            if (backupMinutes > 0) combined.merge(minute + backupMinutes, mask, (a, b) -> a | b);
        }
        if (covered != 127) throw new IOException("A week must cover Monday through Sunday");
        if (combined.size() > slots) throw new IOException("Alarm slot budget exceeded");
        return combined;
    }
}
