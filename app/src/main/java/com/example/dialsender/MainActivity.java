package com.example.dialsender;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@SuppressWarnings({"MissingPermission", "deprecation"})
public class MainActivity extends Activity {

    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final int CHUNK = 965;
    static final int DIAL_ID = 12530;

    EditText etLe;
    TextView tvLog;
    ScrollView scroll;
    Button btn;

    volatile BluetoothGatt gatt;
    volatile boolean connected;
    volatile CountDownLatch connLatch, svcLatch, descLatch;
    final LinkedBlockingQueue<String> notes = new LinkedBlockingQueue<>();
    final LinkedBlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();

    // ---------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView info = new TextView(this);
        info.setText("Langkah sebelum menekan tombol:\n"
                + "1. Tutup aplikasi OnWear Pro sepenuhnya (Info aplikasi > Paksa berhenti).\n"
                + "2. Pastikan jam sudah dipasangkan di Pengaturan Bluetooth HP.\n"
                + "3. Dekatkan jam ke HP, lalu tekan tombol di bawah.");
        root.addView(info);

        TextView l1 = new TextView(this);
        l1.setText("Alamat Bluetooth jam:");
        root.addView(l1);
        etLe = new EditText(this);
        etLe.setSingleLine(true);
        etLe.setText("B4:08:75:0F:17:85");
        root.addView(etLe);

