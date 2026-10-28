package com.particlesdevs.photoncamera.settings;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * P384 (owner, 2026-10-28: «Предлагаю сделать отдельную группу где можно будет вписывать айди модулей»): a Camera ID typed into a
 * module's Camera ID sheet (CameraIdSheet), checked while it is typed - empty, not an id at all, a camera the phone lists (or
 * describes: a hidden one it still opens), a physical camera of a logical one (saved as "logical-physical", the form the module
 * keys and the camera open take), or one the phone does not offer (saved only when the user insists). Pure: the phone's camera
 * list and its two lookups are passed in.
 */
public final class CameraIdCheck {
    private CameraIdCheck() {}

    public enum State { EMPTY, INVALID, KNOWN, PHYSICAL, UNKNOWN }

    /** The verdict: the state, the id that would be saved, and what the phone says of that camera (null when nothing). */
    public static final class Result {
        public final State state;
        public final String id;
        @Nullable public final String detail;

        Result(State state, String id, @Nullable String detail) {
            this.state = state;
            this.id = id;
            this.detail = detail;
        }

        @Override public String toString() {
            return state + " " + id + (detail == null ? "" : " (" + detail + ")");
        }
    }

    /** One id, or a logical-physical pair («0-3»); letters too (some HALs name cameras), at most 16 characters. */
    private static final Pattern ID = Pattern.compile("^[0-9A-Za-z_]+(-[0-9A-Za-z_]+)?$");
    static final int MAX_LENGTH = 16;

    /**
     * @param typed     what the field holds
     * @param listed    the phone's camera list (getCameraIdList)
     * @param describe  a short description of a camera the phone opens, null for one it does not know (a hidden camera the list
     *                  leaves out may still be described: a service camera)
     * @param logicalOf the logical camera a bare physical id belongs to, null for none
     */
    public static Result check(@Nullable String typed, Collection<String> listed, Function<String, String> describe,
                               Function<String, String> logicalOf) {
        final String id = typed == null ? "" : typed.trim();
        if (id.isEmpty()) return new Result(State.EMPTY, "", null);
        if (id.length() > MAX_LENGTH || !ID.matcher(id).matches()) return new Result(State.INVALID, id, null);
        if (!id.contains("-")) {
            if (listed.contains(id)) return new Result(State.KNOWN, id, describe.apply(id));
            // as the save has always done (ModuleActions.assign): a physical camera the list leaves out opens through its logical one
            final String logical = logicalOf.apply(id);
            if (logical != null) return new Result(State.PHYSICAL, logical + "-" + id, describe.apply(id));
            final String known = describe.apply(id);
            return known != null ? new Result(State.KNOWN, id, known) : new Result(State.UNKNOWN, id, null);
        }
        // a pair: its physical part is what the camera opens
        final String physical = id.substring(id.indexOf('-') + 1);
        final String known = describe.apply(physical);
        if (known != null || listed.contains(physical)) return new Result(State.KNOWN, id, known);
        return new Result(State.UNKNOWN, id, null);
    }

    /** The ids probed past the phone's list, as CameraManager2's scan probes them (0 .. 120). */
    public static final int PROBE_MAX = 120;

    /**
     * The cameras of the sheet (owner, 2026-10-28: «у нас так же не определяет автоматом все доступные айди модулей»: a vivo lists 0
     * and 1, its modules open 3, 4, 5 and the scan finds more): every listed id and every bare id up to {@code max} the phone opens
     * though it leaves it out, in number order (names after, as listed), then each logical camera's physical ones as pairs where
     * the bare id does not open by itself.
     */
    public static List<String> available(Collection<String> listed, int max, Predicate<String> opens,
                                         Function<String, Collection<String>> physicalOf) {
        final Set<String> bare = new LinkedHashSet<>();
        final List<String> named = new ArrayList<>();
        for (String id : listed) if (!isNumber(id)) named.add(id);
        for (int n = 0; n <= max; n++) {
            final String id = String.valueOf(n);
            if (listed.contains(id) || opens.test(id)) bare.add(id);
        }
        for (String id : listed) if (isNumber(id) && Integer.parseInt(id) > max) bare.add(id);
        final List<String> out = new ArrayList<>(bare);
        out.addAll(named);
        for (String logical : new ArrayList<>(out)) {
            final Collection<String> physical = physicalOf.apply(logical);
            if (physical == null) continue;
            for (String p : physical) if (!p.equals(logical) && !out.contains(p) && !out.contains(logical + "-" + p)) out.add(logical + "-" + p);
        }
        return out;
    }

    private static boolean isNumber(String id) {
        if (id.isEmpty() || id.length() > 9) return false;
        for (int i = 0; i < id.length(); i++) if (!Character.isDigit(id.charAt(i))) return false;
        return true;
    }
}
