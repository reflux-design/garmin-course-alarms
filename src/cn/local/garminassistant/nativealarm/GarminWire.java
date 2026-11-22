package cn.local.garminassistant.nativealarm;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.io.IOException;
import java.util.zip.Adler32;

/** Original bounded codec for a read-only GFDI request. */
final class GarminWire {
  static int crc(byte[] bytes,int length){return crc(bytes,length,0);}
  static int crc(byte[] bytes,int length,int seed){int c=seed;for(int i=0;i<length;i++){c^=bytes[i]&255;for(int j=0;j<8;j++)c=(c&1)!=0?(c>>>1)^0xA001:c>>>1;}return c;}
  static byte[] frame(int id){return frame(id,new byte[0]);}
  static byte[] frame(int id,byte[] payload){byte[] b=new byte[payload.length+6];b[0]=(byte)b.length;b[1]=(byte)(b.length>>8);b[2]=(byte)id;b[3]=(byte)(id>>8);System.arraycopy(payload,0,b,4,payload.length);int c=crc(b,b.length-2);b[b.length-2]=(byte)c;b[b.length-1]=(byte)(c>>8);return b;}
  static byte[] encode(byte[] b){
    byte[] out=new byte[b.length+b.length/254+4];int index=2,codeAt=1,count=1;
    for(byte v:b){if(v==0){out[codeAt]=(byte)count;codeAt=index++;count=1;}else{out[index++]=v;count++;if(count==255){out[codeAt]=(byte)count;codeAt=index++;count=1;}}}
    out[codeAt]=(byte)count;out[index++]=0;return Arrays.copyOf(out,index);
  }
  // Input contains only bytes between the zero delimiters.
  static byte[] decode(byte[] b){
    ByteArrayOutputStream out=new ByteArrayOutputStream();int i=0;
    while(i<b.length){int count=b[i++]&255;if(count==0||i+count-1>b.length)throw new IllegalArgumentException("COBS");for(int j=1;j<count;j++){if(b[i]==0)throw new IllegalArgumentException("COBS zero");out.write(b[i++]);}if(count!=255&&i<b.length)out.write(0);}
    return out.toByteArray();
  }
    static byte[] storedZlib(byte[] data,int window)throws IOException {
        if(window<8||window>15||data.length>131072)throw new IOException("Unsupported compression size");
        ByteArrayOutputStream out=new ByteArrayOutputStream();int cmf=((window-8)<<4)|8;
        out.write(cmf);out.write((31-((cmf<<8)%31))%31);
        for(int p=0;p<data.length;p+=65535){int n=Math.min(65535,data.length-p);out.write(p+n==data.length?1:0);out.write(n&255);out.write(n>>8);out.write((~n)&255);out.write(((~n)>>8)&255);out.write(data,p,n);}
        Adler32 checksum=new Adler32();checksum.update(data);long adler=checksum.getValue();for(int shift=24;shift>=0;shift-=8)out.write((int)(adler>>shift)&255);return out.toByteArray();
    }
}
