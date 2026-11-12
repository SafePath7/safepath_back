package com.safepath.backend.user;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 전화번호를 DB에 안전하게 저장하고, 중복 가입을 확인할 수 있도록 처리
// phone_hash: 같은 번호로 가입한 사용자가 있는지 확인할 때 사용
// phone_encrypted: 나중에 본인 정보를 보여줄 수 있도록 암호화해서 저장
@Component
public class PhoneProtection {
    private final String encryptionKey;
    private final String hashKey;
    private final SecureRandom random = new SecureRandom();

    // 보호에 사용할 키 가져오기
    // AFEPATH_PHONE_ENCRYPTION_KEY: 전화번호 암호화·복호화에 사용
    // SAFEPATH_PHONE_HASH_KEY: 중복 확인용 해시 생성에 사용
    public PhoneProtection(@Value("${SAFEPATH_PHONE_ENCRYPTION_KEY:}") String encryptionKey,
                           @Value("${SAFEPATH_PHONE_HASH_KEY:}") String hashKey) {
        this.encryptionKey = encryptionKey;
        this.hashKey = hashKey;
    }

    private byte[] key(String encoded) {
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != 32) throw new IllegalArgumentException();
            return key;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Phone protection keys must be Base64-encoded 32-byte values", e);
        }
    }

    // 중복 확인용 값 만들기 (전화번호와 해시 키를 이용해 HmacSHA256 값을 만듦)
    public String hash(String phone) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(hashKey), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(phone.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to hash phone number", e);
        }
    }

    // 전화번호 암호화 (전화번호를 AES/GCM 방식으로 암호화)
    // 가입 시 만들어진 값은 phone_encrypted에 저장
    public String encrypt(String phone) {
        try {
            byte[] nonce = new byte[12];    // 무작위 nonce로 만들어 같은 번호라도 암호화 결과는 매번 다름
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(encryptionKey), "AES"), new GCMParameterSpec(128, nonce));
            return "v1:" + Base64.getEncoder().encodeToString(nonce) + ":"
                    + Base64.getEncoder().encodeToString(cipher.doFinal(phone.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt phone number", e);
        }
    }

    // 전화번호 복호화 (GET /api/users/me에서 본인의 전화번호를 반환할 때 사용)
    public String decrypt(String value) {
        try {
            String[] parts = value.split(":", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException("Invalid encrypted phone format");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(encryptionKey), "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to decrypt phone number", e);
        }
    }
}
