package io.github.k8ambient.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.view.WindowInsets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        TextView title = new TextView(this);
        title.setText("K8 앰비언트 중계");
        title.setTextSize(22);
        layout.addView(title);
        text(layout, "LotusLamp X 앱은 종료하세요. 조명은 한 번에 한 기기에만 연결됩니다.\n"
                + "차량에 NEMESIS 'K8 Ambient Relay'가 설치돼 있어야 합니다.");
        button(layout, "1. 블루투스 권한 허용", new View.OnClickListener() {
            public void onClick(View v) { requestPermissions(PERMISSIONS, 1); }
        });
        button(layout, "2. 차량 선택 (페어링된 기기)", new View.OnClickListener() {
            public void onClick(View v) { chooseCar(); }
        });
        button(layout, "3. Lotus 조명 찾기 (8초)", new View.OnClickListener() {
            public void onClick(View v) { scanLamp(); }
        });
        CheckBox power = new CheckBox(this);
        power.setText("순정 꺼짐 때 조명도 끄기 (실험: 앱 코드에서 읽은 전원 명령)");
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
                if (prefs.car() == null || prefs.lamp() == null) { toast("차량과 조명을 먼저 선택하세요"); return; }
                startForegroundService(new Intent(MainActivity.this, RelayService.class));
            }
        });
        button(layout, "중계 중지", new View.OnClickListener() {
            public void onClick(View v) { stopService(new Intent(MainActivity.this, RelayService.class)); }
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

        text(layout, "\n부드러운 전환·색 톤 (움직이면 조명에 바로 반영)");
        slider(layout, "전환 시간", Prefs.FADE_MS, 0, 3000, 100, "ms");
        slider(layout, "색 깊이(감마 ×0.1, 높을수록 진하고 덜 하얗게)", Prefs.GAMMA, 10, 30, 1, "");
        slider(layout, "빨강 비율", Prefs.RED, 0, 100, 1, "%");
        slider(layout, "초록 비율", Prefs.GREEN, 0, 100, 1, "%");
        slider(layout, "파랑 비율", Prefs.BLUE, 0, 100, 1, "%");
        slider(layout, "사제 최대 밝기(순정 최대일 때)", Prefs.MAX, 5, 50, 1, "%");

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
        super.onPause();
    }

    private final Runnable refresh = new Runnable() {
        public void run() {
            StringBuilder s = new StringBuilder("\n");
            s.append("중계: ").append(RelayStatus.running ? "실행 중" : "중지").append('\n');
            s.append("차량: ").append(mask(prefs.car())).append(" / ").append(RelayStatus.car).append('\n');
            s.append("조명: ").append(mask(prefs.lamp())).append(" / ").append(RelayStatus.lamp).append('\n');
            s.append("순정 원시값: ").append(RelayStatus.raw).append('\n');
            s.append("목표: ").append(RelayStatus.target).append('\n');
            s.append("현재 테마: ").append(RelayStatus.themeKey == null ? "직접색 또는 없음" : RelayStatus.themeKey).append('\n');
            s.append("보낸 패킷: ").append(RelayStatus.packets).append(" / 마지막 ").append(RelayStatus.lastSent).append('\n');
            s.append("저장된 테마 색: ");
            for (Map.Entry<String, int[]> e : prefs.themes().entrySet()) {
                int[] c = e.getValue();
                s.append('\n').append("  ").append(e.getKey()).append(" → ").append(c[0]).append(',').append(c[1]).append(',').append(c[2]);
            }
            status.setText(s.toString());
            ui.postDelayed(this, 1000);
        }
    };

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
        final ScanCallback callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                boolean lotus = false;
                List<ParcelUuid> uuids = result.getScanRecord() == null ? null : result.getScanRecord().getServiceUuids();
                if (uuids != null) for (ParcelUuid id : uuids) lotus |= LotusGattTransport.SERVICE.equals(id.getUuid());
                String label = (lotus ? "[FFF0] " : "") + name(device) + "  " + mask(device.getAddress()) + "  " + result.getRssi() + "dBm";
                if (lotus || device.getName() != null) found.put(device.getAddress(), label);
            }
        };
        try {
            scanner.startScan(callback);
        } catch (SecurityException error) {
            toast("권한 없음");
            return;
        }
        toast("찾는 중... 휴대폰 Lotus 앱이 연결돼 있으면 안 보일 수 있습니다");
        ui.postDelayed(new Runnable() {
            public void run() {
                try { scanner.stopScan(callback); } catch (SecurityException ignored) { }
                final List<String> addresses = new ArrayList<String>(found.keySet());
                if (addresses.isEmpty()) { toast("찾은 조명 없음"); return; }
                String[] labels = found.values().toArray(new String[0]);
                new AlertDialog.Builder(MainActivity.this).setTitle("Lotus 조명 선택").setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        prefs.setLamp(addresses.get(which));
                        toast("조명 저장");
                    }
                }).show();
            }
        }, 8000);
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
