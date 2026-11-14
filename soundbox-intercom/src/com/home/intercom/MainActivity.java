package com.home.intercom;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * One-screen walkie-talkie: speak into the phone, hear it on the paired
 * Bluetooth soundbox. The phone pairs/connects to the speaker through the
 * normal Android Bluetooth settings; this app routes the mic to it live.
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 1;
    private static final String PERM_MIC = "android.permission.RECORD_AUDIO";

    private AudioManager audioManager;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private IntercomEngine engine;

    private TextView statusText;
    private View statusDot;
    private Button talkButton;
    private TextView talkHint;
    private CheckBox toggleMode;
    private CheckBox chimeBox;
    private TextView boostLabel;
    private SeekBar boostBar;

    private ListenEngine listenEngine;
    private Button listenButton;
    private ProgressBar levelBar;
    private TextView listenHint;
    private CheckBox autoListenBox;
    private boolean listenConnected;
    private long listenDeadline;      // 0 = no time limit
    private static final int AUTO_LISTEN_SECONDS = 20;
    private static final int MANUAL_LISTEN_SECONDS = 5 * 60;
    private final Runnable listenTicker = new Runnable() {
        @Override public void run() {
            if (listenEngine == null) return;
            if (listenDeadline > 0 && System.currentTimeMillis() >= listenDeadline) {
                stopListening();
                return;
            }
            updateListenUi();
            ui.postDelayed(this, 500);
        }
    };

    private String speakerName; // null when no Bluetooth speaker is connected
    private boolean soundboxHasMic;
    private boolean pendingStartAfterPermission;

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) { refreshSpeakerStatus(); }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            refreshSpeakerStatus();
            // Speaker dropped mid-talk: stop, otherwise sound would come out of
            // the phone's own speaker and could howl/feed back.
            if (speakerName == null && engine != null && engine.isRunning()) {
                stopTalking();
                toast("Soundbox disconnected");
            }
            if (!soundboxHasMic && listenEngine != null && listenConnected) {
                stopListening();
                toast("Soundbox disconnected");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        setContentView(buildUi());
        audioManager.registerAudioDeviceCallback(deviceCallback, ui);
        refreshSpeakerStatus();
    }

    @Override
    protected void onDestroy() {
        stopTalking();
        stopListening();
        audioManager.unregisterAudioDeviceCallback(deviceCallback);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSpeakerStatus();
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(Color.parseColor("#F4F6F9"));

        TextView title = new TextView(this);
        title.setText("Soundbox Intercom");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1A237E"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Speak on your phone, it plays on the Bluetooth speaker downstairs. Tap Listen to hear the reply.");
        sub.setTextSize(14);
        sub.setTextColor(Color.parseColor("#555555"));
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        // --- Status card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(Color.WHITE, dp(14)));
        statusDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotLp.rightMargin = dp(12);
        card.addView(statusDot, dotLp);
        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTextColor(Color.parseColor("#222222"));
        card.addView(statusText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(card);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        Button btSettings = smallButton("Bluetooth settings");
        btSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openBluetoothSettings(); }
        });
        Button volume = smallButton("Volume");
        volume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
            }
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(48), 1);
        l1.rightMargin = dp(8);
        row.addView(btSettings, l1);
        row.addView(volume, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(row);

        // --- Big talk button
        talkButton = new Button(this);
        talkButton.setTextSize(24);
        talkButton.setTypeface(Typeface.DEFAULT_BOLD);
        talkButton.setTextColor(Color.WHITE);
        talkButton.setAllCaps(false);
        LinearLayout.LayoutParams talkLp = new LinearLayout.LayoutParams(dp(200), dp(200));
        talkLp.gravity = Gravity.CENTER_HORIZONTAL;
        talkLp.topMargin = dp(20);
        root.addView(talkButton, talkLp);
        talkButton.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return onTalkTouch(v, e); }
        });

        talkHint = new TextView(this);
        talkHint.setGravity(Gravity.CENTER);
        talkHint.setTextSize(15);
        talkHint.setTextColor(Color.parseColor("#555555"));
        talkHint.setPadding(0, dp(8), 0, dp(14));
        root.addView(talkHint);

        // --- Listen (talk-back) button
        listenButton = new Button(this);
        listenButton.setTextSize(20);
        listenButton.setTypeface(Typeface.DEFAULT_BOLD);
        listenButton.setTextColor(Color.WHITE);
        listenButton.setAllCaps(false);
        root.addView(listenButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        listenButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (listenEngine != null) stopListening(); else startListening(false);
            }
        });
        levelBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        levelBar.setMax(100);
        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        lvLp.topMargin = dp(8);
        root.addView(levelBar, lvLp);
        listenHint = new TextView(this);
        listenHint.setGravity(Gravity.CENTER);
        listenHint.setTextSize(14);
        listenHint.setTextColor(Color.parseColor("#555555"));
        listenHint.setPadding(0, dp(6), 0, dp(16));
        root.addView(listenHint);

        // --- Options
        toggleMode = new CheckBox(this);
        toggleMode.setText("Tap to start / tap again to stop (instead of hold)");
        toggleMode.setTextSize(15);
        toggleMode.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                stopTalking();
            }
        });
        root.addView(toggleMode);

        chimeBox = new CheckBox(this);
        chimeBox.setText("Play a \"ding-dong\" before I speak");
        chimeBox.setTextSize(15);
        chimeBox.setChecked(true);
        root.addView(chimeBox);

        autoListenBox = new CheckBox(this);
        autoListenBox.setText("After I finish talking, listen for her reply (" + AUTO_LISTEN_SECONDS + " s)");
        autoListenBox.setTextSize(15);
        autoListenBox.setChecked(true);
        root.addView(autoListenBox);

        boostLabel = new TextView(this);
        boostLabel.setTextSize(15);
        boostLabel.setTextColor(Color.parseColor("#222222"));
        boostLabel.setPadding(dp(4), dp(14), 0, 0);
        root.addView(boostLabel);
        boostBar = new SeekBar(this);
        boostBar.setMax(6); // 1.0x .. 4.0x in 0.5 steps
        boostBar.setProgress(2);
        boostBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                updateBoostLabel();
                if (engine != null) engine.setGain(currentGain());
                if (listenEngine != null) listenEngine.setGain(currentGain());
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        root.addView(boostBar);
        updateBoostLabel();

        TextView help = new TextView(this);
        help.setText("First time: switch on the soundbox, tap \"Bluetooth settings\" and pair/connect it. "
                + "After that just open this app and talk. Keep the soundbox volume high.\n\n"
                + "Listen uses the soundbox's own microphone (the one for hands-free calls). "
                + "It only works if your soundbox has a mic. Your mom should speak close to the soundbox.");
        help.setTextSize(13);
        help.setTextColor(Color.parseColor("#777777"));
        help.setPadding(dp(4), dp(18), dp(4), 0);
        root.addView(help);

        updateTalkUi();
        updateListenUi();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor("#F4F6F9"));
        scroll.addView(root);
        return scroll;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.parseColor("#1A237E"));
        b.setBackground(rounded(Color.parseColor("#DDE3F0"), dp(10)));
        return b;
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private float currentGain() {
        return 1f + boostBar.getProgress() * 0.5f;
    }

    private void updateBoostLabel() {
        boostLabel.setText("Volume boost: " + currentGain() + "x");
    }

    private void updateTalkUi() {
        boolean talking = engine != null && engine.isRunning();
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.parseColor(talking ? "#E53935" : (speakerName != null ? "#1E88E5" : "#9E9E9E")));
        talkButton.setBackground(circle);
        if (talking) {
            talkButton.setText("ON AIR\n🎙");
            talkHint.setText(toggleMode.isChecked() ? "Speaking… tap again to stop" : "Speaking… release to stop");
        } else {
            talkButton.setText(toggleMode.isChecked() ? "TAP TO\nTALK" : "HOLD TO\nTALK");
            talkHint.setText(speakerName != null
                    ? (toggleMode.isChecked() ? "Tap the button and speak" : "Hold the button while you speak")
                    : "Connect the soundbox first");
        }
    }

    // ---------------------------------------------------------------- Speaker status

    private void refreshSpeakerStatus() {
        speakerName = null;
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            // 8 = A2DP, 26 = BLE speaker, 27 = BLE broadcast (constants newer than our compile SDK)
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == 26 || t == 27) {
                CharSequence n = d.getProductName();
                speakerName = (n == null || n.length() == 0) ? "Bluetooth speaker" : n.toString();
                break;
            }
        }
        soundboxHasMic = ListenEngine.findSoundboxMic(audioManager) != null;
        if (statusText == null) return;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Color.parseColor(speakerName != null ? "#43A047" : "#E53935"));
        statusDot.setBackground(dot);
        statusText.setText(speakerName != null
                ? "Connected to: " + speakerName
                        + (soundboxHasMic ? "\nSoundbox mic: available" : "\nSoundbox mic: not found")
                : (soundboxHasMic && listenEngine != null ? "Listening to the soundbox mic"
                        : "Soundbox not connected.\nTap \"Bluetooth settings\" to connect."));
        updateTalkUi();
        updateListenUi();
    }

    private void openBluetoothSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ---------------------------------------------------------------- Talking

    private boolean onTalkTouch(View v, MotionEvent e) {
        int a = e.getActionMasked();
        if (toggleMode.isChecked()) {
            if (a == MotionEvent.ACTION_UP) {
                if (engine != null && engine.isRunning()) finishTalking(); else startTalking();
            }
        } else {
            if (a == MotionEvent.ACTION_DOWN) startTalking();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                pendingStartAfterPermission = false;
                finishTalking();
            }
        }
        return true;
    }

    private void startTalking() {
        if (engine != null && engine.isRunning()) return;
        stopListening();
        if (checkSelfPermission(PERM_MIC) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = toggleMode.isChecked();
            requestPermissions(new String[]{PERM_MIC}, REQ_MIC);
            return;
        }
        refreshSpeakerStatus();
        if (speakerName == null) {
            toast("Soundbox is not connected. Connect it in Bluetooth settings first.");
            return;
        }
        engine = new IntercomEngine(audioManager, currentGain(), chimeBox.isChecked(),
                new IntercomEngine.ErrorListener() {
                    @Override public void onError(final String err) {
                        ui.post(new Runnable() {
                            @Override public void run() { toast(err); stopTalking(); }
                        });
                    }
                });
        engine.start();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        talkButton.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        updateTalkUi();
    }

    private void stopTalking() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (talkButton != null) updateTalkUi();
    }

    /** User let go of / tapped off the talk button: stop and optionally wait for a reply. */
    private void finishTalking() {
        boolean wasTalking = engine != null && engine.isRunning();
        stopTalking();
        if (wasTalking && autoListenBox.isChecked() && soundboxHasMic) startListening(true);
    }

    // ---------------------------------------------------------------- Listening (talk-back)

    private void startListening(boolean auto) {
        if (listenEngine != null) return;
        if (checkSelfPermission(PERM_MIC) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{PERM_MIC}, REQ_MIC);
            return;
        }
        stopTalking();
        refreshSpeakerStatus();
        if (!soundboxHasMic) {
            toast("No soundbox microphone found. Your soundbox needs a built-in mic (\"hands-free calling\"), "
                    + "and in Bluetooth settings its \"Calls / Phone audio\" option must be on.");
            return;
        }
        listenConnected = false;
        listenDeadline = System.currentTimeMillis() + 1000L * (auto ? AUTO_LISTEN_SECONDS : MANUAL_LISTEN_SECONDS);
        listenEngine = new ListenEngine(this, audioManager, currentGain(), new ListenEngine.Listener() {
            @Override public void onConnected() {
                ui.post(new Runnable() {
                    @Override public void run() { listenConnected = true; updateListenUi(); }
                });
            }
            @Override public void onLevel(final int percent) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        levelBar.setProgress(percent);
                        // She is speaking: keep the auto-listen window open a bit longer.
                        if (percent > 25 && listenEngine != null) {
                            listenDeadline = Math.max(listenDeadline, System.currentTimeMillis() + 6000);
                        }
                    }
                });
            }
            @Override public void onError(final String err) {
                ui.post(new Runnable() {
                    @Override public void run() { toast(err); stopListening(); }
                });
            }
        });
        listenEngine.start();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacks(listenTicker);
        ui.post(listenTicker);
        updateListenUi();
    }

    private void stopListening() {
        ui.removeCallbacks(listenTicker);
        if (listenEngine != null) {
            listenEngine.stop();
            listenEngine = null;
        }
        listenConnected = false;
        if (levelBar != null) levelBar.setProgress(0);
        if (engine == null) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (listenButton != null) updateListenUi();
    }

    private void updateListenUi() {
        if (listenButton == null) return;
        boolean on = listenEngine != null;
        listenButton.setBackground(rounded(Color.parseColor(on ? "#E65100" : (soundboxHasMic ? "#2E7D32" : "#9E9E9E")), dp(16)));
        if (!on) {
            listenButton.setText("👂  LISTEN TO MOM");
            listenHint.setText(soundboxHasMic ? "Tap to hear the room downstairs through the soundbox mic"
                    : "Needs a soundbox with a built-in microphone");
            return;
        }
        if (!listenConnected) {
            listenButton.setText("Connecting to soundbox mic…");
            listenHint.setText("This takes 1–3 seconds");
            return;
        }
        long left = Math.max(0, (listenDeadline - System.currentTimeMillis() + 999) / 1000);
        listenButton.setText("👂  LISTENING…  tap to stop");
        listenHint.setText(left <= AUTO_LISTEN_SECONDS + 6 ? "Waiting for reply: " + left + " s" : "Listening (stops automatically after 5 minutes)");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingStartAfterPermission) startTalking();
            else toast("Microphone allowed. Now hold the button and speak.");
        } else {
            toast("The app needs microphone permission to send your voice.");
        }
        pendingStartAfterPermission = false;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
