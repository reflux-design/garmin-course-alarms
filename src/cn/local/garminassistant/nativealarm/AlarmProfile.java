package cn.local.garminassistant.nativealarm;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** A per-watch enrollment selected by the owner. Slots outside managedSlots are protected. */
public final class AlarmProfile {
    public final long unit;
    public final Set<Integer> managedSlots;
    public final Set<Integer> replaceEnabledSlots;
    public final int alertTemplateSlot;

    public AlarmProfile(long unit, Set<Integer> managedSlots, Set<Integer> replaceEnabledSlots, int alertTemplateSlot) {
        if (unit <= 0 || unit >= 0xffffffffL || alertTemplateSlot < 0 || alertTemplateSlot > 9) {
            throw new IllegalArgumentException("Invalid unit or alert template slot");
        }
        TreeSet<Integer> managed = new TreeSet<>(managedSlots);
        TreeSet<Integer> replace = new TreeSet<>(replaceEnabledSlots);
        if (managed.isEmpty() || managed.first() < 0 || managed.last() > 9 || !managed.containsAll(replace)) {
            throw new IllegalArgumentException("Invalid managed or replace-enabled slots");
        }
        this.unit = unit;
        this.managedSlots = Collections.unmodifiableSet(managed);
        this.replaceEnabledSlots = Collections.unmodifiableSet(replace);
        this.alertTemplateSlot = alertTemplateSlot;
    }
}
