package com.example.lazygallery.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class IdCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private IdCodec() {
    }

    public static String encode(String input) {
        return ENCODER.encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String input) {
        return new String(DECODER.decode(input), StandardCharsets.UTF_8);
    }
}
