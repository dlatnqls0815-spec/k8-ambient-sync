package io.github.k8ambient.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.InputType;
import android.util.SparseArray;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import io.github.k8ambient.core.ColorOrder;
import io.github.k8ambient.core.CustomLampProfile;
import io.github.k8ambient.core.CustomRgbTemplate;
import io.github.k8ambient.core.VehicleProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Setup and status screen for the plan B phone relay. Programmatic UI, no resources. */
public final class MainActivity extends Activity {
    private static final String[] PERMISSIONS = {
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private TextView status;
    private Button vehicleProfileButton;
    private Button manageCustomButton;
    private EditText themeColor;
    private BluetoothAdapter adapter;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        DiagLog.init(this);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        TextView title = new TextView(this);
        title.setText("K8 앰비언트 중계");
        title.setTextSize(22);
        layout.addView(title);
        text(layout, "LotusLamp X·Zengge 등 기존 조명 앱은 종료하세요. BLE 조명은 한 번에 한 기기에만 연결됩니다.\n"
                + "차량에 NEMESIS 'K8 Ambient Relay'가 설치돼 있어야 합니다.");
        button(layout, "1. 블루투스 권한 허용", new View.OnClickListener() {
            public void onClick(View v) { requestPermissions(PERMISSIONS, 1); }
        });
        button(layout, "2. 차량 선택 (페어링된 기기)", new View.OnClickListener() {
            public void onClick(View v) { chooseCar(); }
        });
        vehicleProfileButton = button(layout, "차종 프로필 선택", new View.OnClickListener() {
            public void onClick(View v) { chooseVehicleProfile(); }
        });
        updateVehicleProfileLabel();
        button(layout, "3. 기본 조명 찾기 (8초, Lotus·Zengge)", new View.OnClickListener() {
            public void onClick(View v) { scanLamp(); }
        });
        button(layout, "저장된 기본 조명 지우기 (Lotus/Zengge)", new View.OnClickListener() {
            public void onClick(View v) { clearLamp(); }
        });
        button(layout, "Zengge 색 순서 (빨강·초록이 바뀌어 보이면 GRB)", new View.OnClickListener() {
            public void onClick(View v) { chooseOrder(); }
        });

        text(layout, "\n다른 사제 엠비언트 추가 (BLE 스캔 → RGB 형식/보정 설정 → 바로 연동)");
        button(layout, "+ 사제 엠비언트 추가 (BLE 스캔 · RGB 등록)", new View.OnClickListener() {
            public void onClick(View v) { scanCustomLamp(); }
        });
        manageCustomButton = button(layout, "등록된 사제 엠비언트 관리", new View.OnClickListener() {
            public void onClick(View v) { manageCustomLamps(); }
        });
        updateCustomButtonLabel();

        CheckBox power = new CheckBox(this);
        power.setText("순정 꺼짐 때 Lotus 조명도 끄기 (실험: 앱 코드에서 읽은 전원 명령)");
        power.setChecked(prefs.power());
        power.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.setPower(checked);
                toast("다음 시작부터 적용됩니다");
            }
        });
        layout.addView(power);
        button(layout, "4. 중계 시작", new View.OnClickListener() {
            public void onClick(View v) {
                if (!granted()) { toast("먼저 권한을 허용하세요"); return; }
                if (prefs.car() == null || !prefs.anyLamp()) { toast("차량과 조명을 먼저 선택하거나 등록하세요"); return; }
                prefs.setAuto(true);
                startForegroundService(new Intent(MainActivity.this, RelayService.class));
            }
        });
        button(layout, "중계 중지 (시동 시 자동 시작도 끔)", new View.OnClickListener() {
            public void onClick(View v) {
                prefs.setAuto(false);
                stopService(new Intent(MainActivity.this, RelayService.class));
            }
        });

        text(layout, "\n순정 테마(드림퍼플 등)일 때 사제 색 지정: R,G,B (예: 126,0,255)");
        themeColor = new EditText(this);
        themeColor.setInputType(InputType.TYPE_CLASS_TEXT);
        themeColor.setHint("126,0,255");
        layout.addView(themeColor);
        button(layout, "지금 순정 테마에 이 색 지정", new View.OnClickListener() {
            public void onClick(View v) { assignTheme(Prefs.parseRgb(themeColor.getText().toString()), true); }
        });
        button(layout, "지금 순정 테마의 지정 색 지우기", new View.OnClickListener() {
            public void onClick(View v) { assignTheme(null, false); }
        });

        text(layout, "\n드라이브 모드 연동(순정 설정이 켜져 있을 때): 스마트는 사용자 색, 에코·스포츠는 아래 색");
        driveColorRow(layout, "에코 색 R,G,B", Prefs.DRIVE_ECO);
        driveColorRow(layout, "스포츠 색 R,G,B", Prefs.DRIVE_SPORT);

        text(layout, "\n다른 차종 맞춤 설정 (K8 외 현대·기아·제네시스 Gen5W)");
        button(layout, "밝기 원시값 범위 설정 (꺼짐/최소/최대·자동적응)", new View.OnClickListener() {
            public void onClick(View v) { openBrightnessRangeDialog(); }
        });
        text(layout, "현재 차에서 수신된 드라이브 모드 코드(0x..)를 원터치로 역할에 지정:");
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        quickModeAssignButton(modeRow, "지금 코드→에코", VehicleProfile.ROLE_ECO, "에코");
        quickModeAssignButton(modeRow, "지금 코드→스포츠", VehicleProfile.ROLE_SPORT, "스포츠");
        quickModeAssignButton(modeRow, "지금 코드→일반", VehicleProfile.ROLE_NORMAL, "일반(사용자색)");
        layout.addView(modeRow);

        text(layout, "\n부드러운 전환·색 톤 (움직이면 조명에 바로 반영)");
        slider(layout, "전환 시간", Prefs.FADE_MS, 0, 3000, 100, "ms");
        slider(layout, "색 깊이(감마 ×0.1, 높을수록 진하고 덜 하얗게)", Prefs.GAMMA, 10, 30, 1, "");
        slider(layout, "빨강 비율 (기본 Lotus·Zengge)", Prefs.RED, 0, 100, 1, "%");
        slider(layout, "초록 비율 (기본 Lotus·Zengge)", Prefs.GREEN, 0, 100, 1, "%");
        slider(layout, "파랑 비율 (기본 Lotus·Zengge)", Prefs.BLUE, 0, 100, 1, "%");
        slider(layout, "Lotus 최대 밝기(순정 최대일 때)", Prefs.MAX, 5, 50, 1, "%");
        slider(layout, "Zengge 최대 밝기(순정 최대일 때)", Prefs.ZENGGE_MAX, 5, 100, 1, "%");

        text(layout, "\n색별 밝기 (위 최대 밝기 대비, 100%=그대로. 예: Lotus 20%에서 150%=30%, 65%=13%)");
        String[] hueNames = {"빨강", "따뜻한 색(주황·노랑)", "초록", "하늘", "파랑", "보라"};
        for (int i = 0; i < hueNames.length; i++) {
            slider(layout, hueNames[i] + " 밝기", Prefs.HUE_KEYS[i], 20, 300, 5, "%");
        }

        status = new TextView(this);
        status.setTextIsSelectable(true);
        layout.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        // Target SDK 35 draws edge-to-edge: drop the action bar and pad the content by the system bars.
        if (getActionBar() != null) getActionBar().hide();
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            }
        });
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        updateVehicleProfileLabel();
        updateCustomButtonLabel();
        ui.post(refresh);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    private void updateVehicleProfileLabel() {
        if (vehicleProfileButton == null) return;
        VehicleProfile p = prefs.vehicleProfile();
        vehicleProfileButton.setText("차종 프로필: " + p.name + " (변경)");
    }

    private void updateCustomButtonLabel() {
        if (manageCustomButton == null) return;
        List<CustomLampProfile> all = prefs.customLamps();
        int active = 0;
        for (CustomLampProfile p : all) if (p.enabled) active++;
        manageCustomButton.setText("등록된 사제 엠비언트 관리 (" + all.size() + "개 중 " + active + "개 연동)");
    }

    private final Runnable refresh = new Runnable() {
        public void run() {
            StringBuilder s = new StringBuilder("\n");
            s.append("중계: ").append(RelayStatus.running ? "실행 중" : "중지").append('\n');
            s.append("차량: ").append(mask(prefs.car())).append(" / ").append(RelayStatus.car).append('\n');
            s.append("Lotus: ").append(mask(prefs.lamp())).append(" / ").append(RelayStatus.lamp[RelayStatus.LOTUS]).append('\n');
            s.append("Zengge: ").append(mask(prefs.zengge())).append(" (").append(prefs.zenggeOrder()).append(") / ")
                    .append(RelayStatus.lamp[RelayStatus.ZENGGE]).append('\n');
            List<CustomLampProfile> customs = prefs.customLamps();
            for (CustomLampProfile p : customs) {
                String st = p.enabled ? RelayStatus.customLamp.get(p.id) : "비활성";
                if (st == null) st = "-";
                Long cnt = RelayStatus.customPackets.get(p.id);
                String last = RelayStatus.customLastSent.get(p.id);
                s.append("사제[").append(p.name).append("]: ").append(mask(p.address))
                        .append(" (").append(p.colorOrder).append(", 최대 ").append(p.maxBrightness).append("%) / ")
                        .append(st);
                if (cnt != null && cnt.longValue() > 0) {
                    s.append(" / 패킷 ").append(cnt).append(" (").append(last == null ? "-" : last).append(")");
                }
                s.append('\n');
            }
            VehicleProfile vp = prefs.vehicleProfile();
            s.append("차종 프로필: ").append(vp.summary()).append('\n');
            s.append("순정 원시값: ").append(RelayStatus.raw).append('\n');
            s.append("순정 밝기 원시값: ");
            if (RelayStatus.brightnessRaw < 0) {
                s.append("아직 모름");
            } else {
                int effMax = RelayStatus.effectiveMaxRaw > 0 ? RelayStatus.effectiveMaxRaw : vp.maxOnRaw;
                s.append("raw=").append(RelayStatus.brightnessRaw)
                        .append(" (범위 ").append(vp.minOnRaw).append("~").append(effMax)
                        .append(", 꺼짐=").append(vp.offRaw < 0 ? "없음" : String.valueOf(vp.offRaw)).append(")");
            }
            s.append('\n');
            s.append("목표: ").append(RelayStatus.target).append('\n');
            s.append("드라이브 모드: ").append(vp.modeLabel(RelayStatus.driveMode))
                    .append(RelayStatus.driveLinked ? " (순정 연동 켜짐)" : " (순정 연동 꺼짐)").append('\n');
            s.append("마이 드라이브: ").append(RelayStatus.myDrive).append('\n');
            s.append("현재 테마: ").append(RelayStatus.themeKey == null ? "직접색 또는 없음" : RelayStatus.themeKey).append('\n');
            s.append("보낸 패킷 Lotus: ").append(RelayStatus.packets[RelayStatus.LOTUS]).append(" / 마지막 ").append(RelayStatus.lastSent[RelayStatus.LOTUS]).append('\n');
            s.append("보낸 패킷 Zengge: ").append(RelayStatus.packets[RelayStatus.ZENGGE]).append(" / 마지막 ").append(RelayStatus.lastSent[RelayStatus.ZENGGE]).append('\n');
            if (prefs.zengge() != null)
                s.append("Zengge 경고 전송 (켜짐 / 꺼짐): ").append(RelayStatus.zenggeWarningWrites).append('\n');
            s.append("진단 기록: ").append(DiagLog.path()).append('\n');
            s.append("저장된 테마 색: ");
            for (Map.Entry<String, int[]> e : prefs.themes().entrySet()) {
                int[] c = e.getValue();
                s.append('\n').append("  ").append(e.getKey()).append(" → ").append(c[0]).append(',').append(c[1]).append(',').append(c[2]);
            }
            status.setText(s.toString());
            ui.postDelayed(this, 1000);
        }
    };

    static String modeName(int mode) {
        return VehicleProfile.K8_GL3.modeLabel(mode);
    }

    private void chooseVehicleProfile() {
        final List<VehicleProfile> presets = VehicleProfile.PRESETS;
        String currentId = prefs.vehicleProfileId();
        String[] labels = new String[presets.size()];
        for (int i = 0; i < presets.size(); i++) {
            VehicleProfile p = presets.get(i);
            labels[i] = p.name + (p.id.equals(currentId) ? "  (현재)" : "") + "\n  " + p.description;
        }
        new AlertDialog.Builder(this)
                .setTitle("차종 프로필 선택 (현대·기아·제네시스 Gen5W)")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        VehicleProfile chosen = presets.get(which);
                        prefs.selectVehicleProfile(chosen.id);
                        updateVehicleProfileLabel();
                        notifyProfileChanged();
                        toast(chosen.name + " 적용 완료");
                    }
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void quickModeAssignButton(LinearLayout row, String label, final String role, final String roleLabel) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int code = RelayStatus.driveMode;
                if (code <= 0) {
                    toast("수신된 드라이브 모드 코드가 없습니다. 차에서 모드를 한 번 바꾼 뒤 눌러 주세요.");
                    return;
                }
                prefs.assignDriveModeCode(role, code);
                notifyProfileChanged();
                toast("현재 코드 " + VehicleProfile.formatCode(code) + " → " + roleLabel + " 지정 완료");
            }
        });
        row.addView(btn);
    }

    private void openBrightnessRangeDialog() {
        VehicleProfile current = prefs.vehicleProfile();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(36, 20, 36, 12);

        String currentRawText = RelayStatus.brightnessRaw < 0
                ? "현재 수신된 순정 밝기 원시값: 없음 (중계 연결 후 순정 밝기를 움직여 확인)"
                : "현재 수신된 순정 밝기 원시값: raw=" + RelayStatus.brightnessRaw;
        text(box, currentRawText + "\n아래 빠른 선택을 누르거나 차종에 맞게 직접 입력하세요.");

        final EditText offEdit = field(box, "꺼짐 원시값 (-1이면 꺼짐 없음, 예: 1 또는 0)", String.valueOf(current.offRaw));
        offEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        final EditText minEdit = field(box, "켜짐 최소 밝기 원시값 (예: 2 또는 1)", String.valueOf(current.minOnRaw));
        minEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText maxEdit = field(box, "켜짐 최대 밝기 원시값 (예: 11, 10 또는 100)", String.valueOf(current.maxOnRaw));
        maxEdit.setInputType(InputType.TYPE_CLASS_NUMBER);

        final CheckBox adaptiveCheck = new CheckBox(this);
        adaptiveCheck.setText("범위 밖 원시값 자동 정규화·최대값 학습 (다른 차종 권장)");
        adaptiveCheck.setChecked(current.allowAdaptiveRange);
        box.addView(adaptiveCheck);

        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        brightnessPresetButton(presetRow, "1~11 (K8·EV6)", 1, 2, 11, false, offEdit, minEdit, maxEdit, adaptiveCheck);
        brightnessPresetButton(presetRow, "0~10", 0, 1, 10, true, offEdit, minEdit, maxEdit, adaptiveCheck);
        brightnessPresetButton(presetRow, "0~100", 0, 1, 100, true, offEdit, minEdit, maxEdit, adaptiveCheck);
        box.addView(presetRow);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("순정 밝기 원시값 범위 설정")
                .setView(scroll)
                .setPositiveButton("저장 및 적용", null)
                .setNegativeButton("취소", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        try {
                            int off = Integer.parseInt(offEdit.getText().toString().trim());
                            int min = Integer.parseInt(minEdit.getText().toString().trim());
                            int max = Integer.parseInt(maxEdit.getText().toString().trim());
                            prefs.setCustomBrightnessRange(off, min, max, adaptiveCheck.isChecked());
                            notifyProfileChanged();
                            toast("밝기 원시값 범위를 저장했습니다");
                            dialog.dismiss();
                        } catch (NumberFormatException error) {
                            toast("숫자만 입력하세요 (예: 꺼짐 1, 최소 2, 최대 11)");
                        } catch (IllegalArgumentException error) {
                            toast("확인 필요: " + error.getMessage());
                        }
                    }
                });
            }
        });
        dialog.show();
    }

    private void brightnessPresetButton(LinearLayout row, String label, final int off, final int min, final int max,
                                        final boolean adaptive, final EditText offEdit, final EditText minEdit,
                                        final EditText maxEdit, final CheckBox adaptiveCheck) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                offEdit.setText(String.valueOf(off));
                minEdit.setText(String.valueOf(min));
                maxEdit.setText(String.valueOf(max));
                adaptiveCheck.setChecked(adaptive);
            }
        });
        row.addView(btn);
    }

    private void notifyProfileChanged() {
        if (RelayStatus.running) {
            startService(new Intent(this, RelayService.class).setAction(RelayService.ACTION_RELOAD_THEMES));
        }
    }

    private void driveColorRow(LinearLayout layout, String label, final String key) {
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        int[] c = prefs.driveColor(key);
        field.setText(c[0] + "," + c[1] + "," + c[2]);
        field.setHint(label);
        layout.addView(field);
        button(layout, label + " 저장", new View.OnClickListener() {
            public void onClick(View v) {
                int[] rgb = Prefs.parseRgb(field.getText().toString());
                if (rgb == null) { toast("R,G,B를 0~255로 입력하세요"); return; }
                prefs.setDriveColor(key, rgb);
                if (RelayStatus.running) {
                    startService(new Intent(MainActivity.this, RelayService.class).setAction(RelayService.ACTION_RELOAD_THEMES));
                }
                toast("저장했습니다");
            }
        });
    }

    private void assignTheme(int[] rgb, boolean requireColor) {
        String key = RelayStatus.themeKey;
        if (key == null) { toast("지금 순정이 테마 상태일 때 눌러 주세요"); return; }
        if (requireColor && rgb == null) { toast("R,G,B를 0~255로 입력하세요"); return; }
        prefs.setTheme(key, rgb);
        if (RelayStatus.running) {
            startService(new Intent(this, RelayService.class).setAction(RelayService.ACTION_RELOAD_THEMES));
        }
        toast(rgb == null ? "지웠습니다" : "저장했습니다. 다음 상태 변경부터 적용됩니다");
    }

    private void chooseCar() {
        if (!granted() || adapter == null) { toast("권한을 먼저 허용하세요"); return; }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>(bonded);
            String[] labels = new String[devices.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = name(devices.get(i)) + "  " + mask(devices.get(i).getAddress());
            new AlertDialog.Builder(this).setTitle("차량 헤드유닛 선택").setItems(labels, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    prefs.setCar(devices.get(which).getAddress());
                    toast("차량 저장");
                }
            }).show();
        } catch (SecurityException error) {
            toast("권한 없음");
        }
    }

    private void scanLamp() {
        if (!granted() || adapter == null) { toast("권한을 먼저 허용하세요"); return; }
        final BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { toast("블루투스를 켜세요"); return; }
        final Map<String, String> found = new LinkedHashMap<String, String>();
        final Set<String> zenggeFound = new HashSet<String>();
        final ScanCallback callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                boolean lotus = false, zengge = false;
                ScanRecord record = result.getScanRecord();
                List<ParcelUuid> uuids = record == null ? null : record.getServiceUuids();
                if (uuids != null) for (ParcelUuid id : uuids) lotus |= GattTransport.LOTUS_SERVICE.equals(id.getUuid());
                SparseArray<byte[]> makers = record == null ? null : record.getManufacturerSpecificData();
                if (makers != null) for (int i = 0; i < makers.size(); i++) zengge |= (makers.keyAt(i) & 0xff00) == 0x5a00;
                String deviceName = name(device);
                zengge |= deviceName.startsWith("LEDnetWF");
                String label = (lotus ? "[Lotus] " : zengge ? "[Zengge] " : "") + deviceName + "  " + mask(device.getAddress())
                        + "  " + result.getRssi() + "dBm";
                if (lotus || zengge || !deviceName.startsWith("(")) {
                    found.put(device.getAddress(), label);
                    if (zengge) zenggeFound.add(device.getAddress());
                }
            }
        };
        try {
            scanner.startScan(callback);
        } catch (SecurityException error) {
            toast("권한 없음");
            return;
        }
        toast("찾는 중... Lotus/Zengge 앱이 조명에 연결돼 있으면 안 보일 수 있습니다");
        ui.postDelayed(new Runnable() {
            public void run() {
                try { scanner.stopScan(callback); } catch (SecurityException ignored) { }
                final List<String> addresses = new ArrayList<String>(found.keySet());
                if (addresses.isEmpty()) { toast("찾은 조명 없음"); return; }
                String[] labels = found.values().toArray(new String[0]);
                new AlertDialog.Builder(MainActivity.this).setTitle("조명 선택 ([Zengge]는 Zengge 칸, 나머지는 Lotus 칸)")
                        .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String address = addresses.get(which);
                        if (zenggeFound.contains(address)) {
                            prefs.setZengge(address);
                            toast("Zengge 조명 저장 (중계를 다시 시작하면 적용)");
                        } else {
                            prefs.setLamp(address);
                            toast("Lotus 조명 저장 (중계를 다시 시작하면 적용)");
                        }
                    }
                }).show();
            }
        }, 8000);
    }

    /** Scans BLE devices to add any third-party aftermarket ambient lamp. */
    private void scanCustomLamp() {
        if (!granted() || adapter == null) { toast("먼저 블루투스 권한을 허용하세요"); return; }
        final BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { toast("블루투스를 켜세요"); return; }
        final Map<String, String> foundLabels = new LinkedHashMap<String, String>();
        final Map<String, String> foundNames = new LinkedHashMap<String, String>();
        final ScanCallback callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                ScanRecord record = result.getScanRecord();
                String badge = detectBadge(device, record);
                String deviceName = name(device);
                if (badge.length() == 0 && deviceName.startsWith("(")) return;
                String addr = device.getAddress();
                foundNames.put(addr, deviceName.startsWith("(") ? "사제 엠비언트" : deviceName);
                foundLabels.put(addr, (badge.length() > 0 ? badge + " " : "[BLE] ")
                        + deviceName + "  " + mask(addr) + "  " + result.getRssi() + "dBm");
            }
        };
        try {
            scanner.startScan(callback);
        } catch (SecurityException error) {
            toast("권한 없음");
            return;
        }
        toast("6초간 주변 BLE 사제 엠비언트를 찾는 중... (기존 조명 앱은 닫아 두세요)");
        ui.postDelayed(new Runnable() {
            public void run() {
                try { scanner.stopScan(callback); } catch (SecurityException ignored) { }
                final List<String> addresses = new ArrayList<String>(foundLabels.keySet());
                List<String> items = new ArrayList<String>(foundLabels.values());
                items.add("▶ MAC 주소·RGB 형식 직접 입력");
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("추가할 사제 엠비언트 선택 (" + addresses.size() + "개 발견)")
                        .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                if (which >= addresses.size()) {
                                    openCustomLampEditor(null, "", "", null);
                                } else {
                                    String addr = addresses.get(which);
                                    String devName = foundNames.get(addr);
                                    inspectAndOpenCustomEditor(addr, devName);
                                }
                            }
                        })
                        .setNegativeButton("닫기", null)
                        .show();
            }
        }, 6000);
    }

    private String detectBadge(BluetoothDevice device, ScanRecord record) {
        String deviceName = name(device);
        String upper = deviceName.toUpperCase(Locale.US);
        if (upper.startsWith("LEDNETWF")) return "[Zengge]";
        if (upper.contains("GOVEE")) return "[Govee]";
        if (upper.contains("TRIONES") || upper.contains("HAPPY")) return "[Triones]";
        if (upper.contains("ELK-BLEDOM") || upper.contains("MELK")) return "[Lotus]";
        if (record != null) {
            List<ParcelUuid> uuids = record.getServiceUuids();
            if (uuids != null) {
                for (ParcelUuid id : uuids) {
                    if (GattTransport.LOTUS_SERVICE.equals(id.getUuid())) return "[Lotus]";
                    if (GattTransport.TRIONES_SERVICE.equals(id.getUuid())) return "[Triones]";
                    if (GattTransport.MAGIC_SERVICE.equals(id.getUuid())) return "[MagicBlue]";
                }
            }
            SparseArray<byte[]> makers = record.getManufacturerSpecificData();
            if (makers != null) {
                for (int i = 0; i < makers.size(); i++) {
                    if ((makers.keyAt(i) & 0xff00) == 0x5a00) return "[Zengge]";
                }
            }
        }
        return "";
    }

    private void inspectAndOpenCustomEditor(final String address, final String deviceName) {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("사제 엠비언트 BLE 탐색")
                .setMessage(deviceName + " (" + mask(address) + ")의 쓰기 가능한 GATT 특성을 자동으로 찾는 중...")
                .setCancelable(false)
                .show();
        BleLampProbe.inspect(this, adapter, address, new BleLampProbe.InspectCallback() {
            public void onResult(List<BleLampProbe.WritableTarget> targets, String error) {
                if (progress.isShowing()) progress.dismiss();
                if (error != null) toast(error + " — 수동으로 UUID를 선택/입력할 수 있습니다");
                openCustomLampEditor(null, deviceName, address, targets);
            }
        });
    }

    private void manageCustomLamps() {
        final List<CustomLampProfile> list = prefs.customLamps();
        if (list.isEmpty()) {
            toast("등록된 사제 엠비언트가 없습니다. '+ 사제 엠비언트 추가'를 눌러 등록하세요.");
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            CustomLampProfile p = list.get(i);
            labels[i] = (p.enabled ? "[연동중] " : "[꺼짐] ") + p.name + " (" + mask(p.address) + ")\n"
                    + "  " + p.colorOrder + " / 최대 " + p.maxBrightness + "% / RGB "
                    + p.redGain + "," + p.greenGain + "," + p.blueGain + "%";
        }
        new AlertDialog.Builder(this)
                .setTitle("등록된 사제 엠비언트")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        showCustomLampActions(list.get(which));
                    }
                })
                .setPositiveButton("+ 새로 추가", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { scanCustomLamp(); }
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void showCustomLampActions(final CustomLampProfile profile) {
        String toggleLabel = profile.enabled ? "연동 끄기 (임시 비활성화)" : "연동 켜기";
        String[] actions = {toggleLabel, "RGB 설정 · 패킷 수정 및 테스트", "삭제"};
        new AlertDialog.Builder(this)
                .setTitle(profile.name + " (" + mask(profile.address) + ")")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            prefs.setCustomLampEnabled(profile.id, !profile.enabled);
                            updateCustomButtonLabel();
                            notifyCustomLampsChanged();
                            toast(profile.enabled ? "연동을 껐습니다" : "연동을 켰습니다");
                        } else if (which == 1) {
                            openCustomLampEditor(profile, profile.name, profile.address, null);
                        } else {
                            prefs.removeCustomLamp(profile.id);
                            RelayStatus.clearCustom(profile.id);
                            updateCustomButtonLabel();
                            notifyCustomLampsChanged();
                            toast("삭제했습니다");
                        }
                    }
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void openCustomLampEditor(final CustomLampProfile existing, String initialName, String initialAddress,
                                      final List<BleLampProbe.WritableTarget> discovered) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(36, 20, 36, 12);

        final EditText nameEdit = field(box, "조명 이름 (예: 풋등, 도어몰딩)",
                existing != null ? existing.name : (initialName == null || initialName.length() == 0 ? "사제 엠비언트" : initialName));
        final EditText macEdit = field(box, "BLE MAC 주소 (AA:BB:CC:DD:EE:FF)",
                existing != null ? existing.address : (initialAddress == null ? "" : initialAddress));

        BleLampProbe.WritableTarget firstTarget = discovered != null && !discovered.isEmpty() ? discovered.get(0) : null;
        int matchedIdx = CustomLampProfile.matchPresetIndex(
                firstTarget != null ? firstTarget.serviceUuid : (existing != null ? existing.serviceUuid : ""),
                firstTarget != null ? firstTarget.writeUuid : (existing != null ? existing.writeUuid : ""),
                initialName);
        CustomLampProfile.Preset initialPreset = CustomLampProfile.PRESETS.get(matchedIdx);

        String initSvc = existing != null ? CustomLampProfile.shortUuid(existing.serviceUuid)
                : (firstTarget != null ? CustomLampProfile.shortUuid(firstTarget.serviceUuid)
                : (initialPreset.serviceUuid.length() > 0 ? CustomLampProfile.shortUuid(initialPreset.serviceUuid) : "fff0"));
        String initWrt = existing != null ? CustomLampProfile.shortUuid(existing.writeUuid)
                : (firstTarget != null ? CustomLampProfile.shortUuid(firstTarget.writeUuid)
                : (initialPreset.writeUuid.length() > 0 ? CustomLampProfile.shortUuid(initialPreset.writeUuid) : "fff3"));
        String initNtf = existing != null ? (existing.notifyUuid.length() == 0 ? "" : CustomLampProfile.shortUuid(existing.notifyUuid))
                : (firstTarget != null && firstTarget.notifyUuid.length() > 0 ? CustomLampProfile.shortUuid(firstTarget.notifyUuid)
                : (initialPreset.notifyUuid.length() > 0 ? CustomLampProfile.shortUuid(initialPreset.notifyUuid) : ""));
        String initTpl = existing != null ? existing.template : initialPreset.template;
        final ColorOrder[] chosenOrder = {existing != null ? existing.colorOrder : ColorOrder.RGB};
        int initMax = existing != null ? existing.maxBrightness : initialPreset.defaultMaxBrightness;
        String initGains = existing != null
                ? (existing.redGain + "," + existing.greenGain + "," + existing.blueGain) : "100,100,100";
        boolean initResp = existing != null ? existing.writeWithResponse : (firstTarget != null && firstTarget.preferResponse());
        boolean initInstant = existing != null ? existing.instant : initialPreset.instant;

        final EditText svcEdit = field(box, "GATT 서비스 UUID (4자리 또는 전체, 예: fff0)", initSvc);
        final EditText wrtEdit = field(box, "GATT 쓰기 특성 UUID (4자리 또는 전체, 예: fff3)", initWrt);
        final EditText ntfEdit = field(box, "GATT 알림 UUID (선택, 없으면 비움)", initNtf);

        final CheckBox respCheck = new CheckBox(this);
        respCheck.setText("응답 쓰기 (Write With Response — 자동 감지됨)");
        respCheck.setChecked(initResp);

        final CheckBox instantCheck = new CheckBox(this);
        instantCheck.setText("즉시 전송 모드 (중간 페이드 생략 — Zengge류)");
        instantCheck.setChecked(initInstant);

        if (discovered != null && !discovered.isEmpty()) {
            button(box, "발견된 BLE 쓰기 특성 선택 (" + discovered.size() + "개)", new View.OnClickListener() {
                public void onClick(View v) {
                    String[] labels = new String[discovered.size()];
                    for (int i = 0; i < discovered.size(); i++) labels[i] = discovered.get(i).label();
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("BLE 쓰기 특성 선택")
                            .setItems(labels, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int which) {
                                    BleLampProbe.WritableTarget t = discovered.get(which);
                                    svcEdit.setText(CustomLampProfile.shortUuid(t.serviceUuid));
                                    wrtEdit.setText(CustomLampProfile.shortUuid(t.writeUuid));
                                    ntfEdit.setText(t.notifyUuid.length() == 0 ? "" : CustomLampProfile.shortUuid(t.notifyUuid));
                                    respCheck.setChecked(t.preferResponse());
                                }
                            }).show();
                }
            });
        }

        final EditText tplEdit = field(box, "RGB 패킷 형식 (RR GG BB 필수, 선택: SQ/CS/XR)", initTpl);
        final EditText maxEdit = field(box, "최대 밝기 (1~100%)", String.valueOf(initMax));
        maxEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText gainEdit = field(box, "RGB 비율 R,G,B (0~100%, 예: 100,100,100)", initGains);

        button(box, "프로토콜 프리셋 선택 (자동 추천: " + initialPreset.label + ")", new View.OnClickListener() {
            public void onClick(View v) {
                String[] labels = new String[CustomLampProfile.PRESETS.size()];
                for (int i = 0; i < labels.length; i++) {
                    CustomLampProfile.Preset p = CustomLampProfile.PRESETS.get(i);
                    labels[i] = p.label + "\n  " + p.template;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("사제 엠비언트 프리셋")
                        .setItems(labels, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                CustomLampProfile.Preset p = CustomLampProfile.PRESETS.get(which);
                                tplEdit.setText(p.template);
                                maxEdit.setText(String.valueOf(p.defaultMaxBrightness));
                                instantCheck.setChecked(p.instant);
                                if (discovered == null || discovered.isEmpty()) {
                                    if (p.serviceUuid.length() > 0) svcEdit.setText(CustomLampProfile.shortUuid(p.serviceUuid));
                                    if (p.writeUuid.length() > 0) wrtEdit.setText(CustomLampProfile.shortUuid(p.writeUuid));
                                    ntfEdit.setText(p.notifyUuid.length() == 0 ? "" : CustomLampProfile.shortUuid(p.notifyUuid));
                                }
                                toast("프리셋 적용: " + p.label);
                            }
                        }).show();
            }
        });

        button(box, "샘플 RGB 값으로 패킷 자동 추출 (마법사)", new View.OnClickListener() {
            public void onClick(View v) {
                openSampleInferDialog(tplEdit);
            }
        });

        final Button orderButton = button(box, "색 순서: " + chosenOrder[0].name() + " (빨강·초록이 바뀌면 변경)", null);
        orderButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final ColorOrder[] orders = ColorOrder.values();
                String[] labels = new String[orders.length];
                for (int i = 0; i < orders.length; i++) {
                    labels[i] = orders[i].name() + (orders[i] == chosenOrder[0] ? " (현재)" : "");
                }
                new AlertDialog.Builder(MainActivity.this).setTitle("색 순서 선택").setItems(labels,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                chosenOrder[0] = orders[which];
                                orderButton.setText("색 순서: " + chosenOrder[0].name() + " (빨강·초록이 바뀌면 변경)");
                            }
                        }).show();
            }
        });

        box.addView(respCheck);
        box.addView(instantCheck);

        text(box, "\n실시간 RGB 테스트 (등록 전에 조명이 색대로 켜지는지 바로 확인)");
        final EditText testRgbEdit = field(box, "테스트 RGB (R,G,B, 예: 255,0,0)", "255,0,0");
        final TextView testInfo = new TextView(this);
        testInfo.setText("테스트 버튼을 누르면 설정된 패킷을 조명에 1회 전송합니다.");
        box.addView(testInfo);

        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        quickTestButton(testRow, "빨강", 255, 0, 0, macEdit, svcEdit, wrtEdit, ntfEdit, tplEdit, chosenOrder, respCheck, testInfo);
        quickTestButton(testRow, "초록", 0, 255, 0, macEdit, svcEdit, wrtEdit, ntfEdit, tplEdit, chosenOrder, respCheck, testInfo);
        quickTestButton(testRow, "파랑", 0, 0, 255, macEdit, svcEdit, wrtEdit, ntfEdit, tplEdit, chosenOrder, respCheck, testInfo);
        box.addView(testRow);

        button(box, "입력한 RGB 값으로 테스트 전송", new View.OnClickListener() {
            public void onClick(View v) {
                int[] rgb = Prefs.parseRgb(testRgbEdit.getText().toString());
                if (rgb == null) { toast("테스트 RGB를 0~255 범위 R,G,B로 입력하세요"); return; }
                runLiveTest(macEdit, svcEdit, wrtEdit, ntfEdit, tplEdit, chosenOrder[0], respCheck.isChecked(),
                        rgb[0], rgb[1], rgb[2], testInfo);
            }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "새 사제 엠비언트 등록" : "사제 엠비언트 수정")
                .setView(scroll)
                .setPositiveButton("등록 및 연동", null)
                .setNegativeButton("취소", null)
                .create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        try {
                            int maxB = Integer.parseInt(maxEdit.getText().toString().trim());
                            int[] gains = Prefs.parseTriple(gainEdit.getText().toString(), 0, 100);
                            if (gains == null) throw new IllegalArgumentException("RGB 비율을 0~100 범위 R,G,B로 입력하세요");
                            String id = existing != null ? existing.id : prefs.nextCustomId();
                            boolean enabled = existing == null || existing.enabled;
                            CustomLampProfile profile = new CustomLampProfile(
                                    id,
                                    nameEdit.getText().toString(),
                                    macEdit.getText().toString(),
                                    svcEdit.getText().toString(),
                                    wrtEdit.getText().toString(),
                                    ntfEdit.getText().toString(),
                                    tplEdit.getText().toString(),
                                    chosenOrder[0],
                                    maxB, gains[0], gains[1], gains[2],
                                    respCheck.isChecked(),
                                    instantCheck.isChecked(),
                                    enabled);
                            prefs.saveCustomLamp(profile);
                            updateCustomButtonLabel();
                            notifyCustomLampsChanged();
                            toast("'" + profile.name + "' 등록 완료!"
                                    + (RelayStatus.running ? " 즉시 연동합니다." : " '4. 중계 시작'을 누르면 연동됩니다."));
                            dialog.dismiss();
                        } catch (IllegalArgumentException error) {
                            toast("확인 필요: " + error.getMessage());
                        }
                    }
                });
            }
        });
        dialog.show();
    }

    private void quickTestButton(LinearLayout row, String label, final int r, final int g, final int b,
                                 final EditText macEdit, final EditText svcEdit, final EditText wrtEdit,
                                 final EditText ntfEdit, final EditText tplEdit,
                                 final ColorOrder[] chosenOrder, final CheckBox respCheck,
                                 final TextView testInfo) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                runLiveTest(macEdit, svcEdit, wrtEdit, ntfEdit, tplEdit, chosenOrder[0], respCheck.isChecked(),
                        r, g, b, testInfo);
            }
        });
        row.addView(btn);
    }

    private void runLiveTest(EditText macEdit, EditText svcEdit, EditText wrtEdit, EditText ntfEdit,
                             EditText tplEdit, ColorOrder order, boolean writeWithResponse,
                             int r, int g, int b, final TextView testInfo) {
        if (!granted() || adapter == null) { toast("블루투스 권한이 필요합니다"); return; }
        try {
            String mac = CustomLampProfile.normalizeMac(macEdit.getText().toString());
            String svc = CustomLampProfile.normalizeUuid(svcEdit.getText().toString());
            String wrt = CustomLampProfile.normalizeUuid(wrtEdit.getText().toString());
            String ntfRaw = ntfEdit.getText().toString().trim();
            String ntf = ntfRaw.length() == 0 ? "" : CustomLampProfile.normalizeUuid(ntfRaw);
            CustomRgbTemplate tpl = CustomRgbTemplate.parse(tplEdit.getText().toString());
            int[] wire = order.toWire(r, g, b);
            byte[] packet = tpl.format(wire[0], wire[1], wire[2], 1);
            final String hex = toHex(packet);
            testInfo.setText("전송 중... RGB(" + r + "," + g + "," + b + ") → " + hex);
            BleLampProbe.testWrite(this, adapter, mac, svc, wrt, ntf, writeWithResponse, packet,
                    new BleLampProbe.TestCallback() {
                        public void onResult(boolean ok, String message) {
                            testInfo.setText((ok ? "[성공] " : "[실패] ") + message + "\n패킷: " + hex);
                            toast(message);
                        }
                    });
        } catch (IllegalArgumentException error) {
            testInfo.setText("입력 오류: " + error.getMessage());
            toast("입력 오류: " + error.getMessage());
        }
    }

    /** Wizard that infers an RGB template automatically from 1 distinct sample or 2~3 RGB samples. */
    private void openSampleInferDialog(final EditText targetTemplateField) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(36, 20, 36, 12);
        text(box, "사제 앱에서 보낸 RGB 값과 패킷(Hex)을 넣으면 RR, GG, BB 위치와 체크섬을 자동으로 찾습니다.\n"
                + "• 1개만 넣을 때: 서로 다른 R,G,B 값 (예: 18,52,86)\n"
                + "• 2~3개 넣을 때: 255,0,0 / 0,128,0 / 0,0,64 처럼 밝기가 다른 3색 권장 (체크섬까지 자동 인식)");

        final EditText rgb1 = field(box, "샘플 1 RGB (예: 255,0,0 또는 18,52,86)", "255,0,0");
        final EditText hex1 = field(box, "샘플 1 패킷 Hex (예: 56 ff 00 00 00 f0 aa)", "");
        final EditText rgb2 = field(box, "샘플 2 RGB (선택, 예: 0,128,0)", "0,128,0");
        final EditText hex2 = field(box, "샘플 2 패킷 Hex (선택)", "");
        final EditText rgb3 = field(box, "샘플 3 RGB (선택, 예: 0,0,64)", "0,0,64");
        final EditText hex3 = field(box, "샘플 3 패킷 Hex (선택)", "");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("샘플 RGB로 패킷 자동 추출")
                .setView(scroll)
                .setPositiveButton("자동 추출", null)
                .setNegativeButton("취소", null)
                .create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        try {
                            List<String> hexes = new ArrayList<String>();
                            List<int[]> rgbs = new ArrayList<int[]>();
                            EditText[] rFields = {rgb1, rgb2, rgb3};
                            EditText[] hFields = {hex1, hex2, hex3};
                            for (int i = 0; i < 3; i++) {
                                String h = hFields[i].getText().toString().trim();
                                if (h.length() == 0) continue;
                                int[] c = Prefs.parseRgb(rFields[i].getText().toString());
                                if (c == null) throw new IllegalArgumentException("샘플 " + (i + 1) + " RGB 형식 오류 (0~255 R,G,B)");
                                hexes.add(h);
                                rgbs.add(c);
                            }
                            if (hexes.isEmpty()) throw new IllegalArgumentException("샘플 1 패킷 Hex를 입력하세요");
                            CustomRgbTemplate inferred;
                            if (hexes.size() == 1) {
                                int[] c = rgbs.get(0);
                                inferred = CustomRgbTemplate.inferFromSample(hexes.get(0), c[0], c[1], c[2]);
                            } else {
                                inferred = CustomRgbTemplate.inferFromSamples(
                                        hexes.toArray(new String[0]),
                                        rgbs.toArray(new int[0][]));
                            }
                            targetTemplateField.setText(inferred.normalized());
                            toast("추출 완료: " + inferred.normalized());
                            dialog.dismiss();
                        } catch (IllegalArgumentException error) {
                            toast("추출 실패: " + error.getMessage());
                        }
                    }
                });
            }
        });
        dialog.show();
    }

    private void notifyCustomLampsChanged() {
        if (RelayStatus.running) {
            startService(new Intent(this, RelayService.class).setAction(RelayService.ACTION_RELOAD_LAMPS));
        }
    }

    private void clearLamp() {
        new AlertDialog.Builder(this).setTitle("지울 기본 조명").setItems(new String[] {"Lotus", "Zengge"},
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) prefs.setLamp(null); else prefs.setZengge(null);
                        toast("지웠습니다 (중계를 다시 시작하면 적용)");
                    }
                }).show();
    }

    private void chooseOrder() {
        final ColorOrder[] orders = ColorOrder.values();
        String[] labels = new String[orders.length];
        for (int i = 0; i < orders.length; i++) labels[i] = orders[i].name() + (orders[i] == prefs.zenggeOrder() ? "  (현재)" : "");
        new AlertDialog.Builder(this).setTitle("Zengge 색 순서").setItems(labels, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                prefs.setZenggeOrder(orders[which]);
                toast(orders[which] + " 저장 (중계를 다시 시작하면 적용)");
            }
        }).show();
    }

    private boolean granted() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private static String name(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "(이름 없음)" : name;
        } catch (SecurityException error) {
            return "(권한 없음)";
        }
    }

    /** Shows only the last two octets on screen. */
    static String mask(String address) {
        if (address == null) return "미선택";
        return address.length() == 17 ? "…" + address.substring(12) : address;
    }

    private static String toHex(byte[] packet) {
        StringBuilder sb = new StringBuilder();
        for (byte b : packet) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private final Runnable pushTone = new Runnable() {
        public void run() {
            if (RelayStatus.running) {
                startService(new Intent(MainActivity.this, RelayService.class).setAction(RelayService.ACTION_RELOAD_THEMES));
            }
        }
    };

    private void slider(LinearLayout layout, final String label, final String key, final int min, int max, final int step,
                        final String unit) {
        final TextView caption = new TextView(this);
        layout.addView(caption);
        SeekBar bar = new SeekBar(this);
        bar.setMax((max - min) / step);
        int value = prefs.get(key);
        bar.setProgress(Math.max(0, Math.min(bar.getMax(), (value - min) / step)));
        caption.setText(label + ": " + value + unit);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int chosen = min + progress * step;
                caption.setText(label + ": " + chosen + unit);
                if (!fromUser) return;
                prefs.set(key, chosen);
                ui.removeCallbacks(pushTone);
                ui.postDelayed(pushTone, 150);
            }
            public void onStartTrackingTouch(SeekBar seekBar) { }
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        layout.addView(bar);
    }

    private EditText field(LinearLayout layout, String label, String value) {
        text(layout, label);
        EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        edit.setText(value);
        layout.addView(edit);
        return edit;
    }

    private void text(LinearLayout layout, String value) {
        TextView view = new TextView(this);
        view.setText(value);
        layout.addView(view);
    }

    private Button button(LinearLayout layout, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        if (listener != null) button.setOnClickListener(listener);
        layout.addView(button);
        return button;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
