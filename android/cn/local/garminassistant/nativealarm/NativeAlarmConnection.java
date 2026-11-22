package cn.local.garminassistant.nativealarm;

import android.bluetooth.*;
import android.content.Context;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Arrays;

/** One short-lived, own-client connection. Never closes another client's handles. */
final class NativeAlarmConnection extends BluetoothGattCallback {
    private static final long CLIENT = 0x474149534C414E44L;
    private static final UUID SERVICE = uuid("2800");
    private final UUID rxId,txId;
    private volatile int redirectTo;
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Consumer<String> log;
    private final CountDownLatch ready = new CountDownLatch(1), closed = new CountDownLatch(1), unsubscribed = new CountDownLatch(1);
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx, rx;
    private BluetoothGattDescriptor descriptor;
    private volatile int handle = -1;
    private volatile boolean subscribed;
    private boolean cleanup;
    private volatile CountDownLatch writeDone=new CountDownLatch(0);
    private final CountDownLatch metadata=new CountDownLatch(1);
    private final ByteArrayOutputStream inbound=new ByteArrayOutputStream();
    private boolean framed;
    private final LinkedBlockingQueue<byte[]> frames=new LinkedBlockingQueue<>(128);
    private volatile int writeStatus;
    private static final int FILE_SERVICE=0x2018;
    private volatile int fileHandle=-1,fileReliable=-1,fileCloseStatus=-1;
    private volatile boolean fileIsClosed;
    private volatile CountDownLatch fileReady=new CountDownLatch(1),fileClosed=new CountDownLatch(1);
    private volatile FitDownload fileBuffer=new FitDownload();
    private volatile boolean fileReading=true;
    private final LinkedBlockingQueue<byte[]> filePackets=new LinkedBlockingQueue<>(16);
    interface Job {void run(GarminFileAccess protocol,GarminFileAccess.Download download,GarminFileAccess.Upload upload)throws Exception;}
    private final Job job;
    private boolean completed;
    boolean completed(){return completed;}
    NativeAlarmConnection(Context context, Consumer<String> log,Job job,int endpoint) {
        if(endpoint!=0x2810&&endpoint!=0x2811)throw new IllegalArgumentException("Unexpected ML endpoint");
        this.context=context;this.log=log;this.job=job;
        rxId=uuid(Integer.toHexString(endpoint));txId=uuid(Integer.toHexString(endpoint+0x10));
    }
    int redirectCharacteristic(){return redirectTo;}
    private static UUID uuid(String shortPart) { return UUID.fromString("6a4e" + shortPart + "-667b-11e3-949a-0800200c9a66"); }
    public void run() throws Exception {
        BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter();
        BluetoothDevice watch = null;
        for (BluetoothDevice device : adapter.getBondedDevices()) if ("Forerunner 255".equals(device.getName())) {
            if (watch != null) throw new IllegalStateException("Ambiguous watch");
            watch = device;
        }
        if (watch == null) { log.accept("RESULT no_bonded_watch"); return; }
        gatt = watch.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) { log.accept("RESULT no_gatt"); return; }
        try {
            log.accept("OPEN_WAIT completed=" + ready.await(15, TimeUnit.SECONDS));
            if (handle >= 0) {
                if(writeDone.await(3,TimeUnit.SECONDS)) {
                    GarminFileDiscovery.Link link=new GarminFileDiscovery.Link(){
                        public void send(byte[] frame)throws Exception{sendFrame(frame);}
                        public byte[] receive(long ms)throws Exception{return frames.poll(ms,TimeUnit.MILLISECONDS);}
                    };
                    // Device identity is checked from the downloaded FIT before any write.
                    GarminFileAccess protocol=new GarminFileAccess(link,log);
                    job.run(protocol,this::readTransfer,this::pushTransfer);completed=true;
                }
            }
        } finally {
            if(fileHandle>=0&&!fileIsClosed&&writeDone.await(3,TimeUnit.SECONDS)){
                sendManagement(2,FILE_SERVICE,fileHandle);
                log.accept("FILE_CLOSE_WAIT completed="+fileClosed.await(5,TimeUnit.SECONDS));
            }
            if(handle>=0&&writeDone.await(3,TimeUnit.SECONDS)){
                send(2, handle);
                log.accept("CLOSE_WAIT completed=" + closed.await(5, TimeUnit.SECONDS));
            }
            boolean idle=writeDone.await(3,TimeUnit.SECONDS);
            if (idle && subscribed && descriptor != null) {
                cleanup = true;
                int status = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                log.accept("UNSUBSCRIBE queued=" + status);
                if (status == 0) log.accept("UNSUBSCRIBE_WAIT completed=" + unsubscribed.await(4, TimeUnit.SECONDS));
            }
            if (rx != null) gatt.setCharacteristicNotification(rx, false);
            gatt.close(); log.accept("RESULT client_closed");
        }
    }
    @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
        log.accept("CONNECTION status=" + status + " state=" + state);
        if (status != 0 || state == BluetoothProfile.STATE_DISCONNECTED) { ready.countDown(); return; }
        if (state == BluetoothProfile.STATE_CONNECTED && !g.discoverServices()) ready.countDown();
    }
    @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
        BluetoothGattService service = g.getService(SERVICE);
        if (status != 0 || service == null) { ready.countDown(); return; }
        rx = service.getCharacteristic(rxId); tx = service.getCharacteristic(txId);
        if (rx == null || tx == null) { log.accept("RESULT channel_missing"); ready.countDown(); return; }
        descriptor = rx.getDescriptor(CCCD);
        if (descriptor == null || !g.readDescriptor(descriptor)) { log.accept("RESULT cccd_read_unavailable"); ready.countDown(); }
    }
    @Override public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int status, byte[] value) {
        if (status != 0 || value.length != 2 || (value[0] != 0 && value[0] != 1) || value[1] != 0) {
            log.accept("RESULT channel_unreadable status=" + status); ready.countDown(); return;
        }
        if (!g.setCharacteristicNotification(rx, true)) { ready.countDown(); return; }
        if (value[0] == 1) {
            log.accept("CCCD existing_notification_enabled_preserved");
            send(0, 0);
            return;
        }
        int queued = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        log.accept("SUBSCRIBE queued=" + queued);
        if (queued != 0) ready.countDown();
    }
    @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
        log.accept("CCCD_WRITE status=" + status + " cleanup=" + cleanup);
        if (cleanup) { unsubscribed.countDown(); return; }
        if (status != 0) { ready.countDown(); return; }
        subscribed = true;
        send(0, 0);
    }
    private void send(int type, int last) {
        sendManagement(type,1,last);
    }
    private void sendManagement(int type,int service,int last) {
        byte[] packet = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0).put((byte) type).putLong(CLIENT).putShort((short)service).put((byte) last).array();
        int queued = write(packet);
        log.accept("MANAGEMENT type=" + type + " service="+service+" queued=" + queued);
        if (queued != 0) ready.countDown();
    }
    private byte[] readTransfer(int token,int expected)throws Exception{
        openFile(true);
        if(!writeDone.await(3,TimeUnit.SECONDS))throw new java.io.IOException("GATT busy before file request");
        byte[] request=ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).put((byte)fileHandle).putShort((short)0).putShort((short)token).putShort((short)0).array();
        if(write(request)!=0||!writeDone.await(3,TimeUnit.SECONDS)||writeStatus!=0)throw new java.io.IOException("File request write failed");
        boolean completed=fileClosed.await(45,TimeUnit.SECONDS);
        log.accept("FILE_TRANSFER closed="+completed+" status="+fileCloseStatus+" compressed="+fileBuffer.size());
        if(!completed||fileCloseStatus!=0)throw new java.io.IOException("File transfer did not complete");
        return fileBuffer.finish(expected);
    }
    private void openFile(boolean reading)throws Exception{
        if(fileHandle>=0&&!fileIsClosed)throw new java.io.IOException("Previous file channel still active");
        fileHandle=-1;fileReliable=-1;fileCloseStatus=-1;fileIsClosed=false;fileReading=reading;
        fileReady=new CountDownLatch(1);fileClosed=new CountDownLatch(1);fileBuffer=new FitDownload();filePackets.clear();
        if(!writeDone.await(3,TimeUnit.SECONDS))throw new java.io.IOException("GATT busy before file registration");
        sendManagement(0,FILE_SERVICE,0);
        if(!fileReady.await(10,TimeUnit.SECONDS)||fileHandle<0||fileReliable!=0)throw new java.io.IOException("Plain ML file channel unavailable");
    }
    private void writeFileBytes(byte[] data)throws Exception{
        if(!writeDone.await(3,TimeUnit.SECONDS))throw new java.io.IOException("GATT busy");
        long deadline=android.os.SystemClock.elapsedRealtime()+180000;int reported=0;
        for(int p=0;p<data.length;p+=19){
            if(android.os.SystemClock.elapsedRealtime()>deadline)throw new java.io.IOException("Upload deadline exceeded");
            if(fileIsClosed)throw new java.io.IOException("File channel closed during upload");
            int count=Math.min(19,data.length-p);byte[] packet=new byte[count+1];packet[0]=(byte)fileHandle;System.arraycopy(data,p,packet,1,count);
            if(write(packet)!=0||!writeDone.await(3,TimeUnit.SECONDS)||writeStatus!=0)throw new java.io.IOException("File upload write failed");
            if((p+count)/2048>reported){reported=(p+count)/2048;log.accept("FILE_UPLOAD_PROGRESS bytes="+(p+count)+" total="+data.length);}
        }
    }
    private void pushTransfer(int token,byte[] compressed)throws Exception{
        openFile(false);
        try{
            byte[] control=ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).put((byte)0).put((byte)1).putShort((short)token).putShort((short)0).array();
            writeFileBytes(control);byte[] reply=filePackets.poll(10,TimeUnit.SECONDS);
            if(!Arrays.equals(reply,new byte[]{0,0,0}))throw new java.io.IOException("Upload control rejected");
            writeFileBytes(compressed);log.accept("FILE_UPLOAD bytes="+compressed.length);
        }finally{
            if(fileHandle>=0&&!fileIsClosed&&writeDone.await(3,TimeUnit.SECONDS))sendManagement(2,FILE_SERVICE,fileHandle);
            log.accept("FILE_UPLOAD_CLOSE completed="+fileClosed.await(8,TimeUnit.SECONDS)+" status="+fileCloseStatus);
        }
        if(!fileIsClosed||fileCloseStatus!=0)throw new java.io.IOException("Upload did not close cleanly");
    }
    private int write(byte[] packet){writeDone=new CountDownLatch(1);writeStatus=-1;int result=gatt.writeCharacteristic(tx,packet,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);if(result!=0){writeStatus=result;writeDone.countDown();}return result;}
    @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){writeStatus=status;writeDone.countDown();}
    private void sendFrame(byte[] frame)throws Exception{
        int id=(frame[2]&255)|((frame[3]&255)<<8);
        if(id!=5031&&id!=5002&&id!=5000&&id!=5043&&id!=5044&&id!=5030)throw new IllegalArgumentException("Outside supported protocol scope");
        if(id==5030&&(frame.length!=8||frame[4]!=0||frame[5]!=0))throw new IllegalArgumentException("Only sync-complete event is allowed");
        if(!writeDone.await(3,TimeUnit.SECONDS))throw new java.io.IOException("GATT busy");
        byte[] bytes=GarminWire.encode(frame);
        for(int at=0;at<bytes.length;at+=19){int count=Math.min(19,bytes.length-at);byte[] packet=new byte[count+1];packet[0]=(byte)handle;System.arraycopy(bytes,at,packet,1,count);
            if(write(packet)!=0||!writeDone.await(3,TimeUnit.SECONDS)||writeStatus!=0)throw new java.io.IOException("GATT write failed");
        }
    }
    private void receive(byte[] value){
        for(int i=1;i<value.length;i++){
            if(value[i]==0){
                if(framed&&inbound.size()>0)try{
                    byte[] data=GarminWire.decode(inbound.toByteArray());
                    ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                    if(data.length<6||(b.getShort()&65535)!=data.length||GarminWire.crc(data,data.length)!=0)throw new IllegalArgumentException("GFDI");
                    if(!frames.offer(data))log.accept("FRAME queue_full");
                }catch(RuntimeException malformed){log.accept("FRAME rejected");}
                inbound.reset();framed=true;
            }else if(framed){if(inbound.size()<16384)inbound.write(value[i]);else{inbound.reset();framed=false;log.accept("FRAME oversized");}}
        }
    }
    @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
        if (!rxId.equals(characteristic.getUuid()) || value.length==0) return;
        if(handle>0&&(value[0]&255)==handle){receive(value);return;}
        if(fileHandle>0&&(value[0]&255)==fileHandle){byte[] data=Arrays.copyOfRange(value,1,value.length);if(fileReading)fileBuffer.accept(data);else if(!filePackets.offer(data))log.accept("FILE_UPLOAD unexpected response overflow");return;}
        if(value.length<13||value[0]!=0)return;
        ByteBuffer packet = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        packet.get(); int type = packet.get() & 255;
        if (packet.getLong() != CLIENT) return;
        int service=packet.getShort()&65535;
        if(service==FILE_SERVICE){
            if(type==1){
                int status=packet.get()&255;
                if(status==0&&packet.remaining()>=2){fileHandle=packet.get()&255;fileReliable=packet.get()&255;log.accept("FILE_OPEN status=0 handle="+fileHandle+" reliable="+fileReliable);}
                else log.accept("FILE_OPEN status="+status);
                fileReady.countDown();
            }else if(type==3&&packet.remaining()>=2){
                int closing=packet.get()&255,status=packet.get()&255;
                if(closing==fileHandle){fileCloseStatus=status;fileIsClosed=true;log.accept("FILE_CLOSE status="+status);fileClosed.countDown();}
            }
            return;
        }
        if(service!=1)return;
        if (type == 1) {
            int status = packet.get() & 255;
            if (status == 0 && packet.remaining() >= 2) { handle = packet.get() & 255; log.accept("OPEN status=0 handle=" + handle + " reliable=" + (packet.get() & 255)); }
            else {
                log.accept("OPEN status=" + status);
                if (status == 3 && packet.remaining() >= 2) {
                    redirectTo=packet.getShort() & 65535;
                    log.accept("REGISTER_REDIRECT characteristic=" + Integer.toHexString(redirectTo));
                }
            }
            ready.countDown();
        } else if (type == 3 && packet.remaining() >= 2) {
            int closing = packet.get() & 255, status = packet.get() & 255;
            if (closing == handle) { log.accept("CLOSE status=" + status); closed.countDown(); }
        }
    }
}
