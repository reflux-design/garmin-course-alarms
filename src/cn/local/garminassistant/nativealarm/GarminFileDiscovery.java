package cn.local.garminassistant.nativealarm;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Original minimal reader. No create, upload, delete, sync-filter, or settings command. */
final class GarminFileDiscovery {
  interface Link { void send(byte[] frame) throws Exception; byte[] receive(long timeoutMs) throws Exception; }
  private final Link link;private final Consumer<String> log;private int product=-1;
  GarminFileDiscovery(Link link,Consumer<String> log){this.link=link;this.log=log;}
  boolean modelVerified(){return product==3992;}
  private static ByteBuffer le(int n){return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);}
  static ByteBuffer payload(byte[] frame){
    if(frame.length<6||frame.length>16384||GarminWire.crc(frame,frame.length)!=0)throw new IllegalArgumentException("Invalid GFDI checksum");
    ByteBuffer b=ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
    if((b.getShort()&65535)!=frame.length)throw new IllegalArgumentException("Invalid GFDI length");
    b.limit(frame.length-2);return b;
  }
  private ByteBuffer next(long deadline)throws Exception{
    while(true){long left=deadline-System.nanoTime()/1000000;if(left<=0)throw new IOException("Response timeout");
      byte[] f=link.receive(left);if(f==null)throw new IOException("Response timeout");ByteBuffer b=payload(f);int id=b.getShort()&65535;
      if(id==5024){int protocol=b.getShort()&65535;product=b.getShort()&65535;log.accept("DEVICE protocol="+protocol+" product="+product);if(product!=3992)throw new IOException("Unexpected model");continue;}
      b.position(2);return b;
    }
  }
  private ByteBuffer response(int request,long deadline)throws Exception{
    while(true){ByteBuffer b=next(deadline);int id=b.getShort()&65535;
      if(id==5000&&b.remaining()>=3&&(b.getShort()&65535)==request)return b;
      log.accept("IGNORED message="+id);
    }
  }
  byte[] readSettings()throws Exception{
    link.send(GarminWire.frame(5031));ByteBuffer types=response(5031,System.nanoTime()/1000000+10000);
    if((types.get()&255)!=0)throw new IOException("File types rejected");int count=types.get()&255;
    for(int i=0;i<count;i++){int type=types.get()&255,sub=types.get()&255,len=types.get()&255;if(len>types.remaining())throw new IOException("Invalid type name");byte[] name=new byte[len];types.get(name);String label=new String(name,StandardCharsets.UTF_8);if(!label.matches("[A-Za-z0-9_ .-]{0,100}"))label="(non-ASCII type)";log.accept("FILE_TYPE "+type+"/"+sub+" "+label);}
    if(types.hasRemaining())throw new IOException("Trailing file types data");
    if(product!=3992)throw new IOException("Model not verified");
    byte[] directory=download(0,262144);if(directory.length%16!=0)throw new IOException("Invalid directory length");
    ByteBuffer entries=ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN);int index=-1,size=-1,settingsCount=0;
    while(entries.hasRemaining()){
      int entry=entries.getShort()&65535,type=entries.get()&255,sub=entries.get()&255;entries.getShort();entries.get();entries.get();int bytes=entries.getInt();entries.getInt();
      if(type==128&&sub==2){index=entry;size=bytes;settingsCount++;log.accept("SETTINGS_ENTRY index="+index+" bytes="+size);}
    }
    log.accept("DIRECTORY entries="+directory.length/16+" settings="+settingsCount);
    if(settingsCount!=1||index<=0||size<14||size>131072)throw new IOException("Settings not uniquely readable");
    byte[] settings=download(index,131072);if(settings.length!=size)throw new IOException("Settings size changed");return settings;
  }
  private byte[] download(int index,int limit)throws Exception{
    ByteBuffer request=le(14).putShort((short)index).putInt(0).put((byte)1).putShort((short)0).putInt(0).put((byte)0);
    link.send(GarminWire.frame(5002,request.array()));long deadline=System.nanoTime()/1000000+45000;
    ByteBuffer response=response(5002,deadline);int status=response.get()&255;if(status!=0)throw new IOException("Download response="+status);
    int transfer=response.get()&255,expected=response.getInt();log.accept("DOWNLOAD index="+index+" status="+transfer+" bytes="+expected);
    if(transfer!=0||expected<0||expected>limit)throw new IOException("Download unavailable or exceeds bound");
    ByteArrayOutputStream out=new ByteArrayOutputStream(expected);int crc=0;
    while(out.size()<expected){ByteBuffer data=next(deadline);int id=data.getShort()&65535;if(id!=5004){log.accept("IGNORED message="+id);continue;}
      if(data.remaining()<7)throw new IOException("Short file chunk");int flags=data.get()&255,checksum=data.getShort()&65535,offset=data.getInt();byte[] chunk=new byte[data.remaining()];data.get(chunk);
      if(flags!=0||offset!=out.size()||chunk.length==0||chunk.length>expected-out.size())throw new IOException("Invalid file chunk range");
      int nextCrc=GarminWire.crc(chunk,chunk.length,crc);if(nextCrc!=checksum)throw new IOException("File chunk CRC mismatch");crc=nextCrc;out.write(chunk);
      ByteBuffer ack=le(8).putShort((short)5004).put((byte)0).put((byte)0).putInt(out.size());link.send(GarminWire.frame(5000,ack.array()));
    }
    log.accept("DOWNLOAD_COMPLETE index="+index+" bytes="+out.size());return out.toByteArray();
  }
}
