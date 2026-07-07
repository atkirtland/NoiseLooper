package ie.delilahsthings.soothingloop;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.SoundPool;
import android.widget.SeekBar;

import java.util.HashMap;
import java.util.Map;

public class SoundEffectVolumeManager implements SeekBar.OnSeekBarChangeListener {

    final static int MAX_STREAMS=32;
    private int playbackId=0;
    private float volumeF, fadeStart, pausedVolumeF;
    private int soundPoolIndex;

    private static Runnable onPlayCallback;
    private static FadeOutThread fadeOutThread;
    private static SoundPool soundPool=new SoundPool(SoundEffectVolumeManager.MAX_STREAMS, AudioManager.STREAM_MUSIC,0);
    private static HashMap<String,SoundEffectVolumeManager> cache = new HashMap<>();
    public static boolean EVER_PLAYED=false;

    private SoundEffectVolumeManager(Context context, int soundId) {
        this.soundPoolIndex=soundPool.load(context, soundId, 1);
    }

    private SoundEffectVolumeManager(String sound) {
        this.soundPoolIndex=soundPool.load(sound, 1);
    }

    public static SoundEffectVolumeManager get(Context context, String persistKey, int soundId) {
        if(cache.containsKey(persistKey)) {
            return cache.get(persistKey);
        }
        else {
            SoundEffectVolumeManager manager = new SoundEffectVolumeManager(context, soundId);
            cache.put(persistKey,manager);
            return manager;
        }
    }
    public static SoundEffectVolumeManager get(String sound){
        if(cache.containsKey(sound)) {
            return cache.get(sound);
        }
        else {
            SoundEffectVolumeManager manager = new SoundEffectVolumeManager(sound);
            cache.put(sound,manager);
            return manager;
        }
    }

    private static SoundEffectVolumeManager lookup(String persistKey) {
        if(persistKey==null) {
            return null;
        }
        if(cache.containsKey(persistKey)) {
            return cache.get(persistKey);
        }
        if(persistKey.startsWith(Constants.CUSTOM_NOISE_PREFIX)) {
            String path = CustomSoundsManager.getSoundPath()+persistKey.substring(Constants.CUSTOM_NOISE_PREFIX.length());
            return cache.get(path);
        }
        return null;
    }

    public static void unload(String sound) {
        SoundEffectVolumeManager manager = cache.get(sound);
        soundPool.stop(manager.playbackId);
        soundPool.unload(manager.soundPoolIndex);
        cache.remove(sound);
        PlaybackService.sync(StaticContext.getAppContext());
    }

    public static void stopAll()
    {
        abortFadeout();

        for(SoundEffectVolumeManager manager: cache.values())
        {
            if(manager.playbackId!=0)
            {
                soundPool.stop(manager.playbackId);
                manager.playbackId=0;
            }
            manager.pausedVolumeF=0;
        }

        PlaybackService.sync(StaticContext.getAppContext());
    }

    /** Stops every currently-playing sound but remembers its volume so {@link #resumeAll()} can restore it. */
    public static void pauseAll()
    {
        abortFadeout();

        for(SoundEffectVolumeManager manager: cache.values())
        {
            if(manager.playbackId!=0)
            {
                manager.pausedVolumeF=manager.volumeF;
                soundPool.stop(manager.playbackId);
                manager.playbackId=0;
            }
        }

        PlaybackService.sync(StaticContext.getAppContext());
    }

    /** Replays every sound that was stopped by {@link #pauseAll()} at its previous volume. */
    public static void resumeAll()
    {
        for(SoundEffectVolumeManager manager: cache.values())
        {
            if(manager.playbackId==0 && manager.pausedVolumeF>0)
            {
                float volume=manager.pausedVolumeF;
                manager.pausedVolumeF=0;
                manager.startPlayback(volume);
            }
        }

        PlaybackService.sync(StaticContext.getAppContext());
    }

