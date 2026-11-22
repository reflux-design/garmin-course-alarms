package cn.local.garminassistant.nativealarm;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
public final class NativeAlarmProtocolTests {
  static byte[] proto(int id,int serial,byte[] value){return GarminWire.frame(id,ByteBuffer.allocate(14+value.length).order(ByteOrder.LITTLE_ENDIAN).putShort((short)serial).putInt(0).putInt(value.length).putInt(value.length).put(value).array());}
  static byte[] service(int field,byte[] value){return GarminFileAccess.bytes(43,GarminFileAccess.bytes(field,value));}
  static final class Link implements GarminFileDiscovery.Link {
    final Queue<byte[]> queue=new ArrayDeque<>();final int mode;boolean canceled;
    Link(int mode){this.mode=mode;}
    public void send(byte[] frame){
      ByteBuffer b=GarminFileDiscovery.payload(frame);int id=b.getShort()&65535;
      if(id==5043){int serial=b.getShort()&65535;b.getInt();b.getInt();int n=b.getInt();byte[] p=new byte[n];b.get(p);
        Map<Integer,List<Object>> fields=GarminFileAccess.child(GarminFileAccess.parse(p),43);
        byte[] response;
        if(fields.containsKey(3))response=service(4,GarminFileAccess.concat(GarminFileAccess.number(1,0),GarminFileAccess.number(2,0),GarminFileAccess.number(3,0),GarminFileAccess.number(4,77),GarminFileAccess.number(8,11)));
        else if(fields.containsKey(18)){canceled=true;response=service(19,GarminFileAccess.number(1,0));}
        else throw new AssertionError("Unexpected request");
        queue.add(proto(5044,serial,response));
      }else if(id==5030){
        byte[] ack=GarminWire.frame(5000,ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN).putShort((short)5030).put((byte)(mode==5?1:0)).array());
        byte[] status=GarminFileAccess.number(2,mode==3?78:77);
        if(mode==2)status=GarminFileAccess.concat(status,GarminFileAccess.number(3,0));
        if(mode==1)queue.add(ack);
        if(mode!=4)queue.add(proto(5043,900,service(5,status)));
        if(mode!=1)queue.add(ack);
      }
    }
    public byte[] receive(long timeout){return queue.poll();}
  }
  public static void main(String[] args)throws Exception {
    byte[] payload=Files.readAllBytes(Paths.get(args[0]));
    AlarmProfile profile=FitFixtureTest.profile();
    for(int mode=0;mode<=5;mode++){
      Link link=new Link(mode);GarminFileAccess p=new GarminFileAccess(link,s->{});boolean success=false;
      try{p.uploadSettings(payload,(token,data)->{if(token!=77||data.length<=payload.length)throw new AssertionError();},profile,8);success=true;}catch(IOException expected){if(mode<2)throw expected;}
      if(success!=(mode<2)||link.canceled!=(mode>=2))throw new AssertionError("Transfer completion mode "+mode);
    }
    System.out.println("PASS success in both callback orders; failure reason zero, mismatched/missing token and rejected sync are failures");
  }
}
