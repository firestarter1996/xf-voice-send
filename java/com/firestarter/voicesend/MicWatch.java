package com.firestarter.voicesend;

import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.SystemClock;

import java.util.List;

/**
 * Remembers when any app (Gboard voice typing) last had the microphone open.
 * Event driven: Android calls onRecordingConfigChanged only when a recording starts or stops.
 */
public final class MicWatch extends AudioManager.AudioRecordingCallback {
    private static volatile boolean on;
    private static volatile long lastOn;
    private static MicWatch instance;
    private static AudioManager audio;

    @Override
    public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
        boolean now = configs != null && !configs.isEmpty();
        if (on || now) {
            lastOn = SystemClock.uptimeMillis();
        }
        on = now;
    }

    public static synchronized void start(AudioManager am) {
        if (instance != null) {
            return;
        }
        audio = am;
        instance = new MicWatch();
        List<AudioRecordingConfiguration> l = am.getActiveRecordingConfigurations();
        on = l != null && !l.isEmpty();
        if (on) {
            lastOn = SystemClock.uptimeMillis();
        }
        am.registerAudioRecordingCallback(instance, null);
    }

    public static synchronized void stop() {
        if (instance != null && audio != null) {
            audio.unregisterAudioRecordingCallback(instance);
        }
        instance = null;
        audio = null;
    }

    /** True while the microphone is open, or if it closed less than graceMs ago. */
    public static boolean recent(long graceMs) {
        return on || SystemClock.uptimeMillis() - lastOn < graceMs;
    }
}
