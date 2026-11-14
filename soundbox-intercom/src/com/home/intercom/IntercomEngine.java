package com.home.intercom;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;

/**
 * Live loop: phone microphone -> (gain) -> media audio output.
 * While a Bluetooth speaker is connected, Android routes media output to it.
 */
class IntercomEngine {

    interface ErrorListener { void onError(String message); }

    private static final int RATE = 44100;

    private final AudioManager audioManager;
    private final boolean chime;
    private final ErrorListener errorListener;
    private volatile float gain;
    private volatile boolean running;
    private Thread thread;

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int change) { }
            };

    IntercomEngine(AudioManager am, float gain, boolean chime, ErrorListener l) {
        this.audioManager = am;
        this.gain = gain;
        this.chime = chime;
        this.errorListener = l;
    }

    void setGain(float g) { gain = g; }

    boolean isRunning() { return running; }

    void start() {
        running = true;
        // Pause any music on the phone so only the voice goes to the speaker.
        audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "intercom-audio");
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(1500); } catch (InterruptedException ignored) { }
            thread = null;
        }
        audioManager.abandonAudioFocus(focusListener);
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord rec = null;
        AudioTrack track = null;
        NoiseSuppressor ns = null;
        try {
            int recMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int playMin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(recMin * 2, 8192));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                fail("Could not open the microphone (is another app using it?)");
                return;
            }
            if (NoiseSuppressor.isAvailable()) {
                try {
                    ns = NoiseSuppressor.create(rec.getAudioSessionId());
                    if (ns != null) ns.setEnabled(true);
                } catch (Exception ignored) { }
            }
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
                    .setBufferSizeInBytes(Math.max(playMin * 2, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();

            if (chime) {
                short[] c = Chime.dingDong(RATE);
                track.write(c, 0, c.length);
            }

            rec.startRecording();
            short[] buf = new short[1024];
            // Drop mic audio captured while the chime was playing.
            if (chime) {
                long until = System.currentTimeMillis() + 50;
                while (running && System.currentTimeMillis() < until) rec.read(buf, 0, buf.length);
            }
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
                }
                track.write(buf, 0, n);
            }
            // Let the tail of the sentence finish playing.
            short[] silence = new short[RATE / 4];
            track.write(silence, 0, silence.length);
            track.stop();
        } catch (SecurityException e) {
            fail("Microphone permission is missing");
        } catch (Exception e) {
            fail("Audio error: " + e.getMessage());
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
            }
            if (ns != null) ns.release();
            if (track != null) track.release();
        }
    }

    private void fail(String msg) {
        running = false;
        if (errorListener != null) errorListener.onError(msg);
    }
}
