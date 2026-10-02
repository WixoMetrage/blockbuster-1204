package mchorse.blockbuster.utils.mclib;

import mchorse.mclib.config.values.ValueGUI;

/**
 * wixo.1 (CDC R1/R7): the "Resolution" block of the video settings — preset
 * buttons (1080p, 1440p, 4K, window), the free width/height fields and the live
 * effective-resolution line. Stores nothing itself: it edits
 * {@code video.width}/{@code video.height}. Its widgets are contributed by the
 * client ({@code VideoConfigGui}).
 */
public class ValueVideoResolution extends ValueGUI
{
    public ValueVideoResolution(String id)
    {
        super(id);
    }
}
