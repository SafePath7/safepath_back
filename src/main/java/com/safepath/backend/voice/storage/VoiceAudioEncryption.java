package com.safepath.backend.voice.storage;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static com.safepath.backend.voice.storage.VoiceAudioStorageException.Reason.*;

final class VoiceAudioEncryption {
    private static final byte[] FORMAT = {'S', 'P', 'V', 'A', 1};
    private static final int NONCE_BYTES = 12;
    private static final int HEADER_BYTES = FORMAT.length + NONCE_BYTES;
    private static final int TAG_BYTES = 16;
    static final int OVERHEAD = HEADER_BYTES + TAG_BYTES;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    VoiceAudioEncryption(String encodedKey) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new VoiceAudioStorageException(INVALID_CONFIGURATION);
        }
        try {
            if (decoded.length != 32) throw new VoiceAudioStorageException(INVALID_CONFIGURATION);
            key = new SecretKeySpec(decoded, "AES");
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    byte[] encrypt(byte[] audio, String reference) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] header = Arrays.copyOf(FORMAT, HEADER_BYTES);
        System.arraycopy(nonce, 0, header, FORMAT.length, nonce.length);
        try {
            byte[] authenticated = cipher(Cipher.ENCRYPT_MODE, header, reference).doFinal(audio);
            byte[] stored = Arrays.copyOf(header, header.length + authenticated.length);
            System.arraycopy(authenticated, 0, stored, header.length, authenticated.length);
            return stored;
        } catch (GeneralSecurityException exception) {
            throw new VoiceAudioStorageException(STORAGE_FAILURE);
        }
    }

    byte[] decrypt(byte[] stored, String reference) {
        if (stored.length <= OVERHEAD || Arrays.mismatch(stored, 0, FORMAT.length, FORMAT, 0, FORMAT.length) != -1) {
            throw new VoiceAudioStorageException(DECRYPTION_FAILED);
        }
        try {
            var cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(stored, HEADER_BYTES), reference);
            return cipher.doFinal(stored, HEADER_BYTES, stored.length - HEADER_BYTES);
        } catch (GeneralSecurityException exception) {
            throw new VoiceAudioStorageException(DECRYPTION_FAILED);
        }
    }

    private Cipher cipher(int mode, byte[] header, String reference) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BYTES * Byte.SIZE, header, FORMAT.length, NONCE_BYTES));
        cipher.updateAAD(header);
        cipher.updateAAD(reference.getBytes(StandardCharsets.US_ASCII));
        return cipher;
    }
}
