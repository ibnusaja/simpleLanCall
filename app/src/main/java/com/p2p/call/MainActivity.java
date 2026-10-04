package com.p2p.call;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Bundle;
import android.widget.*;
import java.net.*;
import java.util.Collections;

public class MainActivity extends Activity {
    static final int RATE = 16000, FRAME = 640; // 20 ms PCM16 mono

    TextView info, status;
    EditText ipIn, portIn;
    volatile boolean running;
    volatile InetSocketAddress peer;
    DatagramSocket socket;
    AudioRecord rec;
    AudioTrack trk;
    AudioManager am;
    boolean pendingServer;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        info = findViewById(R.id.info);
        status = findViewById(R.id.status);
        ipIn = findViewById(R.id.ip);
        portIn = findViewById(R.id.port);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        info.setText("IP saya: " + localIp());
        findViewById(R.id.bServer).setOnClickListener(v -> start(true));
        findViewById(R.id.bClient).setOnClickListener(v -> start(false));
        findViewById(R.id.bStop).setOnClickListener(v -> stop());
    }

    String localIp() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
        } catch (Exception e) {}
        return "tidak ada (cek WiFi)";
    }

    void start(boolean server) {
        if (running) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingServer = server;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        final int port = Integer.parseInt(portIn.getText().toString());
        final String ip = ipIn.getText().toString().trim();
        running = true;
        peer = null;
        info.setText("IP saya: " + localIp() + ":" + port + (server ? "  [SERVER]" : "  [CLIENT]"));
        new Thread(() -> run(server, ip, port)).start();
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) start(pendingServer);
    }

    void run(boolean server, String ip, int port) {
        try {
            if (server) socket = new DatagramSocket(port);
            else { socket = new DatagramSocket(); peer = new InetSocketAddress(ip, port); }

            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            int buf = Math.max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT), FRAME * 4);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
            if (AcousticEchoCanceler.isAvailable())
                AcousticEchoCanceler.create(rec.getAudioSessionId()).setEnabled(true);
            trk = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf)
                    .setTransferMode(AudioTrack.MODE_STREAM).build();

            ui(server ? "Menunggu client..." : "Terhubung ke " + ip + ":" + port);
            rec.startRecording();
            trk.play();

            // Thread kirim (mic -> jaringan)
            new Thread(() -> {
                byte[] d = new byte[FRAME];
                try {
                    while (running) {
                        int n = rec.read(d, 0, FRAME);
                        InetSocketAddress p = peer;
                        if (n > 0 && p != null) socket.send(new DatagramPacket(d, n, p));
                    }
                } catch (Exception e) {}
            }).start();

            // Thread terima (jaringan -> speaker), jalan di thread ini
            byte[] d = new byte[2048];
            DatagramPacket pkt = new DatagramPacket(d, d.length);
            while (running) {
                socket.receive(pkt);
                if (server && peer == null) {
                    peer = new InetSocketAddress(pkt.getAddress(), pkt.getPort());
                    ui("Client terhubung: " + pkt.getAddress().getHostAddress());
                }
                trk.write(pkt.getData(), 0, pkt.getLength());
            }
        } catch (Exception e) {
            if (running) ui("Error: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    void stop() { running = false; if (socket != null) socket.close(); ui("Idle"); }

    void cleanup() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception e) {}
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception e) {}
        try { if (trk != null) { trk.stop(); trk.release(); } } catch (Exception e) {}
        am.setMode(AudioManager.MODE_NORMAL);
    }

    void ui(String s) { runOnUiThread(() -> status.setText(s)); }

    @Override protected void onDestroy() { stop(); super.onDestroy(); }
}
