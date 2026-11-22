package cn.local.garminassistant;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.json.*;

/** A stable Monday-Sunday schedule, not a rolling seven-day window. */
final class NativeWakeWeek {
  private static final int RING_GRACE_MINUTES = 60;
  final LocalDate monday;final int[] minutes=new int[7];final Map<Integer,Integer> groups=new TreeMap<>();final String key;
  private NativeWakeWeek(CourseEngine engine,LocalDate start)throws Exception{
    if(start.getDayOfWeek()!=DayOfWeek.MONDAY)throw new IllegalArgumentException("起床安排必须从周一开始");monday=start;
    StringBuilder identity=new StringBuilder("week:"+start);
    for(int i=0;i<7;i++){
      ZonedDateTime wake=engine.wake(start.plusDays(i));
      if(!wake.toLocalDate().equals(start.plusDays(i))||wake.getSecond()!=0||wake.getNano()!=0)throw new IllegalArgumentException("整周闹钟需要当天的整分钟时间");
      int minute=wake.getHour()*60+wake.getMinute();minutes[i]=minute;groups.put(minute,groups.getOrDefault(minute,0)|(1<<i));identity.append('/').append(minute);
    }key=identity.toString();
  }
  static NativeWakeWeek forWeek(CourseEngine engine,LocalDate monday)throws Exception{return new NativeWakeWeek(engine,monday);}
  static NativeWakeWeek desired(CourseEngine engine,ZonedDateTime now)throws Exception{
    return desired(engine,now,0);
  }
  static NativeWakeWeek desired(CourseEngine engine,ZonedDateTime now,int backupMinutes)throws Exception{
    now=now.withZoneSameInstant(engine.zone);
    LocalDate monday=now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    NativeWakeWeek current=forWeek(engine,monday);
    return now.toInstant().toEpochMilli()>=current.refreshAt(engine,backupMinutes)?forWeek(engine,monday.plusWeeks(1)):current;
  }
  long refreshAt(CourseEngine engine,int backupMinutes)throws Exception{
    LocalDate sunday=monday.plusDays(6);ZonedDateTime handover=sunday.atTime(20,0).atZone(engine.zone);
    // Installing next week's Sunday bit before today's later time would ring again today.
    int nextSunday=forWeek(engine,monday.plusWeeks(1)).minutes[6];
    int latest=Math.max(minutes[6],nextSunday);
    ZonedDateTime safe=sunday.atStartOfDay(engine.zone).plusMinutes(latest+backupMinutes+RING_GRACE_MINUTES);
    return (safe.isAfter(handover)?safe:handover).toInstant().toEpochMilli();
  }
  String minutesJson(){JSONArray a=new JSONArray();for(int m:minutes)a.put(m);return a.toString();}
  String range(){return monday+" 至 "+monday.plusDays(6);}
  static long holdUntil(String previousMinutes,NativeWakeWeek proposed,ZonedDateTime now){
    return holdUntil(previousMinutes,proposed,now,0,0);
  }
  static long holdUntil(String previousMinutes,NativeWakeWeek proposed,ZonedDateTime now,int previousBackup,int proposedBackup){
    try{
      JSONArray a=new JSONArray(previousMinutes);if(a.length()!=7)return 0;long hold=0;
      for(int offset=-1;offset<=0;offset++){
        LocalDate day=now.toLocalDate().plusDays(offset);int old=a.getInt(day.getDayOfWeek().getValue()-1);if(old<0||old>=1440)return 0;
        ZonedDateTime alarm=day.atStartOfDay(now.getZone()).plusMinutes(old),until=alarm.plusMinutes(previousBackup+RING_GRACE_MINUTES);
        if(!now.isBefore(alarm)&&now.isBefore(until))hold=Math.max(hold,until.toInstant().toEpochMilli());
        if(offset==0&&proposed!=null&&!now.isBefore(alarm)){
          ZonedDateTime replacement=day.atStartOfDay(now.getZone()).plusMinutes(proposed.minutes[day.getDayOfWeek().getValue()-1]);
          if(replacement.plusMinutes(proposedBackup).isAfter(now))hold=Math.max(hold,replacement.plusMinutes(proposedBackup+RING_GRACE_MINUTES).toInstant().toEpochMilli());
        }
      }return hold;
    }catch(Exception ex){return 0;}
  }
}
