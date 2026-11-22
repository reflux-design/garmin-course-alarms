package cn.local.garminassistant.nativealarm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public final class FitFixtureTest {
    static AlarmProfile profile() {
        return new AlarmProfile(123456789L, Set.of(0, 2, 4, 5, 8), Set.of(5), 5);
    }

    public static void main(String[] args) throws Exception {
        byte[] source = Files.readAllBytes(Path.of(args[0]));
        AlarmProfile profile = profile();
        NativeAlarmFit fit = new NativeAlarmFit(source, profile);
        Map<Integer,Integer> primary = Map.of(431, 3, 530, 124);
        byte[] delta = fit.week(primary, 8, 1900000000L);
        Files.write(Path.of(args[1]), delta);
        reject(() -> NativeAlarmFit.validateUpdatePayload(delta, profile, 6));
        reject(() -> fit.week(Map.of(1435, 127), 8, 1900000000L));
        reject(() -> fit.week(Map.of(450, 1, 530, 2, 620, 124), 8, 1900000000L));
        reject(() -> fit.week(Map.of(450, 1, 530, 2), 0, 1900000000L));
        reject(() -> new NativeAlarmFit(source, new AlarmProfile(profile.unit, Set.of(5), Set.of(), 5)));
        reject(() -> new NativeAlarmFit(source, new AlarmProfile(123L, Set.of(5), Set.of(5), 5)));
        byte[] corrupt = source.clone();
        corrupt[20] ^= 1;
        reject(() -> new NativeAlarmFit(corrupt, profile));
        System.out.println("PASS configurable offset, enrollment identity, slot budget and FIT checksums");
    }

    private interface Operation {
        void run() throws IOException;
    }

    private static void reject(Operation operation) throws Exception {
        try {
            operation.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Invalid alarm operation was accepted");
    }
}
