package dev.discordru;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class DeviceSession {
    private static final String ALIAS = "discord_native_session";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private final SharedPreferences preferences;

    DeviceSession(Context context) {
        preferences = context.getSharedPreferences("account", Context.MODE_PRIVATE);
    }

    String read() {
        String iv = preferences.getString("iv", "");
        String encrypted = preferences.getString("session", "");
        if (iv.isEmpty() || encrypted.isEmpty()) return "";
        try {
            SecretKey key = key(store(), false);
            if (key == null) throw new GeneralSecurityException("Session key missing");
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            clear();
            return "";
        }
    }

    boolean save(String token) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key(store(), true));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return preferences.edit()
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString("session", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit();
        } catch (Exception error) {
            return false;
        }
    }

    void clear() {
        preferences.edit().remove("iv").remove("session").apply();
    }

    private static KeyStore store() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    private static SecretKey key(KeyStore store, boolean create) throws GeneralSecurityException {
        if (store.getKey(ALIAS, null) instanceof SecretKey existing) return existing;
        if (!create) return null;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
