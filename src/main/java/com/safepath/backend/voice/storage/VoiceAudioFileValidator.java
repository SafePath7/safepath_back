package com.safepath.backend.voice.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

import static com.safepath.backend.voice.storage.VoiceAudioStorageException.Reason.*;

final class VoiceAudioFileValidator {
    static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final Set<String> WAV_TYPES = Set.of("audio/wav", "audio/wave", "audio/x-wav", "audio/vnd.wave");
    private static final Set<String> M4A_TYPES = Set.of("audio/mp4", "audio/m4a", "audio/x-m4a");
    private static final Set<String> MP3_TYPES = Set.of("audio/mpeg", "audio/mp3");
    private static final int[] MPEG1_LAYER3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] MPEG2_LAYER3 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
    private static final int[] SAMPLE_RATES = {44100, 48000, 32000};

    private VoiceAudioFileValidator() {}

    static byte[] read(String name, String contentType, long declaredSize, InputStream contents) {
        if (name == null || name.isBlank() || name.matches(".*[\\\\/:].*")
                || name.codePoints().anyMatch(Character::isISOControl) || contents == null || declaredSize < -1) {
            throw new VoiceAudioStorageException(INVALID_AUDIO);
        }
        if (declaredSize > MAX_FILE_BYTES) throw new VoiceAudioStorageException(TOO_LARGE);
        int dot = name.lastIndexOf('.');
        if (dot <= 0) throw new VoiceAudioStorageException(INVALID_AUDIO);
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        Set<String> expected = switch (extension) {
            case "wav" -> WAV_TYPES;
            case "m4a" -> M4A_TYPES;
            case "mp3" -> MP3_TYPES;
            default -> throw new VoiceAudioStorageException(INVALID_AUDIO);
        };
        if (contentType != null && !contentType.isBlank()) {
            String mediaType = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            if (!expected.contains(mediaType) && !"application/octet-stream".equals(mediaType)) {
                throw new VoiceAudioStorageException(INVALID_AUDIO);
            }
        }
        byte[] bytes;
        try {
            bytes = contents.readNBytes(MAX_FILE_BYTES + 1);
        } catch (IOException exception) {
            throw new VoiceAudioStorageException(STORAGE_FAILURE);
        }
        if (bytes.length > MAX_FILE_BYTES) throw new VoiceAudioStorageException(TOO_LARGE);
        if (bytes.length == 0 || (declaredSize >= 0 && declaredSize != bytes.length)
                || !switch (extension) {
                    case "wav" -> wave(bytes);
                    case "m4a" -> mp4(bytes);
                    default -> mp3(bytes);
                }) {
            throw new VoiceAudioStorageException(INVALID_AUDIO);
        }
        return bytes;
    }

    private static boolean wave(byte[] data) {
        if (data.length < 12 || !tag(data, 0, "RIFF") || !tag(data, 8, "WAVE")
                || unsigned32(data, 4, true) != data.length - 8L) return false;
        boolean format = false;
        boolean audio = false;
        int offset = 12;
        while (offset <= data.length - 8) {
            long size = unsigned32(data, offset + 4, true);
            long end = offset + 8L + size + (size & 1);
            if (end > data.length) return false;
            if (tag(data, offset, "fmt ")) {
                if (format || size < 16 || unsigned16(data, offset + 8) == 0
                        || unsigned16(data, offset + 10) == 0 || unsigned32(data, offset + 12, true) == 0
                        || unsigned16(data, offset + 20) == 0) return false;
                format = true;
            }
            if (tag(data, offset, "data")) {
                if (!format || size == 0) return false;
                audio = true;
            }
            offset = (int) end;
        }
        return format && audio && offset == data.length;
    }

    private static boolean mp4(byte[] data) {
        if (data.length < 16 || !tag(data, 4, "ftyp")) return false;
        boolean media = false;
        boolean audioTrack = false;
        int offset = 0;
        while (offset <= data.length - 8) {
            int end = boxEnd(data, offset, data.length);
            if (end < 0) return false;
            int payload = boxPayload(data, offset);
            if (offset == 0 && (end - payload < 8 || (end - payload) % 4 != 0
                    || !audioBrand(data, payload, end))) return false;
            if (tag(data, offset + 4, "mdat") && end > payload) media = true;
            if (tag(data, offset + 4, "moov")) audioTrack |= hasAudioHandler(data, payload, end, 0);
            offset = end;
        }
        return media && audioTrack && offset == data.length;
    }

    private static boolean audioBrand(byte[] data, int offset, int end) {
        for (int index = offset; index <= end - 4; index += 4) {
            if (index == offset + 4) continue; // minor version
            if (tag(data, index, "M4A ") || tag(data, index, "isom") || tag(data, index, "mp42")
                    || tag(data, index, "mp41")) return true;
        }
        return false;
    }

    private static boolean hasAudioHandler(byte[] data, int offset, int limit, int level) {
        boolean found = false;
        while (offset <= limit - 8) {
            int end = boxEnd(data, offset, limit);
            if (end < 0) return false;
            int payload = boxPayload(data, offset);
            if (level == 0 && tag(data, offset + 4, "trak")
                    || level == 1 && tag(data, offset + 4, "mdia")) {
                found |= hasAudioHandler(data, payload, end, level + 1);
            } else if (level == 2 && tag(data, offset + 4, "hdlr")
                    && end - payload >= 24 && tag(data, payload + 8, "soun")) {
                found = true;
            }
            offset = end;
        }
        return found && offset == limit;
    }

    private static int boxEnd(byte[] data, int offset, int limit) {
        long size = unsigned32(data, offset, false);
        int header = 8;
        if (size == 1) {
            if (limit - offset < 16 || unsigned32(data, offset + 8, false) != 0) return -1;
            size = unsigned32(data, offset + 12, false);
            header = 16;
        } else if (size == 0) {
            size = limit - offset;
        }
        return size < header || size > limit - offset ? -1 : offset + (int) size;
    }

    private static int boxPayload(byte[] data, int offset) {
        return offset + (unsigned32(data, offset, false) == 1 ? 16 : 8);
    }

    private static boolean mp3(byte[] data) {
        int offset = 0;
        if (tag(data, 0, "ID3")) {
            if (data.length < 10 || data[3] < 2 || data[3] > 4 || data[4] == (byte) 0xff) return false;
            int size = 0;
            for (int index = 6; index < 10; index++) {
                if (data[index] < 0) return false;
                size = (size << 7) | data[index];
            }
            offset = 10 + size;
            if (data[3] == 4 && (data[5] & 0x10) != 0) {
                if (!tag(data, offset, "3DI") || offset > data.length - 10) return false;
                offset += 10;
            }
        }
        int end = data.length;
        if (end >= 128 && tag(data, end - 128, "TAG")) end -= 128;
        int frames = 0;
        while (offset <= end - 4) {
            int first = Byte.toUnsignedInt(data[offset]);
            int second = Byte.toUnsignedInt(data[offset + 1]);
            int third = Byte.toUnsignedInt(data[offset + 2]);
            int version = (second >>> 3) & 3;
            int bitrateIndex = third >>> 4;
            int sampleRateIndex = (third >>> 2) & 3;
            if (first != 255 || (second & 0xe6) != 0xe2 || version == 1
                    || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3
                    || (data[offset + 3] & 3) == 2) return false;
            int bitrate = (version == 3 ? MPEG1_LAYER3 : MPEG2_LAYER3)[bitrateIndex];
            int rate = SAMPLE_RATES[sampleRateIndex] / (version == 3 ? 1 : version == 2 ? 2 : 4);
            int frameSize = (version == 3 ? 144000 : 72000) * bitrate / rate + ((third >>> 1) & 1);
            if (frameSize > end - offset) return false;
            offset += frameSize;
            frames++;
        }
        return frames > 0 && offset == end;
    }

    private static boolean tag(byte[] data, int start, String expected) {
        if (start < 0 || start > data.length - expected.length()) return false;
        for (int index = 0; index < expected.length(); index++) {
            if (data[start + index] != expected.charAt(index)) return false;
        }
        return true;
    }

    private static int unsigned16(byte[] data, int offset) {
        return Byte.toUnsignedInt(data[offset]) | Byte.toUnsignedInt(data[offset + 1]) << 8;
    }

    private static long unsigned32(byte[] data, int offset, boolean littleEndian) {
        long value = 0;
        for (int index = 0; index < 4; index++) {
            value = (value << 8) | Byte.toUnsignedInt(data[offset + (littleEndian ? 3 - index : index)]);
        }
        return value;
    }
}
