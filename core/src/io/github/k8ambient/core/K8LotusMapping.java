package io.github.k8ambient.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Converts a raw K8 SML status into the normalized {@link AmbientState} the sync engine sends.
 *
 * <p>Rules (docs/research/PC_ANALYSIS_2026-11-07.md sec. 5, AMBIENT_SOUND_PACKAGE.md):
 * <ul>
 * <li>Brightness raw 1 = OFF, raw 2..11 = menu levels 1..10 = 10..100 % (the engine then scales
 *     to the aftermarket maximum, 20 % by default). Other raw values are ignored.</li>
 * <li>All three channel high bytes 0x00 = direct color; the low bytes are the RGB value.</li>
 * <li>Otherwise the value is a theme code. A user-assigned color is used when known; an unknown
 *     theme uses its low bytes, which the user confirmed on 2026-11-09 match the factory colors
 *     (keeping the previous color made theme changes look stuck until a relay restart).</li>
 * </ul>
 * Not thread-safe; use from one thread.
 */
public final class K8LotusMapping {
    private final VehicleProfile profile;
    private final Map<String, int[]> themeColors = new HashMap<String, int[]>();
    /** 자동 적응 모드에서 관측된 최대 밝기 원시값 (초기값은 profile.maxOnRaw). */
    private int learnedMaxOnRaw;

    /** 기존 K8 GL3 HEV 기본 프로필로 생성한다. */
    public K8LotusMapping() {
        this(VehicleProfile.K8_GL3);
    }

    /** 지정한 차종 프로필(밝기 범위·자동 적응 옵션 포함)로 생성한다. */
    public K8LotusMapping(VehicleProfile profile) {
        if (profile == null) throw new IllegalArgumentException("VehicleProfile is required");
        this.profile = profile;
        this.learnedMaxOnRaw = profile.maxOnRaw;
    }

    public VehicleProfile profile() {
        return profile;
    }

    /** 현재 적용 중인 최대 밝기 원시값 (자동 적응 시 관측된 더 큰 값으로 확장). */
    public int effectiveMaxOnRaw() {
        return learnedMaxOnRaw;
    }

    /** Theme key: the six raw channel bytes as lower-case hex, e.g. {@code 016b015a0195}. */
    public static String themeKey(SmlStatus status) {
        return String.format(Locale.US, "%04x%04x%04x", status.redRaw, status.greenRaw, status.blueRaw);
    }

    public static boolean isDirectColor(SmlStatus status) {
        return status.redRaw <= 255 && status.greenRaw <= 255 && status.blueRaw <= 255;
    }

    public void setThemeColor(String key, int red, int green, int blue) {
        if (key == null || !key.matches("[0-9a-f]{12}")) throw new IllegalArgumentException("Bad theme key");
        AmbientState.checkRange(red, 255);
        AmbientState.checkRange(green, 255);
        AmbientState.checkRange(blue, 255);
        themeColors.put(key, new int[] {red, green, blue});
    }

    public void clearThemeColor(String key) {
        themeColors.remove(key);
    }

    /** Returns the target state, or null when the brightness value is outside the known range (non-adaptive). */
    public AmbientState map(SmlStatus status) {
        if (status == null) throw new IllegalArgumentException("Status is required");
        int raw = status.brightnessRaw;
        boolean on;
        int brightness;

        if (profile.offRaw >= 0 && raw == profile.offRaw) {
            on = false;
            brightness = 0;
        } else if (raw >= profile.minOnRaw && raw <= learnedMaxOnRaw) {
            on = true;
            brightness = normalizeBrightness(raw, profile.minOnRaw, learnedMaxOnRaw);
        } else if (profile.allowAdaptiveRange) {
            if (raw < profile.minOnRaw) {
                on = false;
                brightness = 0;
            } else {
                // 관측된 raw가 기존 최대값보다 크면 안전하게 상한을 확장 학습해 비례 정규화한다.
                learnedMaxOnRaw = raw;
                on = true;
                brightness = normalizeBrightness(raw, profile.minOnRaw, learnedMaxOnRaw);
            }
        } else {
            return null;
        }

        int[] color;
        if (isDirectColor(status)) {
            color = new int[] {status.redRaw, status.greenRaw, status.blueRaw};
        } else {
            int[] assigned = themeColors.get(themeKey(status));
            if (assigned != null) color = assigned.clone();
            else color = new int[] {status.redRaw & 255, status.greenRaw & 255, status.blueRaw & 255};
        }
        return new AmbientState(color[0], color[1], color[2], brightness, on);
    }

    private static int normalizeBrightness(int raw, int minOnRaw, int maxOnRaw) {
        int steps = maxOnRaw - minOnRaw + 1;
        if (steps <= 1) return 100;
        int pct = ((raw - minOnRaw + 1) * 100 + steps / 2) / steps;
        if (pct < 1) return 1;
        if (pct > 100) return 100;
        return pct;
    }
}
