package cn.local.garminassistant.nativealarm;

import java.io.*;
import java.util.*;

/** FR255 alarm records only. Unknown alarm fields are retained byte for byte. */
public final class NativeAlarmFit {
    private static final class Field { int id,size,base,offset; }
    private static final class Record {
        int number; boolean big; byte[] data;
        List<Field> fields=new ArrayList<>();
        Field field(int id,int size,int base)throws IOException {
            for(Field f:fields)if(f.id==id){if(f.size!=size||f.base!=base)break;return f;}
            throw new IOException("Unexpected FIT field schema: "+id);
        }
        long value(int id,int size,int base)throws IOException {Field f=field(id,size,base);return uint(data,f.offset,size,big);}
        void put(int id,int size,int base,long value)throws IOException {Field f=field(id,size,base);for(int i=0;i<size;i++)data[f.offset+i]=(byte)(value>>>((big?size-i-1:i)*8));}
        Record copy(){Record r=new Record();r.number=number;r.big=big;r.fields=fields;r.data=data.clone();return r;}
    }
    private final Map<Integer,Record> alarms=new TreeMap<>();
    private final byte[] header;
    private final AlarmProfile profile;
    public static void verifyDevice(byte[] source,long expectedUnit)throws IOException {
        int count=0;for(Record r:parse(source))if(r.number==0){count++;if(r.value(0,1,0)!=2||r.value(1,2,132)!=1||r.value(2,2,132)!=3992||r.value(3,4,140)!=expectedUnit)throw new IOException("Watch identity mismatch");}
        if(count!=1)throw new IOException("Ambiguous watch identity");
    }
    public NativeAlarmFit(byte[] source, AlarmProfile profile) throws IOException {
        this.profile = profile;
        verifyDevice(source, profile.unit);
        header = Arrays.copyOf(source, 14);
        for (Record record : parse(source)) {
            if (record.number != 222) continue;
            int index = (int) record.value(254, 2, 132);
            if (index < 0 || index > 9 || alarms.put(index, record) != null) {
                throw new IOException("Invalid alarm slot index");
            }
        }
        if (alarms.size() != 10) throw new IOException("Incomplete watch alarm snapshot");
        for (int index : profile.managedSlots) {
            long enabled = alarms.get(index).value(2, 1, 0);
            if (enabled > 1 || (enabled == 1 && !profile.replaceEnabledSlots.contains(index))) {
                throw new IOException("Managed slot is enabled without enrollment approval: " + index);
            }
        }
        Record template = alarms.get(profile.alertTemplateSlot);
        if (template.value(0, 2, 132) >= 1440) throw new IOException("Alert template is not an existing alarm");
    }

    public byte[] week(Map<Integer,Integer> primary, int backupMinutes, long unixSeconds) throws IOException {
        Map<Integer,Integer> groups = AlarmTimes.combine(primary, backupMinutes, profile.managedSlots.size());
        Iterator<Map.Entry<Integer,Integer>> times = groups.entrySet().iterator();
        List<Record> records = new ArrayList<>();
        for (int slot : profile.managedSlots) {
            if (times.hasNext()) {
                Map.Entry<Integer,Integer> alarm = times.next();
                Record record = alarms.get(profile.alertTemplateSlot).copy();
                record.put(254, 2, 132, slot);
                record.put(0, 2, 132, alarm.getKey());
                record.put(1, 4, 140, alarm.getValue());
                record.put(2, 1, 0, 1);
                records.add(record);
            } else {
                Record record = alarms.get(slot).copy();
                record.put(2, 1, 0, 0);
                records.add(record);
            }
        }
        byte[] encoded = encode(records, unixSeconds);
        validateUpdatePayload(encoded, profile, backupMinutes);
        return encoded;
    }

