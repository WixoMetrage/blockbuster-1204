package mchorse.blockbuster.utils.mclib;

import mchorse.mclib.config.values.ValueString;

/**
 * wixo.1: {@code video.encoder}, the encoding preset id. Stored as a plain
 * string (same JSON as a {@link ValueString}); its own class only so the client
 * shows a preset selector instead of a text field ({@code VideoConfigGui}).
 */
public class ValueVideoEncoder extends ValueString
{
    public ValueVideoEncoder(String id, String defaultValue)
    {
        super(id, defaultValue);
    }
}
