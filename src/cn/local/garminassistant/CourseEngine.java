package cn.local.garminassistant;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.json.*;
public final class CourseEngine {
  final ZoneId zone;
  final JSONObject data;
  final LocalDate start;
  public CourseEngine(JSONObject value)throws JSONException{
    data=new JSONObject(value.toString());zone=ZoneId.of(data.getString("timezone"));start=LocalDate.parse(data.getString("term_start"));
    if(start.getDayOfWeek()!=DayOfWeek.MONDAY)throw new IllegalArgumentException("学期第一周日期必须为周一");
    int lead=data.getInt("lead_minutes");if(lead<0||lead>600)throw new IllegalArgumentException("提前量须在0～600分钟之间");
    LocalTime.parse(data.getString("no_class_wake"));
    JSONObject fixed=data.optJSONObject("fixed_wake");
    if(fixed!=null){LocalTime.parse(fixed.getString("class_start"));LocalTime.parse(fixed.getString("early_wake"));LocalTime.parse(fixed.getString("other_wake"));}
    JSONArray courses=data.getJSONArray("courses");Set<String> ids=new HashSet<>();
    for(int i=0;i<courses.length();i++){JSONObject c=courses.getJSONObject(i);validateCourse(c);if(!ids.add(c.getString("id")))throw new IllegalArgumentException("课程ID重复");if(c.getInt("weekday")<1||c.getInt("weekday")>7)throw new IllegalArgumentException("星期须在1～7之间");JSONArray weeks=c.getJSONArray("weeks");if(weeks.length()==0)throw new IllegalArgumentException("课程周次不能为空");for(int j=0;j<weeks.length();j++)if(weeks.getInt(j)<1||weeks.getInt(j)>60)throw new IllegalArgumentException("课程周次须在1～60之间");}
    JSONObject exceptions=data.has("exceptions")?data.getJSONObject("exceptions"):new JSONObject();
    for(Iterator<String> it=exceptions.keys();it.hasNext();){String day=it.next();LocalDate.parse(day);JSONObject ex=exceptions.getJSONObject(day);JSONArray removed=ex.has("cancel_ids")?ex.getJSONArray("cancel_ids"):new JSONArray();Set<String> canceled=new HashSet<>();for(int i=0;i<removed.length();i++){String id=removed.getString(i);if(!ids.contains(id))throw new IllegalArgumentException("取消的课程ID不存在："+id);canceled.add(id);}JSONArray add=ex.has("add")?ex.getJSONArray("add"):new JSONArray();Set<String> added=new HashSet<>();for(int i=0;i<add.length();i++){JSONObject c=add.getJSONObject(i);validateCourse(c);String id=c.getString("id");if(!added.add(id)||(ids.contains(id)&&!canceled.contains(id)))throw new IllegalArgumentException("调课ID重复："+id);}}
    JSONObject overrides=data.has("wake_overrides")?data.getJSONObject("wake_overrides"):new JSONObject();for(Iterator<String> it=overrides.keys();it.hasNext();){String day=it.next();LocalDate.parse(day);LocalTime.parse(overrides.getString(day));}
  }
  static void validateCourse(JSONObject c)throws JSONException{if(c.getString("id").trim().isEmpty()||c.getString("name").trim().isEmpty())throw new IllegalArgumentException("课程ID和名称不能为空");LocalTime begin=LocalTime.parse(c.getString("start")),end=LocalTime.parse(c.getString("end"));if(!end.isAfter(begin))throw new IllegalArgumentException("课程结束时间须晚于开始");}
  public List<JSONObject> on(LocalDate date)throws JSONException{
    int week=(int)Math.floorDiv(ChronoUnit.DAYS.between(start,date),7)+1;
    List<JSONObject> result=new ArrayList<>();
    JSONObject exception=data.optJSONObject("exceptions");exception=exception==null?null:exception.optJSONObject(date.toString());
    JSONArray canceled=exception==null?null:exception.optJSONArray("cancel_ids");Set<String> removed=new HashSet<>();if(canceled!=null)for(int i=0;i<canceled.length();i++)removed.add(canceled.getString(i));
    JSONArray courses=data.getJSONArray("courses");
    for(int i=0;i<courses.length();i++){JSONObject c=courses.getJSONObject(i);if(removed.contains(c.getString("id"))||c.getInt("weekday")!=date.getDayOfWeek().getValue())continue;JSONArray weeks=c.getJSONArray("weeks");for(int j=0;j<weeks.length();j++)if(weeks.getInt(j)==week){result.add(c);break;}}
    JSONArray added=exception==null?null:exception.optJSONArray("add");if(added!=null)for(int i=0;i<added.length();i++){JSONObject c=added.getJSONObject(i);LocalTime.parse(c.getString("start"));LocalTime.parse(c.getString("end"));result.add(c);}
    result.sort(Comparator.comparing(c->c.optString("start")));return result;
  }
  public ZonedDateTime wake(LocalDate date)throws JSONException{
    JSONObject overrides=data.optJSONObject("wake_overrides");String specific=overrides==null?null:overrides.optString(date.toString(),null);
    if(specific!=null)return date.atTime(LocalTime.parse(specific)).atZone(zone);
    List<JSONObject> courses=on(date);
    JSONObject fixed=data.optJSONObject("fixed_wake");
    if(fixed!=null){boolean early=!courses.isEmpty()&&LocalTime.parse(courses.get(0).getString("start")).equals(LocalTime.parse(fixed.getString("class_start")));return date.atTime(LocalTime.parse(fixed.getString(early?"early_wake":"other_wake"))).atZone(zone);}
    return courses.isEmpty()?date.atTime(LocalTime.parse(data.getString("no_class_wake"))).atZone(zone):date.atTime(LocalTime.parse(courses.get(0).getString("start"))).atZone(zone).minusMinutes(data.getInt("lead_minutes"));
  }
}