    private byte[] encode(List<Record> selected,long unixSeconds)throws IOException {
        if(unixSeconds<631065600L||unixSeconds-631065600L>0xfffffffeL)throw new IOException("Invalid FIT creation time");
        ByteArrayOutputStream payload=new ByteArrayOutputStream();
        // FileId identifies a Connect-generated settings delta, as verified on the watch.
        payload.write(new byte[]{0x40,0,0,0,0,6,0,1,0,1,2,(byte)132,2,2,(byte)132,3,4,(byte)140,4,4,(byte)134,5,2,(byte)132});
        payload.write(0);payload.write(2);little(payload,1,2);little(payload,65534,2);little(payload,1,4);little(payload,unixSeconds-631065600L,4);little(payload,1,2);
        for(Record r:selected){payload.write(0x41);payload.write(0);payload.write(r.big?1:0);number(payload,r.number,2,r.big);payload.write(r.fields.size());
            for(Field f:r.fields){payload.write(f.id);payload.write(f.size);payload.write(f.base);}payload.write(1);payload.write(r.data);
        }
        byte[] body=payload.toByteArray(),h=header.clone();h[0]=14;for(int i=0;i<4;i++)h[4+i]=(byte)(body.length>>>(i*8));int hc=crc(h,12);h[12]=(byte)hc;h[13]=(byte)(hc>>8);
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(h);out.write(body);byte[] pre=out.toByteArray();little(out,crc(pre,pre.length),2);
        return out.toByteArray();
    }
    static void validateUpdatePayload(byte[] data, AlarmProfile profile, int backupMinutes) throws IOException {
        Set<Integer> slots = new TreeSet<>();
        int identities = 0;
        List<List<Integer>> days = new ArrayList<>();
        for (int day = 0; day < 7; day++) days.add(new ArrayList<>());
        for (Record record : parse(data)) {
            if (record.number == 0) {
                if (record.value(0, 1, 0) != 2 || record.value(1, 2, 132) != 1
                        || record.value(2, 2, 132) != 65534 || record.value(3, 4, 140) != 1) {
                    throw new IOException("Invalid update identity");
                }
                identities++;
                continue;
            }
            if (record.number != 222) throw new IOException("Non-alarm settings in update");
            int slot = (int) record.value(254, 2, 132);
            long minute = record.value(0, 2, 132);
            long mask = record.value(1, 4, 140);
            long enabled = record.value(2, 1, 0);
            if (!profile.managedSlots.contains(slot) || !slots.add(slot) || enabled > 1 || mask > 128
                    || (minute >= 1440 && !(enabled == 0 && minute == 65535))
                    || (enabled == 1 && (mask < 1 || mask > 127))) {
                throw new IOException("Invalid or protected alarm slot");
            }
            if (enabled == 1) {
                for (int day = 0; day < 7; day++) if ((mask & (1 << day)) != 0) days.get(day).add((int) minute);
            }
        }
        if (identities != 1 || data.length > 2048 || !slots.equals(profile.managedSlots)) {
            throw new IOException("Incomplete managed alarm update");
        }
        int count = backupMinutes == 0 ? 1 : 2;
        for (List<Integer> alarms : days) {
            if (alarms.size() != count || (count == 2 && Math.abs(alarms.get(1) - alarms.get(0)) != backupMinutes)) {
                throw new IOException("Inconsistent weekday coverage or backup offset");
            }
        }
    }
    private static List<Record> parse(byte[] data)throws IOException {
        validate(data);Map<Integer,Record> defs=new HashMap<>();List<Record> out=new ArrayList<>();int p=14,end=data.length-2;
        while(p<end){int h=u(data,p++);if((h&0x80)!=0||(h&0x20)!=0)throw new IOException("Unsupported FIT record encoding");int local=h&15;
            if((h&0x40)!=0){Record r=new Record();if(u(data,p++)!=0)throw new IOException("Invalid FIT reserved byte");int arch=u(data,p++);if(arch>1)throw new IOException("Invalid FIT byte order");r.big=arch==1;r.number=(int)uint(data,p,2,r.big);p+=2;int count=u(data,p++),offset=0;Set<Integer> ids=new HashSet<>();
                for(int i=0;i<count;i++){Field f=new Field();f.id=u(data,p++);f.size=u(data,p++);f.base=u(data,p++);f.offset=offset;offset+=f.size;if(!ids.add(f.id)||f.size==0)throw new IOException("Invalid FIT field");r.fields.add(f);}r.data=new byte[offset];defs.put(local,r);
            }else{Record def=defs.get(local);if(def==null||def.data.length>end-p)throw new IOException("Truncated FIT record");Record r=def.copy();System.arraycopy(data,p,r.data,0,r.data.length);p+=r.data.length;out.add(r);}
        }
        if(p!=end)throw new IOException("Invalid FIT boundary");return out;
    }
    private static int u(byte[] b,int p)throws IOException {if(p<0||p>=b.length-2)throw new IOException("Truncated FIT");return b[p]&255;}
    private static long uint(byte[] b,int p,int size,boolean big)throws IOException {if(p<0||p+size>b.length)throw new IOException("Truncated integer");long n=0;for(int i=0;i<size;i++)n|=(b[p+i]&255L)<<((big?size-i-1:i)*8);return n;}
    private static void number(ByteArrayOutputStream b,long n,int size,boolean big){for(int i=0;i<size;i++)b.write((int)(n>>>((big?size-i-1:i)*8))&255);}
    private static void little(ByteArrayOutputStream b,long n,int size){number(b,n,size,false);}
    static int crc(byte[] b,int count){int c=0;for(int i=0;i<count;i++){c^=b[i]&255;for(int k=0;k<8;k++)c=(c&1)!=0?(c>>>1)^0xa001:c>>>1;}return c;}
    static void validate(byte[] b)throws IOException {if(b.length<16||b.length>131072||b[0]!=14||b[8]!='.'||b[9]!='F'||b[10]!='I'||b[11]!='T'||uint(b,4,4,false)!=b.length-16||crc(b,14)!=0||crc(b,b.length)!=0)throw new IOException("Invalid FIT length or CRC");}
}
