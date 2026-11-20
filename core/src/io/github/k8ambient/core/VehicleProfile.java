package io.github.k8ambient.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 현대·기아·제네시스 Gen5W 차종별 순정 앰비언트 매핑 프로필 (순수 Java 7).
 *
 * <p>차종마다 다를 수 있는 밝기 원시값 범위({@code offRaw}, {@code minOnRaw}..{@code maxOnRaw})와
 * 드라이브 모드 바이트 코드({@code ecoModeCode}, {@code sportModeCode}, {@code normalModeCode})를
 * 캡슐화한다. 기본값 {@link #K8_GL3}는 기존 K8 GL3 HEV 동작과 100% 동일하다.
 */
public final class VehicleProfile {
    public static final String ID_K8_GL3 = "K8_GL3";
    public static final String ID_EV6_IONIQ = "EV6_IONIQ";
    public static final String ID_GEN5W_AUTO = "GEN5W_AUTO";

    public static final String ROLE_ECO = "eco";
    public static final String ROLE_SPORT = "sport";
    public static final String ROLE_NORMAL = "normal";

    /** 기존 K8 GL3 HEV 기본 프로필 (1=꺼짐, 2~11=1~10단계, 0x02=에코, 0x03=스포츠, 0x06=스마트). */
    public static final VehicleProfile K8_GL3 = new VehicleProfile(
            ID_K8_GL3,
            "K8 (GL3 HEV, 기본)",
            "밝기 1=꺼짐·2~11=1~10단계, 모드 02=에코·03=스포츠·06=스마트/마이",
            1, 2, 11, false,
            0x02, 0x03, 0x06, 0x02);

    /** EV6 / 아이오닉5·6 Gen5W 프로필 (범위 밖 밝기 자동 대응, 0x01=노멀·0x02=에코·0x03=스포츠). */
    public static final VehicleProfile EV6_IONIQ = new VehicleProfile(
            ID_EV6_IONIQ,
            "EV6 / 아이오닉5·6 (Gen5W)",
            "밝기 1=꺼짐·2~11(범위 밖 자동 대응), 모드 01=노멀·02=에코·03=스포츠",
            1, 2, 11, true,
            0x02, 0x03, 0x01, 0x02);

    /** 현대·기아·제네시스 Gen5W 범용 프로필 (자동 범위 적응·확장 학습 + 원터치 모드 지정). */
    public static final VehicleProfile GEN5W_AUTO = new VehicleProfile(
            ID_GEN5W_AUTO,
            "현대·기아·제네시스 Gen5W 범용 (자동 적응)",
            "밝기 범위 밖 자동 정규화/학습(1~11·0~10·0~100 대응), 모드 코드 원터치 지정",
            1, 2, 11, true,
            0x02, 0x03, 0x01, 0x02);

    public static final List<VehicleProfile> PRESETS = Collections.unmodifiableList(Arrays.asList(
            K8_GL3,
            EV6_IONIQ,
            GEN5W_AUTO
    ));

    public final String id;
    public final String name;
    public final String description;
    /** 꺼짐을 뜻하는 밝기 원시값 (-1이면 꺼짐 원시값 없음). */
    public final int offRaw;
    /** 켜짐 최소 밝기 원시값 (0..255). */
    public final int minOnRaw;
    /** 켜짐 최대 밝기 원시값 (minOnRaw..255). */
    public final int maxOnRaw;
    /** true이면 범위 밖 원시값도 무시하지 않고 안전하게 비례 정규화 및 최대값 자동 학습. */
    public final boolean allowAdaptiveRange;
    /** 에코 모드 바이트 코드 (0이면 미지정). */
    public final int ecoModeCode;
    /** 스포츠 모드 바이트 코드 (0이면 미지정). */
    public final int sportModeCode;
    /** 일반/스마트/노멀(사용자 색 유지) 모드 대표 코드 (0이면 미지정). */
    public final int normalModeCode;
    /** 순정 드라이브 모드 연동 켜짐을 뜻하는 SML byte 8 값 (기본 0x02). */
    public final int linkOnRaw;

    public VehicleProfile(String id, String name, String description,
                          int offRaw, int minOnRaw, int maxOnRaw, boolean allowAdaptiveRange,
                          int ecoModeCode, int sportModeCode, int normalModeCode, int linkOnRaw) {
        if (id == null || id.trim().length() == 0) throw new IllegalArgumentException("Profile id is required");
        if (name == null || name.trim().length() == 0) throw new IllegalArgumentException("Profile name is required");
        if (offRaw < -1 || offRaw > 255) throw new IllegalArgumentException("offRaw must be -1..255");
        if (minOnRaw < 0 || minOnRaw > 255) throw new IllegalArgumentException("minOnRaw must be 0..255");
        if (maxOnRaw < minOnRaw || maxOnRaw > 255) throw new IllegalArgumentException("maxOnRaw must be minOnRaw..255");
        if (offRaw >= minOnRaw && offRaw <= maxOnRaw) {
            throw new IllegalArgumentException("offRaw must be outside minOnRaw..maxOnRaw");
        }
        checkModeCode("ecoModeCode", ecoModeCode);
        checkModeCode("sportModeCode", sportModeCode);
        checkModeCode("normalModeCode", normalModeCode);
        if (linkOnRaw < 0 || linkOnRaw > 255) throw new IllegalArgumentException("linkOnRaw must be 0..255");
        this.id = id.trim();
        this.name = name.trim();
        this.description = description == null ? "" : description.trim();
        this.offRaw = offRaw;
        this.minOnRaw = minOnRaw;
        this.maxOnRaw = maxOnRaw;
        this.allowAdaptiveRange = allowAdaptiveRange;
        this.ecoModeCode = ecoModeCode;
        this.sportModeCode = sportModeCode;
        this.normalModeCode = normalModeCode;
        this.linkOnRaw = linkOnRaw;
    }

    private static void checkModeCode(String label, int code) {
        if (code < 0 || code > 255) throw new IllegalArgumentException(label + " must be 0..255");
    }

    /** 저장된 ID로 프리셋을 찾는다. 알 수 없는 ID이면 기본값 {@link #K8_GL3}를 반환한다. */
    public static VehicleProfile byId(String id) {
        if (id != null) {
            for (VehicleProfile preset : PRESETS) {
                if (preset.id.equals(id)) return preset;
            }
        }
        return K8_GL3;
    }

    public VehicleProfile withBrightnessRange(int offRaw, int minOnRaw, int maxOnRaw, boolean allowAdaptiveRange) {
        return new VehicleProfile(id, name, description,
                offRaw, minOnRaw, maxOnRaw, allowAdaptiveRange,
                ecoModeCode, sportModeCode, normalModeCode, linkOnRaw);
    }

    public VehicleProfile withModeCodes(int ecoModeCode, int sportModeCode, int normalModeCode) {
        return new VehicleProfile(id, name, description,
                offRaw, minOnRaw, maxOnRaw, allowAdaptiveRange,
                ecoModeCode, sportModeCode, normalModeCode, linkOnRaw);
    }

    /**
     * 특정 역할({@code eco}/{@code sport}/{@code normal})에 모드 바이트 코드를 지정한다.
     * 같은 코드가 다른 역할에 이미 등록돼 있으면 충돌 방지를 위해 기존 역할의 코드를 해제(0)한다.
     */
    public VehicleProfile withAssignedModeCode(String role, int modeCode) {
        if (modeCode < 1 || modeCode > 255) {
            throw new IllegalArgumentException("Assigned mode code must be 1..255");
        }
        int eco = ecoModeCode;
        int sport = sportModeCode;
        int normal = normalModeCode;
        if (ROLE_ECO.equals(role)) {
            eco = modeCode;
            if (sport == modeCode) sport = 0;
            if (normal == modeCode) normal = 0;
        } else if (ROLE_SPORT.equals(role)) {
            sport = modeCode;
            if (eco == modeCode) eco = 0;
            if (normal == modeCode) normal = 0;
        } else if (ROLE_NORMAL.equals(role)) {
            normal = modeCode;
            if (eco == modeCode) eco = 0;
            if (sport == modeCode) sport = 0;
        } else {
            throw new IllegalArgumentException("Unknown mode role: " + role);
        }
        return withModeCodes(eco, sport, normal);
    }

    /** UI 표시용 드라이브 모드 이름. */
    public String modeLabel(int mode) {
        if (mode == 0) return "아직 모름(바꾸면 표시)";
        if (ecoModeCode > 0 && mode == ecoModeCode) {
            return String.format(Locale.US, "에코 (0x%02x)", mode);
        }
        if (sportModeCode > 0 && mode == sportModeCode) {
            return String.format(Locale.US, "스포츠 (0x%02x)", mode);
        }
        if (normalModeCode > 0 && mode == normalModeCode) {
            String base = ID_K8_GL3.equals(id) ? "스마트" : "일반/스마트";
            return String.format(Locale.US, "%s (0x%02x)", base, mode);
        }
        return String.format(Locale.US, "코드 0x%02x (사용자색)", mode);
    }

    /** 현재 프로필의 밝기·모드 설정 요약 문자열. */
    public String summary() {
        String offText = offRaw < 0 ? "없음" : String.valueOf(offRaw);
        return name + " [밝기 꺼짐=" + offText + ", " + minOnRaw + "~" + maxOnRaw
                + (allowAdaptiveRange ? " 자동적응" : "")
                + " / 에코=" + formatCode(ecoModeCode)
                + "·스포츠=" + formatCode(sportModeCode)
                + "·일반=" + formatCode(normalModeCode) + "]";
    }

    public static String formatCode(int code) {
        return code > 0 ? String.format(Locale.US, "0x%02x", code) : "미지정";
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof VehicleProfile)) return false;
        VehicleProfile o = (VehicleProfile) other;
        return offRaw == o.offRaw
                && minOnRaw == o.minOnRaw
                && maxOnRaw == o.maxOnRaw
                && allowAdaptiveRange == o.allowAdaptiveRange
                && ecoModeCode == o.ecoModeCode
                && sportModeCode == o.sportModeCode
                && normalModeCode == o.normalModeCode
                && linkOnRaw == o.linkOnRaw
                && id.equals(o.id)
                && name.equals(o.name)
                && description.equals(o.description);
    }

    @Override public int hashCode() {
        int h = id.hashCode();
        h = 31 * h + offRaw;
        h = 31 * h + minOnRaw;
        h = 31 * h + maxOnRaw;
        h = 31 * h + (allowAdaptiveRange ? 1 : 0);
        h = 31 * h + ecoModeCode;
        h = 31 * h + sportModeCode;
        h = 31 * h + normalModeCode;
        h = 31 * h + linkOnRaw;
        return h;
    }

    @Override public String toString() {
        return summary();
    }
}
