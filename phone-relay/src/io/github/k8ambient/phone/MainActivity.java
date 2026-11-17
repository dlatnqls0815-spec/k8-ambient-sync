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
import android.util.SparseArray;
import io.github.k8ambient.core.ColorOrder;
import io.github.k8ambient.core.DriveModeLight;
import io.github.k8ambient.core.EspConfig;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.view.WindowInsets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.ParcelUuid;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Setup and status screen for the plan B phone relay. Programmatic UI, no resources. */
public final class MainActivity extends Activity {
    private static final String[] PERMISSIONS = {
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private TextView status;
    private EditText themeColor;
    private BluetoothAdapter adapter;
    /** ESP32 settings link (plan C); one background thread owns it. */
    private EspLink esp;
    private final ExecutorService espThread = Executors.newSingleThreadExecutor();
    private final AtomicBoolean espBusy = new AtomicBoolean();
    private volatile String espStatus = "아직 연결 안 함", espTheme;
    private long espPolledAt;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        DiagLog.init(this);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter != null) esp = new EspLink(this, adapter);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        TextView title = new TextView(this);
        title.setText("K8 앰비언트 중계");
        title.setTextSize(22);
        layout.addView(title);
        text(layout, "LotusLamp X·Zengge 앱은 종료하세요. 조명은 한 번에 한 기기에만 연결됩니다.\n"
                + "차량에 NEMESIS 'K8 Ambient Relay'가 설치돼 있어야 합니다.");
        espSection(layout);
        button(layout, "1. 블루투스 권한 허용", new View.OnClickListener() {
            public void onClick(View v) { requestPermissions(PERMISSIONS, 1); }
        });
        button(layout, "2. 차량 선택 (페어링된 기기)", new View.OnClickListener() {
            public void onClick(View v) { chooseCar(); }
        });
        button(layout, "3. 조명 찾기 (8초, Lotus·Zengge 둘 다 가능)", new View.OnClickListener() {
            public void onClick(View v) { scanLamp(); }
        });
        button(layout, "저장된 조명 지우기", new View.OnClickListener() {
            public void onClick(View v) { clearLamp(); }
        });
        button(layout, "Zengge 색 순서 (빨강·초록이 바뀌어 보이면 GRB)", new View.OnClickListener() {
            public void onClick(View v) { chooseOrder(); }
        });
        CheckBox power = new CheckBox(this);
        power.setText("순정 꺼짐 때 조명도 끄기 (실험: 앱 코드에서 읽은 전원 명령)");
        power.setChecked(prefs.power());
        power.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.setPower(checked);
                toast("다음 시작부터 적용됩니다");
                settingsChanged();
            }
        });
        layout.addView(power);
        button(layout, "4. 중계 시작", new View.OnClickListener() {
            public void onClick(View v) {
                if (!granted()) { toast("먼저 권한을 허용하세요"); return; }
                if (prefs.espMode()) { toast("ESP32 모드에서는 휴대폰 중계를 쓰지 않습니다"); return; }
                if (prefs.car() == null || !prefs.anyLamp()) { toast("차량과 조명을 먼저 선택하세요"); return; }
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

        text(layout, "\n순정 테마(드림퍼플 등)일 때 Lotus 색 지정: R,G,B (예: 126,0,255)");
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

        text(layout, "\n부드러운 전환·색 톤 (움직이면 조명에 바로 반영)");
        slider(layout, "전환 시간", Prefs.FADE_MS, 0, 3000, 100, "ms");
        slider(layout, "색 깊이(감마 ×0.1, 높을수록 진하고 덜 하얗게)", Prefs.GAMMA, 10, 30, 1, "");
        slider(layout, "빨강 비율", Prefs.RED, 0, 100, 1, "%");
        slider(layout, "초록 비율", Prefs.GREEN, 0, 100, 1, "%");
        slider(layout, "파랑 비율", Prefs.BLUE, 0, 100, 1, "%");
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
        ui.post(refresh);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        // The ESP32 keeps its own settings; free its BLE slot while the screen is away.
        if (esp != null) espThread.execute(new Runnable() { public void run() { esp.close(); } });
        super.onPause();
    }

    private final Runnable refresh = new Runnable() {
        public void run() {
            StringBuilder s = new StringBuilder("\n");
            if (prefs.espMode()) {
                if (SystemClock.elapsedRealtime() - espPolledAt > 2000) {
                    espPolledAt = SystemClock.elapsedRealtime();
                    pollEsp();
                }
                s.append("[ESP32 모드] ESP32: ").append(mask(prefs.esp())).append('\n').append(espStatus).append("\n\n");
            }
            s.append("중계: ").append(RelayStatus.running ? "실행 중" : "중지").append('\n');
            s.append("차량: ").append(mask(prefs.car())).append(" / ").append(RelayStatus.car).append('\n');
            s.append("Lotus: ").append(mask(prefs.lamp())).append(" / ").append(RelayStatus.lamp[RelayStatus.LOTUS]).append('\n');
            s.append("Zengge: ").append(mask(prefs.zengge())).append(" (").append(prefs.zenggeOrder()).append(") / ")
                    .append(RelayStatus.lamp[RelayStatus.ZENGGE]).append('\n');
            s.append("순정 원시값: ").append(RelayStatus.raw).append('\n');
            s.append("목표: ").append(RelayStatus.target).append('\n');
            s.append("드라이브 모드: ").append(modeName(RelayStatus.driveMode))
                    .append(RelayStatus.driveLinked ? " (순정 연동 켜짐)" : " (순정 연동 꺼짐)").append('\n');
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
        if (mode == DriveModeLight.ECO) return "에코";
        if (mode == DriveModeLight.SPORT) return "스포츠";
        if (mode == DriveModeLight.SMART) return "스마트";
        return mode == 0 ? "아직 모름(바꾸면 표시)" : String.format("코드 %02x", mode);
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
                settingsChanged();
                if (RelayStatus.running) {
                    startService(new Intent(MainActivity.this, RelayService.class).setAction(RelayService.ACTION_RELOAD_THEMES));
                }
                toast("저장했습니다");
            }
        });
    }

    private void assignTheme(int[] rgb, boolean requireColor) {
        String key = prefs.espMode() ? espTheme : RelayStatus.themeKey;
        if (key == null) { toast("지금 순정이 테마 상태일 때 눌러 주세요"); return; }
        if (requireColor && rgb == null) { toast("R,G,B를 0~255로 입력하세요"); return; }
        prefs.setTheme(key, rgb);
        settingsChanged();
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
                    settingsChanged();
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
                            settingsChanged();
                        } else {
                            prefs.setLamp(address);
                            toast("Lotus 조명 저장 (중계를 다시 시작하면 적용)");
                            settingsChanged();
                        }
                    }
                }).show();
            }
        }, 8000);
    }

    private void clearLamp() {
        new AlertDialog.Builder(this).setTitle("지울 조명").setItems(new String[] {"Lotus", "Zengge"},
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) prefs.setLamp(null); else prefs.setZengge(null);
                        toast("지웠습니다 (중계를 다시 시작하면 적용)");
                        settingsChanged();
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
                settingsChanged();
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

    /** Plan C controls: the ESP32 drives the lamps, this app only sends its settings. */
    private void espSection(LinearLayout layout) {
        text(layout, "\nESP32 연동: ESP32가 차량 신호를 받아 조명을 직접 켭니다. 이 앱은 아래 색·밝기 설정을 ESP32에 넘겨줍니다.");
        CheckBox mode = new CheckBox(this);
        mode.setText("ESP32 모드 (휴대폰 중계 끄고 설정만 전달)");
        mode.setChecked(prefs.espMode());
        mode.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.setEspMode(checked);
                if (checked) {
                    // Free the lamps: each accepts one BLE central and the ESP32 needs them.
                    stopService(new Intent(MainActivity.this, RelayService.class));
                    if (prefs.esp() != null) pushEsp(true);
                    toast("휴대폰 중계를 껐습니다");
                } else {
                    if (esp != null) espThread.execute(new Runnable() { public void run() { esp.close(); } });
                    toast("휴대폰 중계 모드. 필요하면 '중계 시작'을 누르세요");
                }
            }
        });
        layout.addView(mode);
        button(layout, "ESP32 찾기 (8초)", new View.OnClickListener() {
            public void onClick(View v) { scanEsp(); }
        });
        button(layout, "ESP32로 설정 다시 보내기", new View.OnClickListener() {
            public void onClick(View v) {
                if (!prefs.espMode()) { toast("먼저 ESP32 모드를 켜세요"); return; }
                pushEsp(true);
            }
        });
        CheckBox output = new CheckBox(this);
        output.setText("ESP32가 조명에 실제로 쓰기 (끄면 ESP32는 USB 시리얼에 기록만)");
        output.setChecked(prefs.espOutput());
        output.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.setEspOutput(checked);
                if (prefs.espMode()) sendEsp(java.util.Arrays.asList(EspConfig.output(checked), "SAVE"), "OK SAVE",
                        checked ? "ESP32 조명 쓰기 켬" : "ESP32 조명 쓰기 끔");
            }
        });
        layout.addView(output);
    }

    /** Settings changed on this screen: in ESP32 mode send them (debounced like the sliders). */
    private void settingsChanged() {
        if (!prefs.espMode()) return;
        ui.removeCallbacks(pushTone);
        ui.postDelayed(pushTone, 150);
    }

    private void pushEsp(boolean announce) {
        List<String> lines;
        try {
            lines = EspConfig.snapshot(prefs.espValues());
        } catch (IllegalArgumentException error) {
            toast("설정 오류: " + error.getMessage());
            return;
        }
        sendEsp(lines, "OK SAVE", announce ? "ESP32에 설정 전달 완료" : null);
    }

    private void sendEsp(final List<String> lines, final String until, final String done) {
        if (esp == null || !granted()) { toast("권한을 먼저 허용하세요"); return; }
        final String target = prefs.esp();
        if (target == null) { toast("먼저 ESP32를 찾으세요"); return; }
        espThread.execute(new Runnable() {
            public void run() {
                String result;
                try {
                    List<String> replies = esp.exchange(target, lines, until, 4000);
                    int ok = 0;
                    String error = null;
                    boolean saved = false;
                    for (String reply : replies) {
                        if (reply.startsWith("OK")) ok++;
                        else if (reply.startsWith("ERR") && error == null) error = reply;
                        saved |= reply.startsWith(until);
                    }
                    result = error != null ? "ESP32 거부: " + error
                            : saved ? done : "ESP32 응답 없음 (" + ok + "/" + lines.size() + ")";
                    DiagLog.event("esp", "설정 " + lines.size() + "줄, OK " + ok + (error != null ? ", " + error : ""));
                } catch (java.io.IOException error) {
                    result = "ESP32: " + error.getMessage();
                    DiagLog.event("esp", "설정 전달 실패: " + error.getMessage());
                }
                final String message = result;
                if (message != null) ui.post(new Runnable() { public void run() { toast(message); } });
            }
        });
    }

    /** Reads the ESP32 status (car link, lamps, current theme) for the screen. */
    private void pollEsp() {
        final String target = prefs.esp();
        if (esp == null || target == null || !granted() || !espBusy.compareAndSet(false, true)) return;
        espThread.execute(new Runnable() {
            public void run() {
                try {
                    List<String> replies = esp.exchange(target, java.util.Collections.singletonList("STATUS"), "ST end", 3000);
                    StringBuilder text = new StringBuilder();
                    String theme = null;
                    for (String reply : replies) {
                        String[] st = EspConfig.parseStatus(reply);
                        if (st == null || st[0].equals("end")) continue;
                        if (st[0].equals("theme")) theme = st[1].equals("-") ? null : st[1];
                        text.append(espLabel(st[0])).append(": ").append(st[1]).append('\n');
                    }
                    espTheme = theme;
                    espStatus = text.length() == 0 ? "ESP32 응답 없음" : text.toString().trim();
                } catch (java.io.IOException error) {
                    espStatus = "ESP32 연결 안 됨: " + error.getMessage();
                } finally {
                    espBusy.set(false);
                }
            }
        });
    }

    static String espLabel(String key) {
        if (key.equals("ver")) return "펌웨어";
        if (key.equals("car")) return "차량 연결";
        if (key.equals("lotus")) return "Lotus";
        if (key.equals("zengge")) return "Zengge";
        if (key.equals("output")) return "조명 쓰기";
        if (key.equals("sml")) return "순정 원시값";
        if (key.equals("theme")) return "현재 테마";
        if (key.equals("mode")) return "모드";
        if (key.equals("target")) return "목표";
        if (key.equals("heap")) return "메모리";
        return key;
    }

    private void scanEsp() {
        if (!granted() || adapter == null) { toast("권한을 먼저 허용하세요"); return; }
        final BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { toast("블루투스를 켜세요"); return; }
        final Map<String, String> found = new LinkedHashMap<String, String>();
        final ScanCallback callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                ScanRecord record = result.getScanRecord();
                boolean ours = name(device).startsWith(EspConfig.DEVICE_NAME);
                List<ParcelUuid> uuids = record == null ? null : record.getServiceUuids();
                if (uuids != null) for (ParcelUuid id : uuids) ours |= EspConfig.SERVICE_UUID.equals(id.getUuid());
                if (ours) found.put(device.getAddress(), name(device) + "  " + mask(device.getAddress()) + "  " + result.getRssi() + "dBm");
            }
        };
        try {
            scanner.startScan(callback);
        } catch (SecurityException error) {
            toast("권한 없음");
            return;
        }
        toast("ESP32 찾는 중...");
        ui.postDelayed(new Runnable() {
            public void run() {
                try { scanner.stopScan(callback); } catch (SecurityException ignored) { }
                final List<String> addresses = new ArrayList<String>(found.keySet());
                if (addresses.isEmpty()) { toast("ESP32를 찾지 못했습니다 (전원·거리 확인)"); return; }
                new AlertDialog.Builder(MainActivity.this).setTitle("ESP32 선택")
                        .setItems(found.values().toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        prefs.setEsp(addresses.get(which));
                        toast("ESP32 저장");
                        if (prefs.espMode()) pushEsp(true);
                    }
                }).show();
            }
        }, 8000);
    }

    /** Shows only the last two octets on screen. */
    static String mask(String address) {
        if (address == null) return "미선택";
        return address.length() == 17 ? "…" + address.substring(12) : address;
    }

    private final Runnable pushTone = new Runnable() {
        public void run() {
            if (prefs.espMode()) pushEsp(false);
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

    private void text(LinearLayout layout, String value) {
        TextView view = new TextView(this);
        view.setText(value);
        layout.addView(view);
    }

    private void button(LinearLayout layout, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        layout.addView(button);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
