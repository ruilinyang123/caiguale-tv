package com.fongmi.android.tv.player.exo;

import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.source.preload.PreCacheHelper;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.github.catvod.net.OkHttp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pre-caches the next direct-play episode into the shared EXO disk cache. */
public final class ExoNextEpisodePreloader {

    private PreCacheHelper helper;
    private ExecutorService executor;

    public boolean preload(PlaySpec spec, int kernel) {
        release();
        if (spec == null || spec.getUrl() == null || !PreloadSetting.isPreload(kernel)) return false;
        MediaItem item = ExoUtil.getMediaItem(spec.checkUa(), 1);
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(OkHttp.player());
        http.setDefaultRequestProperties(spec.getHeaders());
        DefaultDataSource.Factory upstream = new DefaultDataSource.Factory(App.get(), http);
        executor = Executors.newFixedThreadPool(PreloadSetting.getPreloadThreads(kernel));
        helper = new PreCacheHelper.Factory(App.get(), MediaSourceFactory.getExoCache(), upstream, Looper.getMainLooper())
                .setDownloadExecutor(executor)
                .create(item);
        helper.preCache(0, PreloadSetting.getPreloadDurationMs(kernel));
        return true;
    }

    public void release() {
        if (helper != null) helper.release(false);
        helper = null;
        if (executor != null) executor.shutdownNow();
        executor = null;
    }
}
