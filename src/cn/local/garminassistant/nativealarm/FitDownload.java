package cn.local.garminassistant.nativealarm;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;

/** Bounded receiver for one read-only ML file transfer. */
final class FitDownload {
    private final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    private boolean started;
    private IOException failure;
    synchronized void accept(byte[] packet) {
        if (failure != null) return;
        if (!started) {
            if (!Arrays.equals(packet, new byte[]{0, 0, 0})) {
                failure = new IOException("Unexpected file transfer control response, length=" + packet.length);
                return;
            }
            started = true;
        } else if (packet.length == 0 || compressed.size() + packet.length > 262144) {
            failure = new IOException("Invalid compressed transfer size");
        } else compressed.write(packet, 0, packet.length);
    }
    synchronized int size() { return compressed.size(); }
    synchronized byte[] finish(int expected) throws IOException {
        if (failure != null) throw failure;
        if (!started || expected < 14 || expected > 131072) throw new IOException("Transfer unavailable");
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed.toByteArray());
            ByteArrayOutputStream output = new ByteArrayOutputStream(expected);
            byte[] block = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(block);
                if (output.size() + n > expected) throw new IOException("Inflated file exceeds listed size");
                output.write(block, 0, n);
                if (n == 0 && !inflater.finished()) throw new IOException("Incomplete compressed file");
            }
            if (inflater.getRemaining() != 0 || output.size() != expected) throw new IOException("Compressed file boundary mismatch");
            byte[] fit = output.toByteArray();
            validateFit(fit);
            return fit;
        } catch (DataFormatException e) { throw new IOException("Invalid zlib checksum or stream", e); }
        finally { inflater.end(); }
    }
    static void validateFit(byte[] fit) throws IOException {
        if (fit.length < 14 || (fit[0] != 12 && fit[0] != 14)
                || fit[8] != '.' || fit[9] != 'F' || fit[10] != 'I' || fit[11] != 'T') throw new IOException("Invalid FIT header");
        long size = ByteBuffer.wrap(fit).order(ByteOrder.LITTLE_ENDIAN).getInt(4) & 0xffffffffL;
        if (size + fit[0] + 2 != fit.length || GarminWire.crc(fit, fit.length) != 0
                || (fit[0] == 14 && GarminWire.crc(fit, 14) != 0)) throw new IOException("Invalid FIT length or CRC");
    }
    private static byte[] zip(byte[] data) {
        Deflater z = new Deflater(); z.setInput(data); z.finish();
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[1024];
        while (!z.finished()) { int n = z.deflate(buf); b.write(buf, 0, n); } z.end(); return b.toByteArray();
    }
    private static FitDownload from(byte[] data) {
        FitDownload b = new FitDownload(); b.accept(new byte[]{0,0,0});
        for (int i = 0; i < data.length; i += 19) b.accept(Arrays.copyOfRange(data, i, Math.min(i + 19, data.length)));
        return b;
    }
    public static void main(String[] args) throws Exception {
        byte[] fit = new byte[]{12,16,0,0,0,0,0,0,'.','F','I','T',0,0};
        int crc = GarminWire.crc(fit,12); fit[12]=(byte)crc; fit[13]=(byte)(crc>>8);
        byte[] packed = zip(fit);
        if (!Arrays.equals(fit, from(packed).finish(fit.length))) throw new AssertionError("Fragmented round trip");
        List<byte[]> bad = new ArrayList<>();
        bad.add(Arrays.copyOf(packed, packed.length-1)); bad.add(Arrays.copyOf(packed, packed.length+1));
        byte[] corrupt=packed.clone(); corrupt[corrupt.length-1]^=1; bad.add(corrupt);
        byte[] badFit=fit.clone(); badFit[12]^=1; bad.add(zip(badFit));
        for(byte[] data:bad) try { from(data).finish(fit.length); throw new AssertionError("Invalid data accepted"); } catch(IOException expected) {}
        try { from(packed).finish(fit.length+1); throw new AssertionError("Wrong size accepted"); } catch(IOException expected) {}
        FitDownload rejected=new FitDownload(); rejected.accept(new byte[]{1,0,0});
        try { rejected.finish(fit.length); throw new AssertionError("Bad control accepted"); } catch(IOException expected) {}
        System.out.println("PASS transfer fragments, truncated/corrupt/trailing zlib, FIT CRC, sizes, control rejection");
    }
}
