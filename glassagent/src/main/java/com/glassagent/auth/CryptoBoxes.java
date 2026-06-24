package com.glassagent.auth;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class CryptoBoxes {
    interface Box {
        String encrypt(String value) throws Exception;
        String decrypt(String stored) throws Exception;
    }

    private CryptoBoxes() {
    }

    static Box create(Context context, SharedPreferences prefs, String alias) {
        if (Build.VERSION.SDK_INT >= 23) {
            return new HardwareBox(alias);
        }
        return new LegacyBox(prefs, alias);
    }

    @TargetApi(23)
    private static final class HardwareBox implements Box {
        private final String alias;

        HardwareBox(String alias) {
            this.alias = alias;
            ensureKey();
        }

        @Override
        public String encrypt(String value) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(iv, Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        }

        @Override
        public String decrypt(String stored) throws Exception {
            if (stored == null || stored.length() == 0 || !stored.contains(":")) {
                return null;
            }
            String[] parts = stored.split(":", 2);
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }

        private void ensureKey() {
            try {
                KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                keyStore.load(null);
                if (keyStore.containsAlias(alias)) {
                    return;
                }
                KeyGenerator generator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .build());
                generator.generateKey();
            } catch (Exception e) {
                throw new IllegalStateException("Unable to create hardware key", e);
            }
        }

        private SecretKey getKey() throws Exception {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            return (SecretKey) keyStore.getKey(alias, null);
        }
    }

    private static final class LegacyBox implements Box {
        private static final String PREFIX = "legacy1:";
        private final SharedPreferences prefs;
        private final String keyName;

        LegacyBox(SharedPreferences prefs, String alias) {
            this.prefs = prefs;
            this.keyName = alias + "_legacy_aes";
            ensureKey();
        }

        @Override
        public String encrypt(String value) throws Exception {
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        }

        @Override
        public String decrypt(String stored) throws Exception {
            if (stored == null || !stored.startsWith(PREFIX) || !stored.contains(":")) {
                return null;
            }
            String encoded = stored.substring(PREFIX.length());
            String[] parts = encoded.split(":", 2);
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new IvParameterSpec(iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }

        private void ensureKey() {
            if (prefs.getString(keyName, "").length() > 0) {
                return;
            }
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            prefs.edit().putString(keyName, Base64.encodeToString(key, Base64.NO_WRAP)).apply();
        }

        private SecretKeySpec key() {
            byte[] key = Base64.decode(prefs.getString(keyName, ""), Base64.NO_WRAP);
            return new SecretKeySpec(key, "AES");
        }
    }
}
