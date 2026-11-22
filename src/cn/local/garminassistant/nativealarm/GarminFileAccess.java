package cn.local.garminassistant.nativealarm;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Minimal file-access protocol for a temporary, bounded settings experiment. */
final class GarminFileAccess {
  interface Download { byte[] read(int token,int expected) throws Exception; }
  interface Upload { void write(int token,byte[] compressed) throws Exception; }
  private final GarminFileDiscovery.Link link;private final Consumer<String> log;
  private int sequence=100;
  private int awaitedUpload=-1;private boolean uploadStatusSeen;private IOException uploadFailure;
  GarminFileAccess(GarminFileDiscovery.Link link,Consumer<String> log){this.link=link;this.log=log;}
  static byte[] concat(byte[]... parts){ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] p:parts)b.write(p,0,p.length);return b.toByteArray();}
  static byte[] var(long v){ByteArrayOutputStream b=new ByteArrayOutputStream();while((v&~127L)!=0){b.write((int)(v&127)|128);v>>>=7;}b.write((int)v);return b.toByteArray();}
  static byte[] number(int tag,long v){return concat(var(tag<<3),var(v));}
  static byte[] fixed64(int tag,long v){return concat(var((tag<<3)|1),ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array());}
  static byte[] bytes(int tag,byte[] v){return concat(var((tag<<3)|2),var(v.length),v);}
  static byte[] string(int tag,String v){return bytes(tag,v.getBytes(StandardCharsets.UTF_8));}
  static long readVar(ByteBuffer b){long v=0;for(int n=0;n<10;n++){if(!b.hasRemaining())throw new IllegalArgumentException("Truncated varint");int x=b.get()&255;if(n==9&&x>1)throw new IllegalArgumentException("Overflow varint");v|=(long)(x&127)<<(n*7);if(x<128)return v;}throw new IllegalArgumentException("Overflow varint");}
  static Map<Integer,List<Object>> parse(byte[] data){
    if(data.length>65536)throw new IllegalArgumentException("Protobuf too large");
    ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);Map<Integer,List<Object>> out=new LinkedHashMap<>();
    while(b.hasRemaining()){long key=readVar(b);int tag=(int)(key>>>3),wire=(int)(key&7);if(tag<=0||key>0xffffffffL)throw new IllegalArgumentException("Invalid tag");Object value;
      if(wire==0)value=readVar(b);else if(wire==1)value=b.getLong();else if(wire==5)value=(long)b.getInt();else if(wire==2){long n=readVar(b);if(n<0||n>b.remaining())throw new IllegalArgumentException("Invalid length");byte[] chunk=new byte[(int)n];b.get(chunk);value=chunk;}else throw new IllegalArgumentException("Unknown wire type");
      out.computeIfAbsent(tag,k->new ArrayList<>()).add(value);
    }return out;
  }
  static Object one(Map<Integer,List<Object>> m,int tag){List<Object> values=m.get(tag);return values==null||values.size()!=1?null:values.get(0);}
  static Map<Integer,List<Object>> child(Map<Integer,List<Object>> m,int tag){Object v=one(m,tag);if(!(v instanceof byte[]))throw new IllegalArgumentException("Missing submessage "+tag);return parse((byte[])v);}
  private byte[] request(int requestLabel,byte[] proto)throws Exception{
    int serial=sequence++;
    ByteBuffer payload=ByteBuffer.allocate(14+proto.length).order(ByteOrder.LITTLE_ENDIAN).putShort((short)serial).putInt(0).putInt(proto.length).putInt(proto.length).put(proto);
    link.send(GarminWire.frame(5043,payload.array()));long end=System.nanoTime()/1000000+15000;ByteArrayOutputStream assembled=new ByteArrayOutputStream();int expected=-1;
    while(System.nanoTime()/1000000<end){byte[] raw=link.receive(Math.max(1,end-System.nanoTime()/1000000));if(raw==null)break;ByteBuffer b=GarminFileDiscovery.payload(raw);int id=b.getShort()&65535;
      if(id==5000){if(b.remaining()>=3){int response=b.getShort()&65535,status=b.get()&255;if(response==5043&&status!=0)throw new IOException("Protobuf rejected "+status);}continue;}
      if(id==5043){handleIncoming(b);continue;}
      if(id!=5044||b.remaining()<14)continue;int req=b.getShort()&65535,offset=b.getInt(),total=b.getInt(),count=b.getInt();if(req!=serial)continue;
      if(total<0||total>65536||count!=b.remaining()||offset!=assembled.size()||count>total-offset||(expected>=0&&total!=expected))throw new IOException("Invalid protobuf range");
      expected=total;byte[] chunk=new byte[count];b.get(chunk);assembled.write(chunk);
      ByteBuffer ack=ByteBuffer.allocate(offset==0&&count==total?3:11).order(ByteOrder.LITTLE_ENDIAN).putShort((short)5044).put((byte)0);
      if(ack.capacity()>3)ack.putShort((short)serial).putInt(offset).put((byte)0).put((byte)0);
      link.send(GarminWire.frame(5000,ack.array()));if(assembled.size()==total)return assembled.toByteArray();
    }throw new IOException("Protobuf timeout");
  }
  private void handleIncoming(ByteBuffer b)throws Exception{
    if(b.remaining()<14)return;int serial=b.getShort()&65535,offset=b.getInt(),total=b.getInt(),count=b.getInt();
    if(offset!=0||count!=total||total<0||total>65536||count!=b.remaining()){log.accept("V2_INCOMING fragmented_or_invalid");return;}
    byte[] raw=new byte[count];b.get(raw);Map<Integer,List<Object>> smart=parse(raw);
    if(!(one(smart,43) instanceof byte[]))return;Map<Integer,List<Object>> service=child(smart,43);
    log.accept("V2_INCOMING file_access_fields="+service.keySet());
    if(!(one(service,5) instanceof byte[]))return;
    Map<Integer,List<Object>> status=child(service,5);
    log.accept("V2_TRANSFER_STATUS token="+one(status,2)+" failure="+one(status,3)+" transport_status="+one(status,4)+" provider="+one(status,5)+" error="+one(status,6));
    link.send(GarminWire.frame(5000,ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN).putShort((short)5043).put((byte)0).array()));
    byte[] reply=bytes(43,bytes(21,number(1,1)));
    link.send(GarminWire.frame(5044,ByteBuffer.allocate(14+reply.length).order(ByteOrder.LITTLE_ENDIAN).putShort((short)serial).putInt(0).putInt(reply.length).putInt(reply.length).put(reply).array()));
    if(Long.valueOf(awaitedUpload).equals(one(status,2))){
      uploadStatusSeen=true;
      // FailureReason 0 means TRANSPORT_FAILED; presence, not a nonzero value, is the error.
      if(status.containsKey(3)||status.containsKey(6)||(one(status,4)!=null&&!Long.valueOf(0).equals(one(status,4))))uploadFailure=new IOException("Watch reported failed alarm transfer");
    }
  }
  void completeSync()throws Exception{
    link.send(GarminWire.frame(5030,new byte[]{0,0}));long end=System.nanoTime()/1000000+5000;boolean syncAcknowledged=false;
    while(System.nanoTime()/1000000<end){byte[] raw=link.receive(Math.max(1,end-System.nanoTime()/1000000));if(raw==null)break;ByteBuffer b=GarminFileDiscovery.payload(raw);int id=b.getShort()&65535;
      if(id==5043)handleIncoming(b);
      if(id==5000&&b.remaining()>=3){int req=b.getShort()&65535,status=b.get()&255;if(req==5030){log.accept("SYNC_COMPLETE status="+status);if(status!=0)throw new IOException("Sync completion rejected");syncAcknowledged=true;}}
      if(uploadFailure!=null)throw uploadFailure;
      if(syncAcknowledged&&uploadStatusSeen)return;
    }throw new IOException("Complete alarm transfer acknowledgement not received");
  }
  byte[] readSettings(Download download)throws Exception{
    byte[] type=concat(number(1,0),string(2,"FIT_TYPE_2"));
    byte[] list=concat(number(3,8),bytes(6,type));
    Map<Integer,List<Object>> result=child(child(parse(request(81,bytes(43,bytes(9,list)))),43),10);
    List<Object> items=result.get(4);
    log.accept("V2_SETTINGS_LIST status="+one(result,1)+" items="+(items==null?0:items.size()));
    if(!Long.valueOf(0).equals(one(result,1))||items==null||items.size()!=1||!(items.get(0) instanceof byte[]))throw new IOException("Settings not unique");
    byte[] item=(byte[])items.get(0);Map<Integer,List<Object>> info=parse(item),dataType=child(info,2);
    Object name=one(dataType,2),size=one(info,3);
    if(!(name instanceof byte[])||!Arrays.equals((byte[])name,"FIT_TYPE_2".getBytes(StandardCharsets.UTF_8))
        ||!(size instanceof Long)||(Long)size<14||(Long)size>131072||!(one(info,1) instanceof byte[]))throw new IOException("Unexpected Settings reference");
    log.accept("V2_SETTINGS_ITEM bytes="+size);
    byte[] pull=concat(bytes(1,item),number(2,24),number(3,0),number(4,0),number(5,15));
    Map<Integer,List<Object>> reply=child(child(parse(request(82,bytes(43,bytes(1,pull)))),43),2);
    Object token=one(reply,3),window=one(reply,7);
    log.accept("V2_PULL status="+one(reply,1)+" transport="+one(reply,2)+" token="+token+" window="+window);
    if(!Long.valueOf(0).equals(one(reply,1))||!(token instanceof Long)||(Long)token<0||(Long)token>65535)throw new IOException("Pull rejected or invalid token");
    try {
      if(!Long.valueOf(0).equals(one(reply,2))||!(window instanceof Long)||(Long)window<8||(Long)window>15)throw new IOException("Unsupported transport or zlib window");
      return download.read(((Long)token).intValue(),((Long)size).intValue());
    }
    catch(Exception failure) {
      // Cancel only the transfer reserved by this read request, never another client's session.
      try { Map<Integer,List<Object>> canceled=child(child(parse(request(83,bytes(43,bytes(18,number(1,(Long)token))))),43),19);log.accept("V2_CANCEL status="+one(canceled,1)); }
      catch(Exception cancelFailure){log.accept("V2_CANCEL unavailable="+cancelFailure.getClass().getSimpleName());}
      throw failure;
    }
  }
  void uploadSettings(byte[] settings,Upload upload,AlarmProfile profile,int backupMinutes)throws Exception{
    NativeAlarmFit.validateUpdatePayload(settings,profile,backupMinutes);
    byte[] uid=concat(fixed64(1,-1L),fixed64(2,new java.security.SecureRandom().nextLong()));
    byte[] type=concat(number(1,0),string(2,"FIT_TYPE_2"));
    byte[] item=concat(bytes(1,uid),bytes(2,type),number(3,settings.length));
    byte[] reserve=concat(bytes(1,item),number(2,34),number(3,0),number(4,15));
    Map<Integer,List<Object>> reply=child(child(parse(request(94,bytes(43,bytes(3,reserve)))),43),4);
    Object token=one(reply,4),window=one(reply,8);
    log.accept("V2_PUSH status="+one(reply,1)+" offset="+one(reply,2)+" transport="+one(reply,3)+" window="+window);
    if(!Long.valueOf(0).equals(one(reply,1))||!(token instanceof Long)||(Long)token<0||(Long)token>65535)throw new IOException("Push reservation failed");
    awaitedUpload=((Long)token).intValue();uploadStatusSeen=false;uploadFailure=null;
    try{
      if(!Long.valueOf(0).equals(one(reply,2))||!Long.valueOf(0).equals(one(reply,3))||!(window instanceof Long)||(Long)window<8||(Long)window>15)throw new IOException("Unsupported push response");
      upload.write(((Long)token).intValue(),GarminWire.storedZlib(settings,((Long)window).intValue()));
      completeSync();
    }catch(Exception failure){
      try{Map<Integer,List<Object>> canceled=child(child(parse(request(95,bytes(43,bytes(18,number(1,(Long)token))))),43),19);log.accept("V2_PUSH_CANCEL status="+one(canceled,1));}
      catch(Exception cancelFailure){log.accept("V2_PUSH_CANCEL unavailable="+cancelFailure.getClass().getSimpleName());}
      throw failure;
    }finally{awaitedUpload=-1;}
  }
}
