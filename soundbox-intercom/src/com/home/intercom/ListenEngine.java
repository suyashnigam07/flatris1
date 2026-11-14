package com.home.intercom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Talk-back: opens the soundbox's own microphone (Bluetooth hands-free / SCO link,
 * the same one it uses for phone calls) and plays it on the phone's loudspeaker.
 */
class ListenEngine {

    interface Listener {
        void onConnected();
        void onLevel(int percent);
        void onError(String message);
    }

    private static final int RATE = 16000;
    static final int TYPE_BLE_HEADSET = 22; // AudioDeviceInfo.TYPE_BLE_HEADSET (API 31)

    private final Context context;
    private final AudioManager am;
    private final Listener listener;
    private volatile float gain;
    private volatile boolean running;
    private Thread thread;
    private boolean usedLegacySco;
    private int savedMode;

    ListenEngine(Context ctx, AudioManager am, float gain, Listener l) {
        this.context = ctx.getApplicationContext();
        this.am = am;
        this.gain = gain;
        this.listener = l;
    }

    /** The soundbox microphone as an input device, or null if the speaker has none / it's not connected. */
    static AudioDeviceInfo findSoundboxMic(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.getType() == TYPE_BLE_HEADSET) return d;
        }
        return null;
    }

    void setGain(float g) { gain = g; }

    boolean isRunning() { return running; }

    void start() {
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "intercom-listen");
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(2000); } catch (InterruptedException ignored) { }
            thread = null;
        }
    }

    // ------------------------------------------------------------ routing

    private boolean openSoundboxMic() throws InterruptedException {
        savedMode = am.getMode();
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Method avail = AudioManager.class.getMethod("getAvailableCommunicationDevices");
                Method set = AudioManager.class.getMethod("setCommunicationDevice", AudioDeviceInfo.class);
                Method get = AudioManager.class.getMethod("getCommunicationDevice");
                @SuppressWarnings("unchecked")
                List<AudioDeviceInfo> devices = (List<AudioDeviceInfo>) avail.invoke(am);
                AudioDeviceInfo target = null;
                for (AudioDeviceInfo d : devices) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.getType() == TYPE_BLE_HEADSET) {
                        target = d;
                        break;
                    }
                }
                if (target == null) return false;
                if (!(Boolean) set.invoke(am, target)) return false;
                // Wait (max 4 s) for the Bluetooth voice link to come up.
                long until = System.currentTimeMillis() + 4000;
                while (running && System.currentTimeMillis() < until) {
                    AudioDeviceInfo cur = (AudioDeviceInfo) get.invoke(am);
                    if (cur != null && cur.getType() == target.getType()) {
                        Thread.sleep(400);
                        return true;
                    }
                    Thread.sleep(100);
                }
                return false;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                // fall through to the legacy path
            }
        }
        usedLegacySco = true;
        final CountDownLatch up = new CountDownLatch(1);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    up.countDown();
                }
            }
        };
        context.registerReceiver(r, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
        try {
            am.startBluetoothSco();
            am.setBluetoothScoOn(true);
            boolean ok = up.await(5, TimeUnit.SECONDS);
            if (ok) Thread.sleep(300);
            return ok;
        } finally {
            context.unregisterReceiver(r);
        }
    }

    private void closeSoundboxMic() {
        try {
            if (usedLegacySco) {
                am.setBluetoothScoOn(false);
                am.stopBluetoothSco();
            } else if (Build.VERSION.SDK_INT >= 31) {
                AudioManager.class.getMethod("clearCommunicationDevice").invoke(am);
            }
        } catch (Exception ignored) { }
        am.setMode(savedMode == AudioManager.MODE_IN_COMMUNICATION ? AudioManager.MODE_NORMAL : savedMode);
    }

    private AudioDeviceInfo phoneSpeaker() {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return d;
        }
        return null;
    }

    // ------------------------------------------------------------ audio loop

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord rec = null;
        AudioTrack track = null;
        boolean opened = false;
        try {
            if (findSoundboxMic(am) == null) {
                fail("Your soundbox has no microphone, or it is not connected for calls.");
                return;
            }
            opened = true;
            if (!openSoundboxMic()) {
                if (running) fail("Could not open the soundbox microphone. Try again, or turn the soundbox off and on.");
                return;
            }
            int recMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(recMin * 2, 4096));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                fail("Could not open the microphone (is another app using it?)");
                return;
            }
            AudioDeviceInfo mic = findSoundboxMic(am);
            if (mic != null) rec.setPreferredDevice(mic);

            int playMin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(playMin * 2, 4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            // Mom's voice must come out of the phone, not go back down to the soundbox.
            AudioDeviceInfo spk = phoneSpeaker();
            if (spk != null) track.setPreferredDevice(spk);

            rec.startRecording();
            track.play();
            if (listener != null) listener.onConnected();

            short[] buf = new short[512];
            long lastLevel = 0;
            int peak = 0;
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) {
                    if (n < 0) { fail("Microphone error (" + n + ")"); break; }
                    continue;
                }
                float g = gain;
                for (int i = 0; i < n; i++) {
                    int s = Math.round(buf[i] * g);
                    if (s > 32767) s = 32767; else if (s < -32768) s = -32768;
                    buf[i] = (short) s;
                    int a = s < 0 ? -s : s;
                    if (a > peak) peak = a;
                }
                track.write(buf, 0, n);
                long now = System.currentTimeMillis();
                if (now - lastLevel > 100) {
                    if (listener != null) listener.onLevel(Math.min(100, peak * 100 / 20000));
                    peak = 0;
                    lastLevel = now;
                }
            }
        } catch (SecurityException e) {
            fail("Microphone permission is missing");
        } catch (InterruptedException e) {
            // stopping
        } catch (Exception e) {
            fail("Audio error: " + e.getMessage());
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
            }
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) { }
                track.release();
            }
            if (opened) closeSoundboxMic();
            if (listener != null) listener.onLevel(0);
        }
    }

    private void fail(String msg) {
        running = false;
        if (listener != null) listener.onError(msg);
    }
}
