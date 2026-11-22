package cn.local.garminassistant;

import cn.local.garminassistant.nativealarm.AlarmTimes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline plan preview. This entry point has no device transport. */
public final class PlanCli {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: <course.json> <Monday> <slots> <backupMinutes>");
        CourseEngine engine = new CourseEngine(new JSONObject(Files.readString(Path.of(args[0]))));
        NativeWakeWeek week = NativeWakeWeek.forWeek(engine, LocalDate.parse(args[1]));
        int slots = Integer.parseInt(args[2]);
        int delay = Integer.parseInt(args[3]);
        Map<Integer,Integer> groups = AlarmTimes.combine(week.groups, delay, slots);
        JSONArray days = new JSONArray();
        for (int day = 0; day < 7; day++) {
            JSONObject row = new JSONObject().put("date", week.monday.plusDays(day).toString())
                .put("primary", time(week.minutes[day]));
            if (delay > 0) row.put("backup", time(week.minutes[day] + delay));
            days.put(row);
        }
        JSONArray alarmGroups = new JSONArray();
        for (Map.Entry<Integer,Integer> alarm : groups.entrySet()) {
            alarmGroups.put(new JSONObject().put("time", time(alarm.getKey())).put("weekdayMask", alarm.getValue()));
        }
        JSONObject result = new JSONObject().put("monday", week.monday.toString()).put("timezone", engine.zone.toString())
            .put("days", days).put("alarmGroups", alarmGroups).put("slotsNeeded", groups.size());
        System.out.println(result.toString(2));
    }

    private static String time(int minutes) {
        return LocalTime.of(minutes / 60, minutes % 60).toString();
    }
}
