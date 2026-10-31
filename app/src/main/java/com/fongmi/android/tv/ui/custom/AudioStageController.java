package com.fongmi.android.tv.ui.custom;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.Player;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ViewAudioStageBinding;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.karaoke.KaraokeController;
import com.fongmi.android.tv.player.karaoke.KaraokePitchTrackGenerator;
import com.fongmi.android.tv.player.karaoke.KaraokeResult;
import com.fongmi.android.tv.player.karaoke.KaraokeTrackRepository;
import com.fongmi.android.tv.player.lyrics.AudioPlaylistStore;
import com.fongmi.android.tv.player.lyrics.LyricsController;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsRepository;
import com.fongmi.android.tv.player.lyrics.LyricsRequest;
import com.fongmi.android.tv.player.lyrics.LyricsResult;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.LyricsSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.adapter.AudioQueueAdapter;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.crawler.SpiderDebug;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AudioStageController implements LyricsController.Listener {

    private final Host host;
    private final FragmentActivity activity;
    private final ViewAudioStageBinding binding;
    private final LyricsOverlayView overlay;
    private final View stageView;
    private final ViewGroup originalParent;
    private LyricsController lyrics;
    private LyricsResult currentResult;
    private List<LyricsLine> currentLines;
    private AudioQueueAdapter queueAdapter;
    private LinearLayout queueSearchList;
    private TextView queueStatus;
    private LinearLayout lyricsResultList;
    private TextView lyricsStatus;
    private BottomSheetDialog sheet;
    private BottomSheetDialog lyricsSheet;
    private KaraokeController karaoke;
    private boolean karaokeResultShown;
    private BottomSheetDialog karaokePitchSheet;
    private ProgressBar karaokePitchProgress;
    private TextView karaokePitchMessage;
    private boolean stageVisible;
    private boolean seekAttached;
    private int artworkColor = Color.rgb(55, 45, 68);
    private int backgroundNonce;
    private int queueSearchSeq;
    private int lyricsSearchSeq;
    private int lyricsRefreshSeq;

    public AudioStageController(Host host, ViewAudioStageBinding binding, LyricsOverlayView overlay) {
        this.host = host;
        this.activity = host.activity();
        this.binding = binding;
        this.overlay = overlay;
        this.stageView = binding.getRoot();
        this.originalParent = (ViewGroup) stageView.getParent();
        initEvent();
    }

    public interface Host {
        FragmentActivity activity();

        PlayerManager player();

        PlaybackService service();

        History history();

        Site getSite();

        String getSiteKey();

        Flag getFlag();

        Episode getEpisode();

        String getVodName();

        String getVodPic();

        List<Episode> getQueueEpisodes();

        void setQueueEpisodes(List<Episode> items);

        void playEpisode(Episode episode);

        void playNext();

        void playPrev();

        void launchKaraokeTrackFileChooser();

        void onStageVisibilityChanged(boolean visible);
    }

    private PlayerManager player() {
        return host.player();
    }

    private boolean ready() {
        return host.service() != null && player() != null;
    }

    private void initEvent() {
        binding.audioPlay.setOnClickListener(view -> togglePlay());
        binding.audioNext.setOnClickListener(view -> host.playNext());
        binding.audioPrev.setOnClickListener(view -> host.playPrev());
        binding.audioRepeatAction.setOnClickListener(view -> onRepeat());
        binding.audioQueueAction.setOnClickListener(view -> onAudioQueue());
        binding.audioLyricsAction.setOnClickListener(view -> onLyricsSearch());
        binding.audioMoreAction.setOnClickListener(view -> showAudioMoreSheet());
        binding.audioBackgroundAction.setOnClickListener(view -> randomizeAudioBackgroundMix(true));
    }

    public void onPlayingChanged(boolean playing) {
        if (!stageVisible) return;
        checkAudioPlayImg(playing);
        updateLyrics(playing);
        updateKaraoke();
    }

    public void onStateChanged(int state) {
        if (state == Player.STATE_READY) {
            refreshLyricsNow();
        } else if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
            updateLyrics(false);
            if (state == Player.STATE_ENDED) showKaraokeResultIfNeeded(null);
        }
    }

    public void onTimeChanged() {
        if (!stageVisible || !ready() || player().isEmpty()) return;
        lyrics.update(player());
        updateKaraoke();
    }

    public void onTracksChanged() {
        refreshLyricsNow();
    }

    public void toggleImmersiveAudioMode() {
        PlayerSetting.putImmersiveAudioMode(!PlayerSetting.isImmersiveAudioMode());
        updateImmersiveAction();
        refreshLyricsNow();
    }

    public void updateImmersiveAction() {
        View button = activity.findViewById(R.id.immersiveAudio);
        if (button == null) return;
        boolean audioContent = isAudioOnly() || isMusicLike();
        button.setVisibility(audioContent ? View.VISIBLE : View.GONE);
        button.setSelected(PlayerSetting.isImmersiveAudioMode());
    }

    public boolean shouldUseImmersiveAudio() {
        return PlayerSetting.isImmersiveAudioMode() && (isAudioOnly() || isMusicLike());
    }

    public boolean isAudioOnly() {
        return ready() && LyricsController.isAudioOnly(player());
    }

    public boolean isMusicLike() {
        Site site = host.getSite();
        String text = host.getSiteKey() + " " + (site == null ? "" : site.getKey()) + " " + (site == null ? "" : site.getName()) + " " + (host.getFlag() == null ? "" : host.getFlag().getFlag()) + " " + host.getVodName() + " " + (host.getEpisode() == null ? "" : host.getEpisode().getName());
        return LyricsController.isMusicLikeText(text);
    }

    public boolean isStageVisible() {
        return stageVisible;
    }

    public void consumeBack() {
        if (lyricsSheet != null) {
            lyricsSheet.dismiss();
            return;
        }
        if (sheet != null) {
            sheet.dismiss();
            return;
        }
        if (stageVisible) setAudioStageVisible(false);
    }

    public boolean interceptBack() {
        return lyricsSheet != null || sheet != null || stageVisible;
    }

    private void refreshLyricsNow() {
        if (!ready()) return;
        lyricsRefreshSeq++;
        boolean audioContent = shouldUseImmersiveAudio();
        setAudioStageVisible(audioContent);
        ensureLyrics();
        if (lyrics == null) return;
        if (!audioContent) {
            lyrics.refresh(player(), false);
            refreshKaraoke(false);
            return;
        }
        lyrics.refresh(player(), true);
        refreshKaraoke(true);
    }

    private void ensureLyrics() {
        if (lyrics != null) return;
        lyrics = new LyricsController(overlay);
        lyrics.setSecondaryView(binding.audioLyrics);
        binding.audioLyrics.setAudioStageMode(true);
        binding.audioLyrics.setSeekListener(this::onAudioLyricsSeek);
        binding.audioLyrics.setSuppressed(true);
        lyrics.setListener(this);
    }

    @Override
    public void onLyricsChanged(LyricsResult result, List<LyricsLine> lines) {
        currentResult = result;
        currentLines = lines == null ? null : new ArrayList<>(lines);
        syncDesktopLyricsSnapshot();
        updateAudioStageText();
    }

    private void syncDesktopLyricsSnapshot() {
        if (host.service() == null) return;
        host.service().setDesktopLyricsSnapshot(currentResult, currentLines);
    }

    private void onAudioLyricsSeek(long positionMs) {
        if (!ready() || player().isEmpty()) return;
        long duration = player().getDuration();
        long target = duration > 0 ? Math.min(Math.max(0, positionMs), Math.max(0, duration - 500)) : Math.max(0, positionMs);
        player().seekTo(target);
        if (host.history() != null) host.history().setPosition(target);
        if (lyrics != null) lyrics.update(target);
    }

    private void updateLyrics(boolean playing) {
        if (!ready() || player().isEmpty() || lyrics == null) return;
        lyrics.update(player(), playing);
    }

    private void togglePlay() {
        if (!ready() || player().isEmpty()) return;
        if (player().isPlaying()) player().pause();
        else player().play();
    }

    private void onRepeat() {
        if (!ready()) return;
        player().setRepeatOne(!player().isRepeatOne());
        setAudioRepeatSelected(player().isRepeatOne());
    }

    private void setAudioRepeatSelected(boolean selected) {
        binding.audioRepeatAction.setSelected(selected);
        binding.audioRepeatAction.setAlpha(selected ? 1f : 0.62f);
    }

    private void checkAudioPlayImg(boolean playing) {
        binding.audioPlay.setImageResource(playing ? R.drawable.ic_audio_pause : R.drawable.ic_audio_play);
    }

    public void setAudioStageVisible(boolean visible) {
        visible = visible && PlayerSetting.isImmersiveAudioMode();
        if (stageVisible == visible) {
            if (visible) updateAudioStageText();
            return;
        }
        stageVisible = visible;
        if (visible) {
            reparentStage(true);
            stageView.setVisibility(View.VISIBLE);
            stageView.bringToFront();
            ensureLyrics();
            attachSeek(true);
            applyAudioBackground();
            updateAudioStageControls();
            chainStageFocus();
            stageView.post(() -> binding.audioPlay.requestFocus());
        } else {
            attachSeek(false);
            reparentStage(false);
            stageView.setVisibility(View.GONE);
        }
        overlay.setAudioStageMode(visible);
        overlay.setSuppressed(visible);
        binding.audioLyrics.setSuppressed(!visible);
        host.onStageVisibilityChanged(visible);
    }

    private void chainStageFocus() {
        binding.audioRepeatAction.setNextFocusRightId(binding.audioPrev.getId());
        binding.audioRepeatAction.setNextFocusLeftId(binding.audioQueueAction.getId());
        binding.audioRepeatAction.setNextFocusDownId(binding.audioMoreAction.getId());
        binding.audioPrev.setNextFocusRightId(binding.audioPlay.getId());
        binding.audioPrev.setNextFocusLeftId(binding.audioRepeatAction.getId());
        binding.audioPrev.setNextFocusDownId(binding.audioMoreAction.getId());
        binding.audioPlay.setNextFocusRightId(binding.audioNext.getId());
        binding.audioPlay.setNextFocusLeftId(binding.audioPrev.getId());
        binding.audioPlay.setNextFocusDownId(binding.audioLyricsAction.getId());
        binding.audioNext.setNextFocusRightId(binding.audioQueueAction.getId());
        binding.audioNext.setNextFocusLeftId(binding.audioPlay.getId());
        binding.audioNext.setNextFocusDownId(binding.audioLyricsAction.getId());
        binding.audioQueueAction.setNextFocusRightId(binding.audioRepeatAction.getId());
        binding.audioQueueAction.setNextFocusLeftId(binding.audioNext.getId());
        binding.audioQueueAction.setNextFocusDownId(binding.audioLyricsAction.getId());
        binding.audioLyricsAction.setNextFocusRightId(binding.audioMoreAction.getId());
        binding.audioLyricsAction.setNextFocusUpId(binding.audioPlay.getId());
        binding.audioMoreAction.setNextFocusLeftId(binding.audioLyricsAction.getId());
        binding.audioMoreAction.setNextFocusUpId(binding.audioPlay.getId());
    }

    private void attachSeek(boolean attach) {
        if (seekAttached == attach) return;
        seekAttached = attach;
        binding.audioSeek.setPlayer(attach && host.service() != null ? player().getPlayer() : null);
    }

    private void reparentStage(boolean toRoot) {
        ViewGroup current = (ViewGroup) stageView.getParent();
        ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content);
        if (root == null) return;
        ViewGroup target = toRoot ? root : originalParent;
        if (target == null || current == target) return;
        if (current != null) current.removeView(stageView);
        target.addView(stageView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void applyAudioBackground() {
        if (!stageVisible) return;
        AudioPlayerBackgroundDrawable drawable = new AudioPlayerBackgroundDrawable(PlayerSetting.getAudioBackground(), artworkColor, PlayerSetting.isAudioBackgroundDecorated(), PlayerSetting.isAudioBackgroundLightEffect(), ready() && player().isPlaying(), PlayerSetting.getAudioBackgroundSeed(), PlayerSetting.getAudioBackgroundDecorationSeed());
        stageView.setBackground(drawable);
        stageView.invalidate();
    }

    private void randomizeAudioBackgroundMix(boolean notify) {
        PlayerSetting.putAudioBackground(PlayerSetting.AUDIO_BACKGROUND_RANDOM);
        PlayerSetting.putAudioBackgroundDecorated(true);
        int seed = mixSeed((int) System.nanoTime() ^ (int) System.currentTimeMillis() ^ (++backgroundNonce * 0x9E3779B9));
        PlayerSetting.putAudioBackgroundSeed(seed);
        PlayerSetting.putAudioBackgroundDecorationSeed(mixSeed(seed ^ 0x5A17B3));
        applyAudioBackground();
        if (notify) Notify.show(R.string.player_audio_background_random_mix_done);
    }

    private int mixSeed(int seed) {
        int value = seed == 0 ? 0x5A17B3 : seed;
        value ^= value >>> 16;
        value *= 0x85EBCA6B;
        value ^= value >>> 13;
        value *= 0xC2B2AE35;
        value ^= value >>> 16;
        return value;
    }

    private void updateAudioStageControls() {
        setAudioRepeatSelected(ready() && player().isRepeatOne());
        checkAudioPlayImg(ready() && player().isPlaying());
        updateAudioStageText();
    }

    private void updateAudioStageText() {
        if (!stageVisible) return;
        String title = getAudioStageTitle();
        String artist = getAudioStageArtist(title);
        binding.audioTitle.setText(TextUtils.isEmpty(title) ? getString(R.string.player_audio_badge_audio) : title);
        binding.audioSubtitle.setText(artist);
        binding.audioSubtitle.setVisibility(TextUtils.isEmpty(artist) ? View.GONE : View.VISIBLE);
        loadCover();
    }

    private void loadCover() {
        String pic = host.getVodPic();
        if (TextUtils.isEmpty(pic)) return;
        ImgUtil.load(activity, pic, new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                int color = extractColor(resource);
                if (color != artworkColor) {
                    artworkColor = color;
                    applyAudioBackground();
                }
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
            }
        });
    }

    private int extractColor(Drawable drawable) {
        try {
            if (!(drawable instanceof BitmapDrawable)) return artworkColor;
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return artworkColor;
            Bitmap small = Bitmap.createScaledBitmap(bitmap, 8, 8, true);
            long red = 0, green = 0, blue = 0;
            int count = small.getWidth() * small.getHeight();
            for (int x = 0; x < small.getWidth(); x++) {
                for (int y = 0; y < small.getHeight(); y++) {
                    int pixel = small.getPixel(x, y);
                    red += Color.red(pixel);
                    green += Color.green(pixel);
                    blue += Color.blue(pixel);
                }
            }
            int result = Color.rgb((int) (red / count), (int) (green / count), (int) (blue / count));
            if (small != bitmap) small.recycle();
            return result;
        } catch (Throwable e) {
            return artworkColor;
        }
    }

    private String getAudioStageTitle() {
        Episode episode = host.getEpisode();
        AudioPlaylistStore.Metadata metadata = episode == null ? null : AudioPlaylistStore.getMetadata(episode.getUrl());
        if (metadata != null && !TextUtils.isEmpty(metadata.title)) return metadata.title;
        if (host.history() != null && !TextUtils.isEmpty(host.history().getVodName())) return host.history().getVodName();
        if (!TextUtils.isEmpty(host.getVodName())) return host.getVodName();
        return "";
    }

    private String getAudioStageArtist(String title) {
        Episode item = host.getEpisode();
        AudioPlaylistStore.Metadata metadata = item == null ? null : AudioPlaylistStore.getMetadata(item.getUrl());
        if (metadata != null && !TextUtils.isEmpty(metadata.artist)) return metadata.artist;
        String episode = item == null ? "" : item.getName();
        String artist = artistFromEpisode(title, episode);
        return TextUtils.equals(artist, title) ? "" : artist;
    }

    private String artistFromEpisode(String title, String episode) {
        String name = Objects.toString(title, "").trim();
        String value = Objects.toString(episode, "").trim();
        if (name.isEmpty() || value.isEmpty() || TextUtils.equals(name, value)) return "";
        for (String separator : new String[]{" - ", " – ", " — ", "-"}) {
            if (value.startsWith(name + separator) && value.length() > name.length() + separator.length()) return value.substring(name.length() + separator.length()).trim();
            if (value.endsWith(separator + name) && value.length() > name.length() + separator.length()) return value.substring(0, value.length() - name.length() - separator.length()).trim();
        }
        return value;
    }

    public void release() {
        if (karaoke != null) {
            karaoke.setListener(null);
            karaoke.release();
            karaoke = null;
        }
        if (lyrics != null) {
            lyrics.setListener(null);
            lyrics.release();
            lyrics = null;
        }
        if (stageVisible) setAudioStageVisible(false);
        dismissSheets();
    }

    private void dismissSheets() {
        if (sheet != null) {
            sheet.dismiss();
            sheet = null;
        }
        if (lyricsSheet != null) {
            lyricsSheet.dismiss();
            lyricsSheet = null;
        }
        if (karaokePitchSheet != null) {
            karaokePitchSheet.dismiss();
            karaokePitchSheet = null;
            karaokePitchProgress = null;
            karaokePitchMessage = null;
        }
    }

    private String getString(int resId, Object... args) {
        return activity.getString(resId, args);
    }

    private void syncQueueToStore(List<Episode> items) {
        AudioPlaylistStore.Playlist playlist = AudioPlaylistStore.active();
        playlist.items.clear();
        for (Episode item : items) {
            AudioPlaylistStore.Entry entry = new AudioPlaylistStore.Entry();
            entry.url = item.getUrl();
            entry.name = item.getName();
            playlist.items.add(entry);
        }
        AudioPlaylistStore.upsertPlaylist(playlist);
    }

    private void onAudioQueue() {
        restoreActiveAudioPlaylist();
        showAudioQueueSheet();
    }

    private void restoreActiveAudioPlaylist() {
        List<Episode> items = host.getQueueEpisodes();
        if (items == null) items = new ArrayList<>();
        AudioPlaylistStore.Playlist playlist = AudioPlaylistStore.active();
        List<Episode> merged = new ArrayList<>(items);
        for (AudioPlaylistStore.Entry entry : playlist.items) {
            if (containsEpisode(merged, entry.url)) continue;
            String name = TextUtils.isEmpty(entry.title) ? entry.name : entry.title;
            if (TextUtils.isEmpty(name)) name = entry.url;
            if (!TextUtils.isEmpty(entry.artist) && !name.contains(entry.artist)) name = entry.artist + " - " + name;
            merged.add(Episode.create(name, entry.url));
        }
        if (merged.size() != items.size()) host.setQueueEpisodes(merged);
        syncQueueToStore(merged);
    }

    private boolean containsEpisode(List<Episode> items, String url) {
        for (Episode item : items) if (TextUtils.equals(item.getUrl(), url)) return true;
        return false;
    }

    private void showAudioQueueSheet() {
        dismissSheets();
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot();
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = createSheetText(getString(R.string.player_audio_playlist), 17, true);
        TextView subtitle = createSheetText(AudioPlaylistStore.active().name, 12, false);
        subtitle.setTextColor(0xB8FFFFFF);
        LinearLayout titleGroup = new LinearLayout(activity);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        titleGroup.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleGroup.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(titleGroup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        header.addView(createSheetMiniButton(getString(R.string.player_audio_playlist_create), this::showPlaylistCreateSheet), miniParams());
        root.addView(header, topParams(0, ResUtil.dp2px(42)));

        LinearLayout searchRow = new LinearLayout(activity);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        TextInputLayout layout = new TextInputLayout(activity);
        layout.setHint(getString(R.string.player_audio_playlist_search_hint));
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setSingleLine(true);
        input.setMaxLines(1);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0x70FFFFFF);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        searchRow.addView(layout, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        searchRow.addView(createSheetIconButton(R.drawable.ic_action_search, () -> submitQueueSearch(input)), new LinearLayout.LayoutParams(ResUtil.dp2px(46), ResUtil.dp2px(46)));
        root.addView(searchRow, topParams(8, ResUtil.dp2px(56)));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            submitQueueSearch(input);
            return true;
        });

        queueStatus = createSheetText("", 13, false);
        queueStatus.setTextColor(0xB8FFFFFF);
        root.addView(queueStatus, topParams(4, ResUtil.dp2px(26)));

        queueSearchList = new LinearLayout(activity);
        queueSearchList.setOrientation(LinearLayout.VERTICAL);
        root.addView(queueSearchList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RecyclerView list = new RecyclerView(activity);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setItemAnimator(null);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(queueAdapter = new AudioQueueAdapter());
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (ResUtil.getScreenHeight() * 0.45f)));
        renderAudioQueueList();

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            if (sheet == dialog) {
                sheet = null;
                queueAdapter = null;
                queueSearchList = null;
                queueStatus = null;
                queueSearchSeq++;
            }
        });
        sheet = dialog;
        dialog.show();
    }

    private void submitQueueSearch(TextInputEditText input) {
        String keyword = input.getText() == null ? "" : input.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            input.setError(getString(R.string.player_audio_playlist_search_required));
            return;
        }
        Util.hideKeyboard(input);
        searchAudioQueue(keyword);
    }

    private void searchAudioQueue(String keyword) {
        int seq = ++queueSearchSeq;
        if (queueStatus != null) queueStatus.setText(getString(R.string.search_loading));
        if (queueSearchList != null) queueSearchList.removeAllViews();
        Site site = host.getSite();
        if (site == null) {
            if (queueStatus != null) queueStatus.setText(getString(R.string.player_audio_playlist_no_results));
            return;
        }
        Task.execute(() -> {
            try {
                Result result = SiteApi.searchContent(site, keyword, false, "1");
                List<Vod> items = result.getList();
                items.removeIf(item -> TextUtils.isEmpty(item.getId()));
                App.post(() -> showQueueSearchResults(seq, items));
            } catch (Exception e) {
                App.post(() -> {
                    if (seq == queueSearchSeq && queueStatus != null) queueStatus.setText(Notify.getError(R.string.player_audio_playlist_search_failed, e));
                });
            }
        });
    }

    private void showQueueSearchResults(int seq, List<Vod> items) {
        if (seq != queueSearchSeq || queueSearchList == null) return;
        queueSearchList.removeAllViews();
        if (items == null || items.isEmpty()) {
            queueStatus.setText(getString(R.string.player_audio_playlist_no_results));
            return;
        }
        queueStatus.setText(getString(R.string.player_audio_playlist_result_count, items.size()));
        for (int i = 0; i < items.size(); i++) {
            Vod item = items.get(i);
            String label = item.getName() + (TextUtils.isEmpty(item.getRemarks()) ? "" : "\n" + item.getRemarks());
            TextView view = createSheetItem(label, () -> addQueueVod(item));
            queueSearchList.addView(view, topParams(i == 0 ? 4 : 0, ResUtil.dp2px(50)));
        }
    }

    private void addQueueVod(Vod item) {
        if (item == null || TextUtils.isEmpty(item.getId())) return;
        int seq = ++queueSearchSeq;
        if (queueStatus != null) queueStatus.setText(getString(R.string.player_audio_playlist_adding, item.getName()));
        Task.execute(() -> {
            try {
                String key = TextUtils.isEmpty(item.getSiteKey()) ? host.getSiteKey() : item.getSiteKey();
                Vod vod = SiteApi.detailContent(key, item.getId()).getVod();
                App.post(() -> appendQueueVod(seq, vod));
            } catch (Exception e) {
                App.post(() -> {
                    if (seq == queueSearchSeq && queueStatus != null) queueStatus.setText(Notify.getError(R.string.player_audio_playlist_add_failed, e));
                });
            }
        });
    }

    private void appendQueueVod(int seq, Vod vod) {
        if (seq != queueSearchSeq || vod == null) return;
        Flag queue = host.getFlag();
        if (queue == null || vod.getFlags().isEmpty()) {
            if (queueStatus != null) queueStatus.setText(getString(R.string.player_audio_playlist_add_empty));
            return;
        }
        int added = 0;
        List<Episode> items = new ArrayList<>(host.getQueueEpisodes());
        for (Flag source : vod.getFlags()) {
            for (Episode item : source.getEpisodes()) {
                if (TextUtils.isEmpty(item.getUrl()) || containsEpisode(items, item.getUrl())) continue;
                items.add(Episode.create(vod.getName() + " - " + item.getName(), item.getUrl()));
                AudioPlaylistStore.putMetadata(item.getUrl(), item.getName(), vod.getName());
                added++;
            }
        }
        host.setQueueEpisodes(items);
        syncQueueToStore(items);
        renderAudioQueueList();
        if (queueStatus != null) queueStatus.setText(getString(added > 0 ? R.string.player_audio_playlist_added : R.string.player_audio_playlist_exists, added));
    }

    private void renderAudioQueueList() {
        if (queueAdapter == null) return;
        List<Episode> items = host.getQueueEpisodes();
        queueAdapter.addAll(new ArrayList<>(items));
        queueAdapter.setSelectedPosition(Math.max(0, positionOf(items)));
        queueAdapter.setOnItemClickListener(this::playQueueItem);
        if (queueStatus != null && items.isEmpty()) queueStatus.setText(getString(R.string.player_audio_playlist_empty));
    }

    private int positionOf(List<Episode> items) {
        Episode current = host.getEpisode();
        if (current == null) return 0;
        for (int i = 0; i < items.size(); i++) if (TextUtils.equals(items.get(i).getUrl(), current.getUrl())) return i;
        return 0;
    }

    private void playQueueItem(Episode item) {
        dismissSheets();
        host.playEpisode(item);
    }

    private void showPlaylistCreateSheet() {
        dismissSheets();
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot();
        root.addView(createSheetText(getString(R.string.player_audio_playlist_name_hint), 15, true), topParams(0, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextInputLayout layout = new TextInputLayout(activity);
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(layout, topParams(8, ResUtil.dp2px(64)));
        root.addView(createSheetButton(getString(R.string.player_audio_playlist_create), () -> {
            String name = input.getText() == null ? "" : input.getText().toString().trim();
            AudioPlaylistStore.create(name);
            dialog.dismiss();
            if (sheet == dialog) sheet = null;
        }), topParams(8, ResUtil.dp2px(48)));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            if (sheet == dialog) sheet = null;
        });
        sheet = dialog;
        dialog.show();
    }

    private void onLyricsSearch() {
        if (!ready()) return;
        LyricsRequest request = LyricsRequest.from(player());
        showLyricsSearchSheet(request);
    }

    private void showLyricsSearchSheet(LyricsRequest request) {
        dismissSheets();
        if (lyrics == null) ensureLyrics();
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot();
        root.addView(createSheetText(getString(R.string.player_lyrics_search), 17, true), topParams(0, ResUtil.dp2px(40)));
        lyricsStatus = createSheetText(request != null && request.isValid() ? request.displayKeyword() : "", 13, false);
        lyricsStatus.setTextColor(0xB8FFFFFF);
        root.addView(lyricsStatus, topParams(4, ResUtil.dp2px(26)));
        lyricsResultList = new LinearLayout(activity);
        lyricsResultList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(lyricsResultList, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (ResUtil.getScreenHeight() * 0.5f)));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            if (lyricsSheet == dialog) {
                lyricsSheet = null;
                lyricsResultList = null;
                lyricsStatus = null;
                lyricsSearchSeq++;
            }
        });
        lyricsSheet = dialog;
        dialog.show();
        if (lyrics != null && request != null && request.isValid()) {
            int seq = ++lyricsSearchSeq;
            if (lyricsStatus != null) lyricsStatus.setText(getString(R.string.player_lyrics_searching));
            lyrics.search(request, (results, complete) -> showLyricsResults(seq, results, complete));
        }
    }

    private void showLyricsResults(int seq, List<LyricsResult> results, boolean complete) {
        if (seq != lyricsSearchSeq || lyricsResultList == null) return;
        if (!complete) return;
        lyricsResultList.removeAllViews();
        if (results == null || results.isEmpty()) {
            if (lyricsStatus != null) lyricsStatus.setText(getString(R.string.player_lyrics_not_found));
            return;
        }
        if (lyricsStatus != null) lyricsStatus.setText("");
        for (int i = 0; i < results.size(); i++) {
            LyricsResult item = results.get(i);
            String label = getString(R.string.player_lyrics_result_item, TextUtils.isEmpty(item.getSource()) ? getString(R.string.player_lyrics_unknown) : item.getSource(), item.hasWordTiming() ? getString(R.string.player_lyrics_word) : item.isSynced() ? getString(R.string.player_lyrics_synced) : getString(R.string.player_lyrics_plain), item.getDurationMs() / 1000, item.getTrackName(), item.getArtistName());
            TextView view = createSheetItem(label, () -> applyLyricsResult(item));
            lyricsResultList.addView(view, topParams(i == 0 ? 4 : 0, ResUtil.dp2px(56)));
        }
    }

    private void applyLyricsResult(LyricsResult result) {
        dismissSheets();
        if (lyrics == null || !ready()) return;
        lyrics.apply(player(), result, true, applied -> {
            if (applied != null) Notify.show(getString(R.string.player_lyrics_loaded, TextUtils.isEmpty(applied.getSource()) ? getString(R.string.player_lyrics_unknown) : applied.getSource()));
            else Notify.show(R.string.player_lyrics_not_found);
        });
    }

    private void showAudioMoreSheet() {
        dismissSheets();
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot();
        root.addView(createSheetText(getString(R.string.player_audio_options), 17, true), topParams(0, ResUtil.dp2px(40)));
        root.addView(sheetRow(getString(R.string.player_lyrics_rows) + " " + getString(R.string.player_lyrics_rows_value, PlayerSetting.getLyricsRows()), () -> showChoiceSheet(getString(R.string.player_lyrics_rows), rowsItems(), PlayerSetting.getLyricsRows() - 1, which -> {
            PlayerSetting.putLyricsRows(which + 1);
            applyLyricsRuntimeSettings();
        })), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_lyrics_size) + " " + sizeText(), () -> showChoiceSheet(getString(R.string.player_lyrics_size), ResUtil.getStringArray(R.array.select_lyrics_size), PlayerSetting.getLyricsTextSizeOption(), which -> {
            PlayerSetting.putLyricsTextSizeOption(which);
            applyLyricsRuntimeSettings();
        })), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_lyrics_source) + " " + sourceText(), () -> showChoiceSheet(getString(R.string.player_lyrics_source), ResUtil.getStringArray(R.array.select_lyrics_source), LyricsSetting.getSourceMode(), which -> {
            LyricsSetting.putSourceMode(which);
            if (lyrics != null) lyrics.clear();
            refreshLyricsNow();
        })), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_lyrics_offset) + " " + getString(R.string.player_lyrics_offset_value, PlayerSetting.getLyricsTimeOffsetMs()), this::adjustLyricsOffset), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_audio_background_decorated_turn_on) + "/" + getString(R.string.player_audio_background_decorated_turn_off), () -> {
            PlayerSetting.putAudioBackgroundDecorated(!PlayerSetting.isAudioBackgroundDecorated());
            applyAudioBackground();
            dismissSheets();
        }), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_audio_background_random_plain), () -> {
            PlayerSetting.putAudioBackgroundDecorated(false);
            randomizeAudioBackgroundMix(true);
            dismissSheets();
        }), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_karaoke_mode) + " " + getString(PlayerSetting.isKaraokeMode() ? R.string.player_karaoke_track_option_enabled : R.string.player_karaoke_track_option_disabled), this::showKaraokeSheet), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_lyrics_cache) + " " + getString(R.string.player_lyrics_cache_value, LyricsRepository.cacheCount()), () -> {
            LyricsRepository.clearCache();
            Notify.show(R.string.player_lyrics_cache_cleared);
            dismissSheets();
        }), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_desktop_lyrics) + " " + getString(PlayerSetting.isDesktopLyrics() ? R.string.setting_on : R.string.setting_off), () -> {
            toggleDesktopLyrics();
            dismissSheets();
        }), topParams(6, ResUtil.dp2px(48)));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            if (sheet == dialog) sheet = null;
        });
        sheet = dialog;
        dialog.show();
    }

    private void adjustLyricsOffset() {
        long current = PlayerSetting.getLyricsTimeOffsetMs();
        long next = current + 500 > 5000 ? -5000 : current + 500;
        PlayerSetting.putLyricsTimeOffsetMs(next);
        Notify.show(getString(R.string.player_lyrics_offset_value, next));
        applyLyricsRuntimeSettings();
    }

    private void toggleDesktopLyrics() {
        boolean enabled = !PlayerSetting.isDesktopLyrics();
        PlayerSetting.putDesktopLyrics(enabled);
        if (enabled && !canDrawOverlays()) {
            Notify.show(R.string.player_desktop_lyrics_permission);
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception e) {
                SpiderDebug.log(e);
            }
        }
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(activity);
    }

    private void ensureKaraoke() {
        if (karaoke != null) return;
        karaoke = new KaraokeController();
        karaoke.setListener((status, track, sample, snapshot) -> {
            boolean playing = ready() && !player().isEmpty() && player().isPlaying();
            binding.audioKaraoke.setPlaying(playing);
            binding.audioKaraoke.setState(status, track, sample, snapshot);
        });
    }

    private void refreshKaraoke(boolean audioContent) {
        ensureKaraoke();
        karaoke.refresh(activity, player(), audioContent);
    }

    private void reloadKaraoke() {
        if (karaoke == null || !ready()) return;
        karaoke.reload(activity, player(), shouldUseImmersiveAudio());
    }

    private void updateKaraoke() {
        if (karaoke == null || !ready() || player().isEmpty()) return;
        long position = Math.max(0, player().getPosition() + PlayerSetting.getLyricsTimeOffsetMs());
        binding.audioKaraoke.syncPosition(position, player().isPlaying());
        karaoke.update(player(), lyrics == null ? null : lyrics.getLines());
    }

    public void onKaraokeModeChanged() {
        karaokeResultShown = false;
        if (PlayerSetting.isKaraokeMode()) refreshKaraoke(shouldUseImmersiveAudio());
        else if (karaoke != null) karaoke.clear();
    }

    private void showKaraokeSheet() {
        dismissSheets();
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot();
        root.addView(createSheetText(getString(R.string.player_karaoke_mode), 17, true), topParams(0, ResUtil.dp2px(40)));
        root.addView(sheetRow(getString(R.string.player_karaoke_mode) + " " + getString(PlayerSetting.isKaraokeMode() ? R.string.player_karaoke_track_option_enabled : R.string.player_karaoke_track_option_disabled), () -> {
            PlayerSetting.putKaraokeMode(!PlayerSetting.isKaraokeMode());
            onKaraokeModeChanged();
            showKaraokeSheet();
        }), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_karaoke_mic_delay) + " " + getString(R.string.player_lyrics_offset_value, PlayerSetting.getKaraokeMicDelayMs()), this::adjustKaraokeDelay), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_karaoke_difficulty) + " " + karaokeDifficultyText(), this::showKaraokeDifficultySheet), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_karaoke_track_generate_pitch), () -> {
            dismissSheets();
            generateKaraokePitchTrack();
        }), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_karaoke_track_search), this::showKaraokeTrackSearchSheet), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_karaoke_track_import_file), () -> {
            dismissSheets();
            host.launchKaraokeTrackFileChooser();
        }), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_karaoke_track_import_url), this::showKaraokeTrackUrlSheet), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_karaoke_track_sources), this::showKaraokeTrackSourcesSheet), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(basicPitchLabel(), () -> {
            PlayerSetting.putKaraokeBasicPitchTflite(!PlayerSetting.isKaraokeBasicPitchTflite());
            showKaraokeSheet();
        }), topParams(6, ResUtil.dp2px(48)));
        root.addView(sheetRow(getString(R.string.player_karaoke_track_clear), () -> {
            boolean cleared = ready() && KaraokeTrackRepository.clearBinding(player());
            Notify.show(cleared ? R.string.player_karaoke_track_cleared : R.string.player_karaoke_track_none);
            if (cleared) applyKaraokeTrackChange(false);
            showKaraokeSheet();
        }), topParams(6, ResUtil.dp2px(48)));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            if (sheet == dialog) sheet = null;
        });
        sheet = dialog;
        dialog.show();
    }

    private void adjustKaraokeDelay() {
        long current = PlayerSetting.getKaraokeMicDelayMs();
        long next = current + 100 > 1000 ? -1000 : current + 100;
        PlayerSetting.putKaraokeMicDelayMs(next);
        Notify.show(getString(R.string.player_karaoke_mic_delay) + " " + getString(R.string.player_lyrics_offset_value, next));
        reloadKaraoke();
        showKaraokeSheet();
    }

    private String karaokeDifficultyText() {
        String[] items = ResUtil.getStringArray(R.array.select_karaoke_difficulty);
        return items[Math.min(PlayerSetting.getKaraokeDifficulty(), items.length - 1)];
    }

    private void showKaraokeDifficultySheet() {
        String[] items = ResUtil.getStringArray(R.array.select_karaoke_difficulty);
        showChoiceSheet(getString(R.string.player_karaoke_difficulty), items, PlayerSetting.getKaraokeDifficulty(), which -> {
            PlayerSetting.putKaraokeDifficulty(which);
            reloadKaraoke();
            showKaraokeSheet();
        });
    }

    private String basicPitchLabel() {
        return getString(R.string.player_karaoke_track_basic_pitch_tflite, getString(PlayerSetting.isKaraokeBasicPitchTflite() ? R.string.player_karaoke_track_option_enabled : R.string.player_karaoke_track_option_disabled));
    }

    private void applyKaraokeTrackChange(boolean enableMode) {
        if (enableMode && !PlayerSetting.isKaraokeMode()) {
            PlayerSetting.putKaraokeMode(true);
            onKaraokeModeChanged();
            return;
        }
        reloadKaraoke();
    }

    private void generateKaraokePitchTrack() {
        KaraokeTrackRepository.MediaInput input = ready() ? KaraokeTrackRepository.snapshot(player()) : null;
        List<LyricsLine> lines = lyrics == null ? null : lyrics.getLines();
        if (!KaraokeTrackRepository.canGeneratePitch(input, lines)) {
            Notify.show(R.string.player_karaoke_track_generate_no_lyrics);
            return;
        }
        showKaraokePitchProgress();
        Task.execute(() -> {
            KaraokeTrackRepository.ImportResult result = KaraokeTrackRepository.importGeneratedPitch(input, lines, (percent, stage, elapsedMs, remainingMs) -> App.post(() -> updateKaraokePitchProgress(percent, stage, remainingMs)));
            App.post(() -> onKaraokePitchTrackGenerated(result));
        });
    }

    private void onKaraokePitchTrackGenerated(KaraokeTrackRepository.ImportResult result) {
        dismissKaraokePitchProgress();
        if (result != null && result.isSuccess()) {
            applyKaraokeTrackChange(true);
            Notify.show(R.string.player_karaoke_track_generated_pitch);
        } else if (result != null && KaraokeTrackRepository.isUnsupportedPitchSourceError(result.getError())) {
            Notify.show(R.string.player_karaoke_track_generate_pitch_unsupported_source);
        } else {
            String error = result == null ? "" : result.getError();
            Notify.show(getString(R.string.player_karaoke_track_generate_pitch_failed) + (TextUtils.isEmpty(error) ? "" : "\n" + error));
        }
    }

    private void showKaraokePitchProgress() {
        dismissKaraokePitchProgress();
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot();
        root.addView(createSheetText(getString(R.string.player_karaoke_track_generating_pitch), 16, true), topParams(0, ResUtil.dp2px(36)));
        TextView message = createSheetText(getString(R.string.player_karaoke_track_generating_pitch_message), 13, false);
        message.setTextColor(0xB8FFFFFF);
        root.addView(message, topParams(4, ResUtil.dp2px(54)));
        karaokePitchMessage = createSheetText("", 15, true);
        root.addView(karaokePitchMessage, topParams(4, ResUtil.dp2px(36)));
        karaokePitchProgress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        karaokePitchProgress.setIndeterminate(false);
        karaokePitchProgress.setMax(100);
        karaokePitchProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xE6FFFFFF));
        karaokePitchProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x2AFFFFFF));
        root.addView(karaokePitchProgress, topParams(6, ResUtil.dp2px(24)));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            if (karaokePitchSheet == dialog) {
                karaokePitchSheet = null;
                karaokePitchProgress = null;
                karaokePitchMessage = null;
            }
        });
        karaokePitchSheet = dialog;
        dialog.show();
        updateKaraokePitchProgress(1, KaraokePitchTrackGenerator.STAGE_PREPARE, -1);
    }

    private void updateKaraokePitchProgress(int percent, int stage, long remainingMs) {
        if (karaokePitchProgress == null || karaokePitchMessage == null) return;
        int safePercent = Math.max(0, Math.min(100, percent));
        karaokePitchProgress.setProgress(safePercent);
        karaokePitchMessage.setText(getString(R.string.player_karaoke_track_generating_pitch_progress, safePercent, karaokePitchStageName(stage), formatKaraokePitchRemaining(remainingMs)));
    }

    private String karaokePitchStageName(int stage) {
        if (stage == KaraokePitchTrackGenerator.STAGE_DECODE) return getString(R.string.player_karaoke_track_pitch_stage_decode);
        if (stage == KaraokePitchTrackGenerator.STAGE_ANALYZE) return getString(R.string.player_karaoke_track_pitch_stage_analyze);
        if (stage == KaraokePitchTrackGenerator.STAGE_WRITE) return getString(R.string.player_karaoke_track_pitch_stage_write);
        if (stage == KaraokePitchTrackGenerator.STAGE_FINISH) return getString(R.string.player_karaoke_track_pitch_stage_finish);
        return getString(R.string.player_karaoke_track_pitch_stage_prepare);
    }

    private String formatKaraokePitchRemaining(long remainingMs) {
        if (remainingMs <= 0) return getString(R.string.player_karaoke_track_pitch_remaining_unknown);
        long seconds = remainingMs / 1000;
        if (seconds < 60) return getString(R.string.player_karaoke_track_pitch_remaining_seconds, seconds);
        return getString(R.string.player_karaoke_track_pitch_remaining_minutes, seconds / 60, seconds % 60);
    }

    private void dismissKaraokePitchProgress() {
        if (karaokePitchSheet != null) {
            if (karaokePitchSheet.isShowing()) karaokePitchSheet.dismiss();
            karaokePitchSheet = null;
        }
        karaokePitchProgress = null;
        karaokePitchMessage = null;
    }

    private void showKaraokeTrackSearchSheet() {
        dismissSheets();
        showKaraokeTextInputSheet(getString(R.string.player_karaoke_track_search), getString(R.string.player_karaoke_track_keyword), ready() ? KaraokeTrackRepository.defaultKeyword(player()) : "", false, value -> searchKaraokeTrack(value));
    }

    private void searchKaraokeTrack(String keyword) {
        if (!ready()) return;
        Notify.show(R.string.player_karaoke_track_searching);
        KaraokeTrackRepository.search(player(), keyword, results -> {
            if (results == null || results.isEmpty()) {
                Notify.show(R.string.player_karaoke_track_not_found);
                return;
            }
            showKaraokeTrackResults(results);
        });
    }

    private void showKaraokeTrackResults(List<KaraokeTrackRepository.SearchResult> results) {
        dismissSheets();
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot();
        root.addView(createSheetText(getString(R.string.player_karaoke_track_select), 17, true), topParams(0, ResUtil.dp2px(40)));
        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < results.size(); i++) {
            KaraokeTrackRepository.SearchResult result = results.get(i);
            String source = result.getSource() + (result.isLoginRequired() ? getString(R.string.player_karaoke_track_source_login) : "");
            String label = getString(R.string.player_karaoke_track_result_item, source, result.getArtist(), result.getTitle(), result.getNote());
            content.addView(createSheetItem(label, () -> importKaraokeTrackUrl(result.getUrl())), topParams(i == 0 ? 4 : 0, ResUtil.dp2px(64)));
        }
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (ResUtil.getScreenHeight() * 0.5f)));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            if (sheet == dialog) sheet = null;
        });
        sheet = dialog;
        dialog.show();
    }

    private void showKaraokeTrackUrlSheet() {
        dismissSheets();
        showKaraokeTextInputSheet(getString(R.string.player_karaoke_track_import_url), getString(R.string.player_karaoke_track_url_hint), "", false, this::importKaraokeTrackUrl);
    }

    private void importKaraokeTrackUrl(String url) {
        if (!ready() || TextUtils.isEmpty(url)) return;
        KaraokeTrackRepository.importUrl(player(), url, this::onKaraokeTrackImported);
    }

    private void onKaraokeTrackImported(KaraokeTrackRepository.ImportResult result) {
        if (result != null && result.isSuccess()) {
            Notify.show(R.string.player_karaoke_track_imported);
            applyKaraokeTrackChange(true);
        } else {
            String error = result == null ? "" : result.getError();
            Notify.show(getString(R.string.player_karaoke_track_import_failed) + (TextUtils.isEmpty(error) ? "" : "\n" + error));
        }
    }

    public void onKaraokeTrackFilePicked(String path) {
        if (TextUtils.isEmpty(path)) {
            Notify.show(R.string.player_karaoke_track_import_failed);
            return;
        }
        Task.execute(() -> {
            KaraokeTrackRepository.ImportResult imported;
            try {
                imported = karaoke == null || !ready() ? KaraokeTrackRepository.ImportResult.fail("empty player") : KaraokeTrackRepository.importFile(player(), new java.io.File(path));
            } catch (Exception e) {
                imported = KaraokeTrackRepository.ImportResult.fail(e.getMessage());
            }
            KaraokeTrackRepository.ImportResult finalImported = imported;
            App.post(() -> onKaraokeTrackImported(finalImported));
        });
    }

    private void showKaraokeTrackSourcesSheet() {
        dismissSheets();
        showKaraokeTextInputSheet(getString(R.string.player_karaoke_track_sources), getString(R.string.player_karaoke_track_sources_hint), PlayerSetting.getKaraokeGithubSources(), true, value -> {
            PlayerSetting.putKaraokeGithubSources(value);
            KaraokeTrackRepository.clearSearchCache();
            Notify.show(R.string.player_karaoke_track_sources_saved);
        });
    }

    private void showKaraokeTextInputSheet(String title, String hint, String initial, boolean multiline, InputCallback callback) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot();
        root.addView(createSheetText(title, 16, true), topParams(0, ResUtil.dp2px(40)));
        TextInputLayout layout = new TextInputLayout(activity);
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setHint(hint);
        input.setText(initial);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0x70FFFFFF);
        if (multiline) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setMinLines(3);
            input.setMaxLines(6);
            input.setGravity(Gravity.TOP);
        } else {
            input.setSingleLine(true);
            input.setMaxLines(1);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
        }
        layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(layout, topParams(8, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(createSheetButton(getString(R.string.dialog_positive), () -> {
            String value = input.getText() == null ? "" : input.getText().toString().trim();
            if (TextUtils.isEmpty(value)) {
                input.setError(getString(R.string.player_lyrics_keyword_required));
                return;
            }
            Util.hideKeyboard(input);
            dialog.dismiss();
            if (sheet == dialog) sheet = null;
            callback.onInput(value);
        }), topParams(8, ResUtil.dp2px(48)));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            if (sheet == dialog) sheet = null;
        });
        sheet = dialog;
        dialog.show();
    }

    private interface InputCallback {
        void onInput(String value);
    }

    private boolean showKaraokeResultIfNeeded(Runnable after) {
        if (karaoke == null || !karaoke.isActive() || karaokeResultShown || activity.isFinishing() || activity.isDestroyed()) return false;
        KaraokeResult result = karaoke.getResult();
        if (result == null) return false;
        karaokeResultShown = true;
        KaraokeResultView view = new KaraokeResultView(activity).setResult(result);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setView(view).create();
        view.setAction(() -> {
            dialog.dismiss();
            if (after != null) after.run();
        });
        dialog.setOnCancelListener(d -> {
            if (after != null) after.run();
        });
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.62f;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
            window.setLayout(view.getPreferredDialogWidth(), WindowManager.LayoutParams.WRAP_CONTENT);
            view.requestActionFocus();
        });
        dialog.show();
        return true;
    }

    private void applyLyricsRuntimeSettings() {
        if (lyrics == null) return;
        lyrics.refreshStyle();
        if (ready() && !player().isEmpty()) lyrics.update(player());
    }

    private String[] rowsItems() {
        String[] items = new String[5];
        for (int i = 0; i < items.length; i++) items[i] = getString(R.string.player_lyrics_rows_value, i + 1);
        return items;
    }

    private String sizeText() {
        String[] items = ResUtil.getStringArray(R.array.select_lyrics_size);
        return items[Math.min(PlayerSetting.getLyricsTextSizeOption(), items.length - 1)];
    }

    private String sourceText() {
        String[] items = ResUtil.getStringArray(R.array.select_lyrics_source);
        return items[Math.min(LyricsSetting.getSourceMode(), items.length - 1)];
    }

    private void showChoiceSheet(String title, String[] items, int selected, ChoiceHandler handler) {
        dismissSheets();
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = createSheetRoot();
        root.addView(createSheetText(title, 16, true), topParams(0, ResUtil.dp2px(40)));
        for (int i = 0; i < items.length; i++) {
            int index = i;
            TextView view = createSheetItem(items[i], () -> {
                handler.onChoice(index);
                dismissSheets();
            });
            if (i == selected) view.setTextColor(0xFFFFC766);
            root.addView(view, topParams(i == 0 ? 4 : 0, ResUtil.dp2px(48)));
        }
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            if (sheet == dialog) sheet = null;
        });
        sheet = dialog;
        dialog.show();
    }

    private interface ChoiceHandler {
        void onChoice(int which);
    }

    private LinearLayout createSheetRoot() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(24), ResUtil.dp2px(10), ResUtil.dp2px(24), ResUtil.dp2px(18));
        root.setBackground(sheetBackground());
        View handle = new View(activity);
        handle.setBackground(roundRect(0x55FFFFFF, 2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ResUtil.dp2px(38), ResUtil.dp2px(4));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = ResUtil.dp2px(14);
        root.addView(handle, params);
        return root;
    }

    private Drawable sheetBackground() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xF21A2028, 0xF8101318});
        float radius = ResUtil.dp2px(16);
        drawable.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return drawable;
    }

    private Drawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(ResUtil.dp2px(radiusDp));
        return drawable;
    }

    private TextView createSheetText(String text, int sizeSp, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sizeSp);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private TextView createSheetItem(String label, Runnable action) {
        TextView view = createSheetText(label, 15, false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(ResUtil.dp2px(12), 0, ResUtil.dp2px(12), 0);
        view.setBackground(roundRect(0x14FFFFFF, 10));
        view.setSingleLine(false);
        view.setMaxLines(2);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private TextView createSheetButton(String label, Runnable action) {
        TextView view = createSheetText(label, 15, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(roundRect(0x33FFFFFF, 10));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private TextView createSheetMiniButton(String label, Runnable action) {
        TextView view = createSheetButton(label, action);
        view.setPadding(ResUtil.dp2px(8), 0, ResUtil.dp2px(8), 0);
        view.setTextSize(13);
        return view;
    }

    private View createSheetIconButton(int resId, Runnable action) {
        ImageView view = new ImageView(activity);
        view.setImageResource(resId);
        view.setPadding(ResUtil.dp2px(10), ResUtil.dp2px(10), ResUtil.dp2px(10), ResUtil.dp2px(10));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private TextView sheetRow(String label, Runnable action) {
        TextView view = createSheetText(label, 15, false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(ResUtil.dp2px(12), 0, ResUtil.dp2px(12), 0);
        view.setBackground(roundRect(0x14FFFFFF, 10));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private LinearLayout.LayoutParams topParams(int top, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.topMargin = ResUtil.dp2px(top);
        return params;
    }

    private LinearLayout.LayoutParams miniParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ResUtil.dp2px(34));
        params.leftMargin = ResUtil.dp2px(8);
        return params;
    }
}
