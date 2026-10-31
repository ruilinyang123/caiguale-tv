package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

public class DecodeSetting {

    public static boolean isAudioPassThrough() {
        return PlayerSetting.isAudioPassThrough();
    }

    public static void putAudioPassThrough(boolean audioPassThrough) {
        PlayerSetting.putAudioPassThrough(audioPassThrough);
    }

    public static boolean isAudioPrefer() {
        return PlayerSetting.isAudioPrefer();
    }

    public static void putAudioPrefer(boolean audioPrefer) {
        PlayerSetting.putAudioPrefer(audioPrefer);
    }

    public static boolean isVideoPrefer() {
        return PlayerSetting.isVideoPrefer();
    }

    public static void putVideoPrefer(boolean videoPrefer) {
        PlayerSetting.putVideoPrefer(videoPrefer);
    }

    public static int getDolbyVisionOutputPolicy() {
        return Math.min(Math.max(Prefers.getInt("decode_dolby_vision_output_policy", 0), 0), 2);
    }

    public static void putDolbyVisionOutputPolicy(int mode) {
        Prefers.put("decode_dolby_vision_output_policy", Math.min(Math.max(mode, 0), 2));
    }

    public static boolean isPreferAAC() {
        return PlayerSetting.isPreferAAC();
    }

    public static void putPreferAAC(boolean preferAAC) {
        PlayerSetting.putPreferAAC(preferAAC);
    }

    public static boolean isTunnel() {
        return PlayerSetting.isTunnel();
    }

    public static void putTunnel(boolean tunnel) {
        PlayerSetting.putTunnel(tunnel);
    }

    public static boolean isTunnelingEnabled() {
        return isTunnel() && PlayerSetting.getRender() == PlayerSetting.RENDER_SURFACE;
    }
}
