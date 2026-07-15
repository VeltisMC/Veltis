package org.veltismc.veltis.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class CharsetDetector {

    private static final byte[] UTF_32BE_BOM = {(byte) 0x00, (byte) 0x00, (byte) 0xFE, (byte) 0xFF};
    private static final byte[] UTF_32LE_BOM = {(byte) 0xFF, (byte) 0xFE, (byte) 0x00, (byte) 0x00};
    private static final byte[] UTF_16BE_BOM = {(byte) 0xFE, (byte) 0xFF};
    private static final byte[] UTF_16LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final int BUFFER_SIZE = 4096;

    public enum BomType {
        NONE,
        UTF_8,
        UTF_16BE,
        UTF_16LE,
        UTF_32BE,
        UTF_32LE
    }

    public record Detection(Charset charset, BomType bomType, int bomLength) {
        public static final Detection UTF8 = new Detection(StandardCharsets.UTF_8, BomType.NONE, 0);
        public static final Detection UTF8_BOM = new Detection(StandardCharsets.UTF_8, BomType.UTF_8, 3);
    }

    public static Detection detect(Path path) throws IOException {
        var bytes = Files.readAllBytes(path);
        return detect(bytes);
    }

    public static Detection detect(byte[] bytes) {
        if (bytes.length < 4) {
            return detectSmall(bytes);
        }

        if (startsWith(bytes, UTF_32BE_BOM)) {
            return new Detection(Charset.forName("UTF-32BE"), BomType.UTF_32BE, 4);
        }
        if (startsWith(bytes, UTF_32LE_BOM)) {
            return new Detection(Charset.forName("UTF-32LE"), BomType.UTF_32LE, 4);
        }
        if (startsWith(bytes, UTF_16BE_BOM)) {
            return new Detection(StandardCharsets.UTF_16BE, BomType.UTF_16BE, 2);
        }
        if (startsWith(bytes, UTF_16LE_BOM)) {
            return new Detection(StandardCharsets.UTF_16LE, BomType.UTF_16LE, 2);
        }
        if (startsWith(bytes, UTF_8_BOM)) {
            return Detection.UTF8_BOM;
        }

        return detectEncoding(bytes);
    }

    private static Detection detectSmall(byte[] bytes) {
        if (bytes.length >= 3 && startsWith(bytes, UTF_8_BOM)) {
            return Detection.UTF8_BOM;
        }
        if (bytes.length >= 2) {
            if (startsWith(bytes, UTF_16BE_BOM)) {
                return new Detection(StandardCharsets.UTF_16BE, BomType.UTF_16BE, 2);
            }
            if (startsWith(bytes, UTF_16LE_BOM)) {
                return new Detection(StandardCharsets.UTF_16LE, BomType.UTF_16LE, 2);
            }
        }
        if (bytes.length >= 4) {
            if (startsWith(bytes, UTF_32BE_BOM)) {
                return new Detection(Charset.forName("UTF-32BE"), BomType.UTF_32BE, 4);
            }
            if (startsWith(bytes, UTF_32LE_BOM)) {
                return new Detection(Charset.forName("UTF-32LE"), BomType.UTF_32LE, 4);
            }
        }
        return tryDecode(bytes)
            .orElse(Detection.UTF8);
    }

    private static Detection detectEncoding(byte[] bytes) {
        var sample = bytes.length > BUFFER_SIZE ? Arrays.copyOf(bytes, BUFFER_SIZE) : bytes;

        if (isUTF8(sample)) {
            return Detection.UTF8;
        }
        if (isLikelyUTF16(sample)) {
            return new Detection(StandardCharsets.UTF_16LE, BomType.NONE, 0);
        }
        if (isLikelyWindows1252(sample)) {
            return new Detection(Charset.forName("windows-1252"), BomType.NONE, 0);
        }
        if (isLikelyISO88591(sample)) {
            return new Detection(StandardCharsets.ISO_8859_1, BomType.NONE, 0);
        }

        return tryDecode(bytes)
            .orElse(Detection.UTF8);
    }

    private static boolean isUTF8(byte[] bytes) {
        int i = 0;
        while (i < bytes.length) {
            if (bytes[i] >= 0) {
                i++;
                continue;
            }
            int continuationBytes;
            if ((bytes[i] & 0xE0) == 0xC0) {
                continuationBytes = 1;
            } else if ((bytes[i] & 0xF0) == 0xE0) {
                continuationBytes = 2;
            } else if ((bytes[i] & 0xF8) == 0xF0) {
                continuationBytes = 3;
            } else {
                return false;
            }
            i++;
            if (i + continuationBytes > bytes.length) {
                return false;
            }
            for (int j = 0; j < continuationBytes; j++) {
                if ((bytes[i + j] & 0xC0) != 0x80) {
                    return false;
                }
            }
            i += continuationBytes;
        }
        return true;
    }

    private static boolean isLikelyUTF16(byte[] bytes) {
        int nullCount = 0;
        for (int i = 0; i < bytes.length - 1; i += 2) {
            if (bytes[i] == 0x00 && bytes[i + 1] >= 0x20 && bytes[i + 1] <= 0x7E) {
                nullCount++;
            }
        }
        return nullCount > bytes.length / 16;
    }

    private static boolean isLikelyWindows1252(byte[] bytes) {
        int highBytes = 0;
        int validCount = 0;
        for (byte b : bytes) {
            if (b < 0) {
                highBytes++;
                int ub = b & 0xFF;
                if ((ub >= 0x80 && ub <= 0x9F) || (ub >= 0xA0 && ub <= 0xFF)) {
                    validCount++;
                }
            }
        }
        return highBytes > 0 && validCount > highBytes / 2;
    }

    private static boolean isLikelyISO88591(byte[] bytes) {
        for (byte b : bytes) {
            if (b >= 0) continue;
            int ub = b & 0xFF;
            if (ub < 0xA0) return false;
        }
        return true;
    }

    private static java.util.Optional<Detection> tryDecode(byte[] bytes) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            decoder.decode(ByteBuffer.wrap(bytes));
            return java.util.Optional.of(Detection.UTF8);
        } catch (CharacterCodingException e) {
            try {
                var decoder = StandardCharsets.ISO_8859_1.newDecoder();
                decoder.decode(ByteBuffer.wrap(bytes));
                return java.util.Optional.of(new Detection(StandardCharsets.ISO_8859_1, BomType.NONE, 0));
            } catch (CharacterCodingException e2) {
                return java.util.Optional.empty();
            }
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    public static String readFileWithEncoding(Path path) throws IOException {
        var detection = detect(path);
        var charset = detection.charset();
        var content = Files.readString(path, charset);

        if (detection.bomType() != BomType.NONE) {
            content = content.substring(detection.bomLength() > 0
                ? content.substring(0, Math.min(detection.bomLength(), content.length())).chars()
                    .filter(c -> Character.isBmpCodePoint(c))
                    .map(c -> 1)
                    .sum()
                : 0);
        }
        if (detection.bomType() != BomType.NONE && content.length() > 0) {
            content = content.substring(1);
        }
        switch (detection.bomType()) {
            case UTF_8 -> content = content.replaceFirst("^\uFEFF", "");
            case UTF_16BE, UTF_16LE, UTF_32BE, UTF_32LE -> {
                if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                    content = content.substring(1);
                }
            }
        }

        return content;
    }

    private CharsetDetector() {
    }
}