    public static boolean isAnythingPlaying()
    {
        for(SoundEffectVolumeManager manager: cache.values())
        {
            if(manager.playbackId!=0)
                return true;
        }
        return false;
    }

    public static boolean isAnythingPaused()
    {
        for(SoundEffectVolumeManager manager: cache.values())
        {
            if(manager.playbackId==0 && manager.pausedVolumeF>0)
                return true;
        }
        return false;
    }

    /** The volume percentage (0-100) a SeekBar bound to this persistKey should currently display. */
    public static int getVolumePercent(String persistKey)
    {
        SoundEffectVolumeManager manager = lookup(persistKey);
        if(manager==null || manager.playbackId==0)
            return 0;
        return Math.round(manager.volumeF*100);
    }

    public static void abortFadeout() {
        if(fadeOutThread!=null && fadeOutThread.isAlive()) {
            fadeOutThread.interrupt();
        }
    }

    public static void fadeOut(Context context, long smearLength)
    {
        if(fadeOutThread==null || !fadeOutThread.isAlive()) {
            fadeOutThread = new FadeOutThread(context, smearLength);
            fadeOutThread.start();
        }
    }

    private boolean startPlayback(float atVolume) {
        for(int i=0; i<10; i++) {
            playbackId = soundPool.play(soundPoolIndex, atVolume, atVolume, 1, -1, 1f);
            if(playbackId!=0) {
                volumeF=atVolume;
                onPlayCallback.run();
                EVER_PLAYED=true;
                return true;
            }
            Util.sleep(500);
        }
        return false;
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int volume, boolean z) {
        abortFadeout();

        volumeF=volume/100f;
        if(playbackId==0) {
            if (volume != 0) {
                if(startPlayback(volumeF)) {
                    PlaybackService.sync(StaticContext.getAppContext());
                }
                else {
                    seekBar.setProgress(0);
                }
            }
        }
        else if(volume==0) {
            soundPool.stop(playbackId);
            playbackId=0;
            PlaybackService.sync(StaticContext.getAppContext());
        }
        else {
            soundPool.setVolume(playbackId, volumeF, volumeF);
        }
    }

    public static void setOnPlayCallback(Runnable onPlayCallback)
    {
        SoundEffectVolumeManager.onPlayCallback=onPlayCallback;
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {

    }

    static class FadeOutThread extends Thread{
        private Context context;
        private long smearLength;

        Intent afterFadeout;
        public FadeOutThread(Context context, long smearLength)
        {
            this.context=context;
            this.smearLength=smearLength;
        }
        @Override
        public void run()
        {
            afterFadeout = new Intent(Constants.FADEOUT_ACTION);
            afterFadeout.setPackage(context.getPackageName());

            long finishAt = System.currentTimeMillis()+smearLength;
            float timeRemaining;

            HashMap<String, Float> startVolumes = new HashMap<>();
            for (SoundEffectVolumeManager manager : cache.values()) {
               manager.fadeStart=manager.volumeF;
            }

            try {
                while (System.currentTimeMillis() < finishAt) {
                    timeRemaining = finishAt - System.currentTimeMillis();
                    for (SoundEffectVolumeManager manager : cache.values()) {
                        if (manager.playbackId != 0) {
                            manager.volumeF = manager.fadeStart * (timeRemaining / smearLength);
                            soundPool.setVolume(manager.playbackId, manager.volumeF, manager.volumeF);
                        }
                    }
                    Thread.sleep(50);
                }
            }
            catch (InterruptedException ex) {
                afterFadeout.putExtra(Constants.FADEOUT_INTERRUPTED, true);
                context.sendBroadcast(afterFadeout);
                return;
            }

            for(SoundEffectVolumeManager manager: cache.values())
            {
                if(manager.playbackId!=0) {
                    manager.pausedVolumeF=manager.fadeStart;
                    soundPool.stop(manager.playbackId);
                    manager.playbackId = 0;
                }
            }

            PlaybackService.sync(context);
            context.sendBroadcast(afterFadeout);
        }
    }
}
