package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class EpisodeTitleCompactTest {

    @Test
    public void compactsSharedSeriesAndTechnicalSuffix() {
        List<String> result = EpisodeTitleCompact.compact(Arrays.asList(
                "Example.Show.S01E01.1080P.WEB-DL.mkv",
                "Example.Show.S01E02.1080P.WEB-DL.mkv"));

        assertEquals(Arrays.asList("S01E01", "S01E02"), result);
    }

    @Test
    public void keepsSizeAndDistinguishesVariants() {
        List<String> result = EpisodeTitleCompact.compact(Arrays.asList(
                "Series S01E01 1080P HEVC [1.5GB].mkv",
                "Series S01E01 2160P HDR [3GB].mkv"));

        assertEquals(Arrays.asList("1080P HEVC [1.5GB]", "2160P HDR [3GB]"), result);
    }

    @Test
    public void preservesSingleStructuredEpisodeTitle() {
        List<String> result = EpisodeTitleCompact.compact(Arrays.asList(
                "[01.2024 第一季] [01][启程][1080P].mkv"));

        assertEquals(Arrays.asList("第一季 01 启程"), result);
    }
}
