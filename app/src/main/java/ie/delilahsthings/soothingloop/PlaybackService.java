package ie.delilahsthings.soothingloop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class PlaybackService extends Service {

    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_TOGGLE_PLAY_PAUSE = "ie.delilahsthings.soothingloop.action.TOGGLE_PLAY_PAUSE";
    private static final String ACTION_STOP = "ie.delilahsthings.soothingloop.action.STOP";

    private MediaSessionCompat mediaSession;

    /**
     * Starts, updates, or stops the playback service to match the current state of
     * {@link SoundEffectVolumeManager}. This is the single entry point the audio layer
     * uses to keep the foreground service/notification/MediaSession in sync, and is safe
     * to call after any playback state change, whether or not the service is running yet.
     */
    public static void sync(Context context) {
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, PlaybackService.class);

        if (SoundEffectVolumeManager.isAnythingPlaying() || SoundEffectVolumeManager.isAnythingPaused()) {
            ContextCompat.startForegroundService(appContext, intent);
        } else {
            appContext.stopService(intent);
        }

        Intent broadcast = new Intent(Constants.PLAYBACK_STATE_CHANGED);
        broadcast.setPackage(appContext.getPackageName());
        appContext.sendBroadcast(broadcast);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannelIfNeeded();

        mediaSession = new MediaSessionCompat(this, "NoiseLooper");
        mediaSession.setSessionActivity(PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), pendingIntentFlags()));
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                SoundEffectVolumeManager.resumeAll();
            }

            @Override
            public void onPause() {
                SoundEffectVolumeManager.pauseAll();
            }

            @Override
            public void onStop() {
                SoundEffectVolumeManager.stopAll();
            }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_TOGGLE_PLAY_PAUSE.equals(action)) {
            if (SoundEffectVolumeManager.isAnythingPlaying()) {
                SoundEffectVolumeManager.pauseAll();
            } else {
                SoundEffectVolumeManager.resumeAll();
            }
        } else if (ACTION_STOP.equals(action)) {
            SoundEffectVolumeManager.stopAll();
        }

        refreshForegroundState();
        return START_NOT_STICKY;
    }

    private void refreshForegroundState() {
        boolean playing = SoundEffectVolumeManager.isAnythingPlaying();
        boolean paused = SoundEffectVolumeManager.isAnythingPaused();

        if (!playing && !paused) {
            stopForeground(true);
            stopSelf();
            return;
        }

        updateSessionState(playing);
        startForegroundCompat(buildNotification(playing));
    }

    private void updateSessionState(boolean playing) {
        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getString(R.string.app_name))
                .build());

        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
    }

    private Notification buildNotification(boolean playing) {
        PendingIntent playPauseIntent = PendingIntent.getService(this, 0,
                new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE_PLAY_PAUSE),
                pendingIntentFlags());
        PendingIntent stopIntent = PendingIntent.getService(this, 1,
                new Intent(this, PlaybackService.class).setAction(ACTION_STOP),
                pendingIntentFlags());

        NotificationCompat.Action playPauseAction = playing
                ? new NotificationCompat.Action(R.drawable.pause, getString(R.string.notification_action_pause), playPauseIntent)
                : new NotificationCompat.Action(R.drawable.play_triangle, getString(R.string.notification_action_play), playPauseIntent);
        NotificationCompat.Action stopAction = new NotificationCompat.Action(R.drawable.stop_square, getString(R.string.stop), stopIntent);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.play_triangle)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(playing ? R.string.notification_playing : R.string.notification_paused))
                .setContentIntent(mediaSession.getController().getSessionActivity())
                .setOngoing(playing)
                .addAction(playPauseAction)
                .addAction(stopAction)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    @Override
    public void onDestroy() {
        mediaSession.setActive(false);
        mediaSession.release();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
