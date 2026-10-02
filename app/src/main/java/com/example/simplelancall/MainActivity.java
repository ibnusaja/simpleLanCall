package com.example.simplelancall;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class MainActivity extends Activity {

    private static final int SAMPLE_RATE = 48000;

    private static final int CHANNEL_IN =
            AudioFormat.CHANNEL_IN_STEREO;

    private static final int CHANNEL_OUT =
            AudioFormat.CHANNEL_OUT_STEREO;

    private static final int AUDIO_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT;

    private static final int DEFAULT_PORT = 5000;

    private EditText ipEdit;
    private EditText portEdit;

    private TextView statusText;

    private Button listenButton;
    private Button connectButton;
    private Button endButton;

    private volatile boolean running = false;

    private Socket socket;
    private ServerSocket serverSocket;

    private AudioRecord recorder;
    private AudioTrack player;

    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;

    private Thread captureThread;
    private Thread playbackThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        ipEdit = findViewById(R.id.ipEdit);
        portEdit = findViewById(R.id.portEdit);

        statusText = findViewById(R.id.statusText);

        listenButton = findViewById(R.id.listenButton);
        connectButton = findViewById(R.id.connectButton);
        endButton = findViewById(R.id.endButton);

        listenButton.setOnClickListener(
                v -> startListening());

        connectButton.setOnClickListener(
                v -> connect());

        endButton.setOnClickListener(
                v -> endCall());

        requestMicrophone();
    }

    private void requestMicrophone() {

        if (android.os.Build.VERSION.SDK_INT >= 23) {

            if (checkSelfPermission(
                    Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(
                        new String[]{
                                Manifest.permission.RECORD_AUDIO
                        },
                        100);
            }
        }
    }

    private int getPort() {

        try {

            return Integer.parseInt(
                    portEdit.getText()
                            .toString()
                            .trim());

        } catch (Exception e) {

            return DEFAULT_PORT;
        }
    }

    private void status(String text) {

        runOnUiThread(() ->
                statusText.setText(text));
    }

    /*
     * ==========================================
     * SERVER
     * ==========================================
     */

    private void startListening() {

        if (running)
            return;

        final int port = getPort();

        status("Listening : " + port);

        new Thread(() -> {

            try {

                serverSocket =
                        new ServerSocket(port);

                socket =
                        serverSocket.accept();

                socket.setTcpNoDelay(true);

                serverSocket.close();

                serverSocket = null;

                startAudio();

            } catch (Exception e) {

                status(
                        "Listen error: " +
                        e.getMessage());

                endCall();
            }

        }, "ServerThread").start();
    }

    /*
     * ==========================================
     * CLIENT
     * ==========================================
     */

    private void connect() {

        if (running)
            return;

        final String ip =
                ipEdit.getText()
                        .toString()
                        .trim();

        final int port = getPort();

        if (ip.length() == 0) {

            status("IP kosong");
            return;
        }

        status("Connecting...");

        new Thread(() -> {

            try {

                socket =
                        new Socket(ip, port);

                socket.setTcpNoDelay(true);

                startAudio();

            } catch (Exception e) {

                status(
                        "Connect error: " +
                        e.getMessage());

                endCall();
            }

        }, "ConnectThread").start();
    }

    /*
     * ==========================================
     * AUDIO
     * ==========================================
     */

    private void startAudio()
            throws Exception {

        int recordBuffer =
                AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        CHANNEL_IN,
                        AUDIO_FORMAT);

        int playBuffer =
                AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        CHANNEL_OUT,
                        AUDIO_FORMAT);

        if (recordBuffer <= 0)
            throw new Exception(
                    "AudioRecord unsupported");

        if (playBuffer <= 0)
            throw new Exception(
                    "AudioTrack unsupported");

        int bufferSize =
                Math.max(
                        Math.max(
                                recordBuffer,
                                playBuffer),
                        16384);

        recorder =
                new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,

                        SAMPLE_RATE,

                        CHANNEL_IN,

                        AUDIO_FORMAT,

                        bufferSize);

        player =
                new AudioTrack(
                        AudioManager.STREAM_VOICE_CALL,

                        SAMPLE_RATE,

                        CHANNEL_OUT,

                        AUDIO_FORMAT,

                        bufferSize,

                        AudioTrack.MODE_STREAM);

        if (recorder.getState() !=
                AudioRecord.STATE_INITIALIZED) {

            throw new Exception(
                    "AudioRecord init failed");
        }

        if (player.getState() !=
                AudioTrack.STATE_INITIALIZED) {

            throw new Exception(
                    "AudioTrack init failed");
        }

        /*
         * Echo cancellation
         */

        if (AcousticEchoCanceler.isAvailable()) {

            echoCanceler =
                    AcousticEchoCanceler.create(
                            recorder.getAudioSessionId());

            if (echoCanceler != null) {

                echoCanceler.setEnabled(true);
            }
        }

        /*
         * Noise suppression
         */

        if (NoiseSuppressor.isAvailable()) {

            noiseSuppressor =
                    NoiseSuppressor.create(
                            recorder.getAudioSessionId());

            if (noiseSuppressor != null) {

                noiseSuppressor.setEnabled(true);
            }
        }

        running = true;

        runOnUiThread(() -> {

            statusText.setText(
                    "CONNECTED");

            listenButton.setEnabled(false);
            connectButton.setEnabled(false);
            endButton.setEnabled(true);
        });

        player.play();
        recorder.startRecording();

        captureThread =
                new Thread(
                        this::captureLoop,
                        "CaptureThread");

        playbackThread =
                new Thread(
                        this::playbackLoop,
                        "PlaybackThread");

        captureThread.start();
        playbackThread.start();
    }

    /*
     * ==========================================
     * MICROPHONE -> TCP
     * ==========================================
     */

    private void captureLoop() {

        try {

            OutputStream output =
                    socket.getOutputStream();

            byte[] buffer =
                    new byte[4096];

            while (running) {

                int count =
                        recorder.read(
                                buffer,
                                0,
                                buffer.length);

                if (count > 0) {

                    output.write(
                            buffer,
                            0,
                            count);

                    output.flush();
                }
            }

        } catch (Exception e) {

            if (running) {

                status(
                        "Send error: " +
                        e.getMessage());
            }

            endCall();
        }
    }

    /*
     * ==========================================
     * TCP -> SPEAKER
     * ==========================================
     */

    private void playbackLoop() {

        try {

            InputStream input =
                    socket.getInputStream();

            byte[] buffer =
                    new byte[4096];

            while (running) {

                int count =
                        input.read(
                                buffer,
                                0,
                                buffer.length);

                if (count < 0)
                    break;

                if (count > 0) {

                    player.write(
                            buffer,
                            0,
                            count);
                }
            }

        } catch (Exception e) {

            if (running) {

                status(
                        "Receive error: " +
                        e.getMessage());
            }
        }

        endCall();
    }

    /*
     * ==========================================
     * END
     * ==========================================
     */

    private synchronized void endCall() {

        running = false;

        try {

            if (recorder != null) {

                try {
                    recorder.stop();
                } catch (Exception ignored) {}

                recorder.release();

                recorder = null;
            }

        } catch (Exception ignored) {}

        try {

            if (player != null) {

                try {
                    player.stop();
                } catch (Exception ignored) {}

                player.release();

                player = null;
            }

        } catch (Exception ignored) {}

        try {

            if (echoCanceler != null) {

                echoCanceler.release();

                echoCanceler = null;
            }

        } catch (Exception ignored) {}

        try {

            if (noiseSuppressor != null) {

                noiseSuppressor.release();

                noiseSuppressor = null;
            }

        } catch (Exception ignored) {}

        try {

            if (socket != null) {

                socket.close();

                socket = null;
            }

        } catch (Exception ignored) {}

        try {

            if (serverSocket != null) {

                serverSocket.close();

                serverSocket = null;
            }

        } catch (Exception ignored) {}

        runOnUiThread(() -> {

            statusText.setText(
                    "Disconnected");

            listenButton.setEnabled(true);
            connectButton.setEnabled(true);
            endButton.setEnabled(false);
        });
    }

    @Override
    protected void onDestroy() {

        endCall();

        super.onDestroy();
    }
}