        btn = new Button(this);
        btn.setText("Kirim watchface ke jam");
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSend();
            }
        });
        root.addView(btn);

        scroll = new ScrollView(this);
        tvLog = new TextView(this);
        tvLog.setTextSize(12);
        scroll.addView(tvLog);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    void log(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvLog.append(s + "\n");
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    void setBusy(final boolean busy) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                btn.setEnabled(!busy);
            }
        });
    }

    void onSend() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            log("Berikan izin Bluetooth, lalu tekan tombol lagi.");
            return;
        }
        final String le = etLe.getText().toString().trim().toUpperCase();
        final String classic = le;
        if (!BluetoothAdapter.checkBluetoothAddress(le)) {
            log("Format alamat salah. Contoh: B4:08:75:0F:17:85");
            return;
        }
        setBusy(true);
        tvLog.setText("");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runSend(le, classic);
                } catch (Throwable t) {
                    log("ERROR: " + t);
                } finally {
                    cleanup();
                    setBusy(false);
                }
            }
        }).start();
    }

    // ---------------------------------------------------------------- BLE

    final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                log("BLE tersambung.");
                CountDownLatch l = connLatch;
                if (l != null) l.countDown();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                log("BLE terputus (kode " + status + ").");
                CountDownLatch l2 = connLatch;
                if (l2 != null) l2.countDown();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            CountDownLatch l = svcLatch;
            if (l != null) l.countDown();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            CountDownLatch l = descLatch;
            if (l != null) l.countDown();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null) return;
            String s = toText(v);
            log("< " + s);
            notes.offer(s);
        }
    };

    static String toText(byte[] v) {
        int n = v.length;
        while (n > 0 && v[n - 1] == 0) n--;
        boolean ascii = n > 0;
        for (int i = 0; i < n; i++) {
            int x = v[i] & 0xff;
            if (x < 32 || x > 126) {
                ascii = false;
                break;
            }
        }
        if (ascii) return new String(v, 0, n, StandardCharsets.US_ASCII);
        StringBuilder sb = new StringBuilder("hex:");
        for (int i = 0; i < Math.min(v.length, 20); i++) sb.append(String.format("%02x", v[i] & 0xff));
        return sb.toString();
    }

    boolean writeAt(BluetoothGattCharacteristic c, String text) {
        byte[] b = (text + "\0").getBytes(StandardCharsets.US_ASCII);
        c.setValue(b);
        int p = c.getProperties();
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else {
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        }
        return gatt.writeCharacteristic(c);
    }

    String waitNote(String prefix, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return null;
            String s = notes.poll(left, TimeUnit.MILLISECONDS);
            if (s == null) return null;
            if (s.startsWith(prefix)) return s;
        }
    }

    // ---------------------------------------------------------------- frames

    static int u32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16)
                | ((b[o + 3] & 0xff) << 24);
    }

    static void put16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }

    static void put32(ByteArrayOutputStream o, int v) {
        put16(o, v & 0xffff);
        put16(o, (v >>> 16) & 0xffff);
    }

    static int crc16(byte[] b, int off, int len) {
        int c = 0;
        for (int i = off; i < off + len; i++) {
            c ^= (b[i] & 0xff) << 8;
            for (int k = 0; k < 8; k++) {
                c = ((c & 0x8000) != 0) ? ((c << 1) ^ 0x1021) : (c << 1);
                c &= 0xffff;
            }
        }
        return c;
    }

    /** Paket "info file": nama, ukuran, ukuran potongan. */
    static byte[] infoFrame(byte[] id, int size, String path) {
        byte[] pb = path.getBytes(StandardCharsets.US_ASCII);
        int plen1 = pb.length + 1;
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(0x24);
        p.write(0x06);
        p.write(0x02);
        p.write(0x81);
        p.write(0x00); // diisi di bawah
        p.write(id, 0, 4);
        p.write(0x00);
        p.write(0xb0);
        p.write(0x02);
        p.write(0x00);
        put32(p, size);
        p.write(0x32);
        p.write(0x00);
        put32(p, CHUNK);
        put16(p, plen1 + 3);
        put16(p, plen1);
        p.write(0x00);
        p.write(0x00);
        p.write(pb, 0, pb.length);
        p.write(0x00);
        byte[] pay = p.toByteArray();
        int total = pay.length + 2;
        pay[4] = (byte) ((total + 0x79) & 0xff);
        int crc = crc16(pay, 0, pay.length);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xaf);
        o.write(0x03);
        put16(o, total);
        o.write(pay, 0, pay.length);
        o.write(crc & 0xff); // sesuai rekaman: hanya 1 byte CRC
        return o.toByteArray();
    }

    /** Paket data: satu potongan file. */
    static byte[] dataFrame(byte[] id, int off, byte[] file, int n) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(0x24);
        p.write(0x06);
        p.write(0x04);
        p.write(0x81);
        p.write(0x02);
        put16(p, n + 12);
        p.write(id, 0, 4);
        put32(p, off);
        put32(p, n);
        p.write(file, off, n);
        byte[] pay = p.toByteArray();
        int crc = crc16(pay, 0, pay.length);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xaf);
        o.write(0x03);
        put16(o, pay.length + 2);
        o.write(pay, 0, pay.length);
        put16(o, crc);
        return o.toByteArray();
    }

    // ---------------------------------------------------------------- main flow

    void runSend(String leAddr, String classicAddr) throws Exception {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            log("Bluetooth HP belum menyala.");
            return;
        }

        // 1. File watchface bawaan aplikasi
        byte[] file = readAsset("dial.bin");
        int size = file.length;
        long ts = System.currentTimeMillis() / 1000L;
        String path = "/user/watchface/Dial_online_L" + size + "_" + ts + "_" + DIAL_ID + ".bin";
        log("File: " + path + " (" + size + " byte)");

        // 2. Sambung BLE
        BluetoothDevice le = ad.getRemoteDevice(leAddr);
        for (int attempt = 1; attempt <= 4 && !connected; attempt++) {
            log("Menyambung BLE (percobaan " + attempt + ")...");
            connLatch = new CountDownLatch(1);
            gatt = le.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE);
            connLatch.await(15, TimeUnit.SECONDS);
            if (!connected) {
                try {
                    gatt.disconnect();
                    gatt.close();
                } catch (Exception ignore) {
                }
                gatt = null;
                Thread.sleep(1500);
            }
        }
        if (!connected) {
            log("Gagal tersambung BLE. Pastikan OnWear Pro sudah ditutup, jam dekat HP, "
                    + "dan jam tidak sedang tersambung ke aplikasi lain. Matikan lalu "
                    + "nyalakan Bluetooth HP, lalu coba lagi.");
            return;
        }
        Thread.sleep(800);
        svcLatch = new CountDownLatch(1);
        gatt.discoverServices();
        if (!svcLatch.await(15, TimeUnit.SECONDS)) {
            log("Gagal membaca layanan BLE jam.");
            return;
        }

        List<BluetoothGattCharacteristic> writable = new ArrayList<>();
        for (BluetoothGattService s : gatt.getServices()) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int p = c.getProperties();
                log("char " + c.getUuid() + " sifat=0x" + Integer.toHexString(p));
                if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                    gatt.setCharacteristicNotification(c, true);
                    BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                    if (d != null) {
                        d.setValue((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                        descLatch = new CountDownLatch(1);
                        gatt.writeDescriptor(d);
                        descLatch.await(3, TimeUnit.SECONDS);
                    }
                }
                if ((p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                    writable.add(c);
                }
            }
        }

        // 3. Cari jalur perintah AT (kirim pertanyaan aman "AT+GST?")
        BluetoothGattCharacteristic atChar = null;
        for (BluetoothGattCharacteristic c : writable) {
            notes.clear();
            writeAt(c, "AT+GST?");
            if (waitNote("AT+GST", 1500) != null) {
                atChar = c;
                break;
            }
        }
        if (atChar == null) {
            log("Jalur perintah AT tidak ditemukan. Kirim isi log ini ke Claude.");
            return;
        }
        log("Jalur perintah AT ditemukan.");

        // 4. Mulai mode kirim file
        notes.clear();
        writeAt(atChar, "AT+SUM:1");
        if (waitNote("AT+SUM:1", 6000) == null) {
            log("Jam tidak membalas AT+SUM:1.");
            return;
        }

        // 5. Sambung Bluetooth klasik (SPP)
        ad.cancelDiscovery();
        BluetoothDevice classic = ad.getRemoteDevice(classicAddr);
        BluetoothSocket sock = null;
        try {
            sock = classic.createRfcommSocketToServiceRecord(SPP);
            sock.connect();
        } catch (IOException e1) {
            log("Sambungan klasik (aman) gagal: " + e1.getMessage() + ". Mencoba cara lain...");
            try {
                if (sock != null) sock.close();
            } catch (IOException ignore) {
            }
            sock = classic.createInsecureRfcommSocketToServiceRecord(SPP);
            sock.connect();
        }
        log("Bluetooth klasik tersambung.");
        final BluetoothSocket fsock = sock;
        sockRef = fsock;
        final InputStream in = fsock.getInputStream();
        OutputStream out = fsock.getOutputStream();
        frames.clear();
        startReader(in);

        // 6. Kirim info file
        byte[] id = new byte[4];
        int r = new Random().nextInt(0xffffff);
        id[0] = (byte) r;
        id[1] = (byte) (r >> 8);
        id[2] = (byte) (r >> 16);
        id[3] = 0x2d;
        out.write(infoFrame(id, size, path));
        out.flush();
        log("Info file terkirim, menunggu jam...");

        // 7. Kirim data sesuai izin (jendela) dari jam
        boolean done = false;
        int pos = 0;
        int lastPct = -1;
        while (!done) {
            byte[] f = frames.poll(20, TimeUnit.SECONDS);
            if (f == null) {
                log("Jam berhenti membalas. Transfer dibatalkan.");
                return;
            }
            if (f.length < 5 || (f[0] & 0xff) != 0x42) continue;
            int cmd = f[2] & 0xff;
            if (cmd == 0x03 && f.length >= 21) {
                int off = u32(f, 13);
                int win = u32(f, 17);
                pos = off;
                int limit = Math.min(size, off + win);
                while (pos < limit) {
                    int n = Math.min(CHUNK, limit - pos);
                    out.write(dataFrame(id, pos, file, n));
                    pos += n;
                }
                out.flush();
                int pct = (int) (100L * pos / size);
                if (pct / 10 != lastPct / 10) log("Terkirim " + pct + "%");
                lastPct = pct;
            } else if (cmd == 0x05) {
                log("Jam menyatakan file selesai diterima.");
                done = true;
            } else {
                log("Balasan jam tak dikenal, perintah " + cmd);
            }
        }

        // 8. Tutup sesi
        Thread.sleep(500);
        notes.clear();
        writeAt(atChar, "AT+SUM:205");
        waitNote("AT+SUM:205", 5000);
        log("SELESAI. Periksa daftar watchface di jam Anda.");
    }

    volatile BluetoothSocket sockRef;

    void startReader(final InputStream in) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[1 << 16];
                int len = 0;
                byte[] tmp = new byte[4096];
                try {
                    while (true) {
                        int n = in.read(tmp);
                        if (n < 0) break;
                        if (len + n > buf.length) len = 0;
                        System.arraycopy(tmp, 0, buf, len, n);
                        len += n;
                        while (len >= 4) {
                            if ((buf[0] & 0xff) != 0xaf || buf[1] != 0x03) {
                                System.arraycopy(buf, 1, buf, 0, len - 1);
                                len--;
                                continue;
                            }
                            int L = (buf[2] & 0xff) | ((buf[3] & 0xff) << 8);
                            if (len < 4 + L) break;
                            frames.offer(Arrays.copyOfRange(buf, 4, 4 + L));
                            System.arraycopy(buf, 4 + L, buf, 0, len - 4 - L);
                            len -= 4 + L;
                        }
                    }
                } catch (IOException ignore) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    byte[] readAsset(String name) throws IOException {
        InputStream in = getAssets().open(name);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] t = new byte[8192];
        int n;
        while ((n = in.read(t)) > 0) bo.write(t, 0, n);
        in.close();
        return bo.toByteArray();
    }

    void cleanup() {
        try {
            BluetoothSocket s = sockRef;
            if (s != null) s.close();
        } catch (Exception ignore) {
        }
        sockRef = null;
        try {
            BluetoothGatt g = gatt;
            if (g != null) {
                g.disconnect();
                g.close();
            }
        } catch (Exception ignore) {
        }
        gatt = null;
    }
}
