import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.HashMLDSASigner;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;

public class Encryption {

    private static final MLKEMParameters ML_KEM_PARAMETER_SET = MLKEMParameters.ml_kem_1024;
    private static final MLDSAParameters ML_DSA_PARAMETER_SET = MLDSAParameters.ml_dsa_87_with_sha512;

    private static final String AES_ALGORITHM = "AES";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_KEY_SIZE_BITS = 256;
    private static final int AES_KEY_SIZE_BYTES = AES_KEY_SIZE_BITS / 8;
    private static final int GCM_NONCE_SIZE_BYTES = 12;
    private static final int GCM_TAG_SIZE_BITS = 128;

    private final MLKEMPublicKeyParameters mlKemPublicKey;
    private final MLKEMPrivateKeyParameters mlKemPrivateKey;
    private final MLDSAPublicKeyParameters mlDsaPublicKey;
    private final MLDSAPrivateKeyParameters mlDsaPrivateKey;
    private final SecureRandom secureRandom;

    public static final class EncapsulationResult {
        private final String encapsulation;
        private final SecretKey sessionKey;

        public EncapsulationResult(String encapsulation, SecretKey sessionKey) {
            this.encapsulation = encapsulation;
            this.sessionKey = sessionKey;
        }

        public String getEncapsulation() {
            return encapsulation;
        }

        public SecretKey getSessionKey() {
            return sessionKey;
        }
    }

    public Encryption() throws GeneralSecurityException {
        this.secureRandom = new SecureRandom();
        AsymmetricCipherKeyPair mlKemKeyPair = generateMlKemKeyPair();
        this.mlKemPublicKey = (MLKEMPublicKeyParameters) mlKemKeyPair.getPublic();
        this.mlKemPrivateKey = (MLKEMPrivateKeyParameters) mlKemKeyPair.getPrivate();

        AsymmetricCipherKeyPair mlDsaKeyPair = generateMlDsaKeyPair();
        this.mlDsaPublicKey = (MLDSAPublicKeyParameters) mlDsaKeyPair.getPublic();
        this.mlDsaPrivateKey = (MLDSAPrivateKeyParameters) mlDsaKeyPair.getPrivate();
    }

    public String getEncodedKemPublicKey() {
        return Base64.getEncoder().encodeToString(mlKemPublicKey.getEncoded());
    }

    public String getEncodedKemPrivateKey() {
        return Base64.getEncoder().encodeToString(mlKemPrivateKey.getEncoded());
    }

    public String getEncodedSigningPublicKey() {
        return Base64.getEncoder().encodeToString(mlDsaPublicKey.getEncoded());
    }

    public String getEncodedSigningPrivateKey() {
        return Base64.getEncoder().encodeToString(mlDsaPrivateKey.getEncoded());
    }

    public static SecretKey generateAesKey() throws GeneralSecurityException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(AES_ALGORITHM);
        keyGenerator.init(AES_KEY_SIZE_BITS);
        return keyGenerator.generateKey();
    }

    public static String encodeSecretKey(SecretKey secretKey) {
        return Base64.getEncoder().encodeToString(secretKey.getEncoded());
    }

    public EncapsulationResult encapsulateSessionKey(String recipientKemPublicKeyEncoded)
            throws GeneralSecurityException {

        MLKEMPublicKeyParameters recipientPublicKey = decodeKemPublicKey(recipientKemPublicKeyEncoded);
        MLKEMGenerator generator = new MLKEMGenerator(secureRandom);
        SecretWithEncapsulation encapsulated = generator.generateEncapsulated(recipientPublicKey);

        byte[] sharedSecret = encapsulated.getSecret();
        byte[] kemCiphertext = encapsulated.getEncapsulation();
        SecretKey sessionKey = deriveAesKeyFromSharedSecret(sharedSecret);

        return new EncapsulationResult(
                Base64.getEncoder().encodeToString(kemCiphertext),
                sessionKey);
    }

    public SecretKey decapsulateSessionKey(String encapsulationEncoded) throws GeneralSecurityException {
        byte[] encapsulation = Base64.getDecoder().decode(encapsulationEncoded);
        MLKEMExtractor extractor = new MLKEMExtractor(mlKemPrivateKey);
        byte[] sharedSecret = extractor.extractSecret(encapsulation);
        return deriveAesKeyFromSharedSecret(sharedSecret);
    }

    public String sign(String message) throws GeneralSecurityException {
        HashMLDSASigner signer = new HashMLDSASigner();
        signer.init(true, mlDsaPrivateKey);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        signer.update(messageBytes, 0, messageBytes.length);

        try {
            byte[] signature = signer.generateSignature();
            return Base64.getEncoder().encodeToString(signature);
        } catch (CryptoException ex) {
            throw new GeneralSecurityException("Failed to generate ML-DSA signature.", ex);
        }
    }

    public boolean verify(String message, String signatureEncoded, String signerPublicKeyEncoded)
            throws GeneralSecurityException {

        MLDSAPublicKeyParameters publicKey = decodeSigningPublicKey(signerPublicKeyEncoded);
        byte[] signature = Base64.getDecoder().decode(signatureEncoded);

        HashMLDSASigner verifier = new HashMLDSASigner();
        verifier.init(false, publicKey);
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        verifier.update(messageBytes, 0, messageBytes.length);
        return verifier.verifySignature(signature);
    }

    public String encryptMessage(SecretKey sessionKey, String plaintext) throws GeneralSecurityException {
        byte[] nonce = new byte[GCM_NONCE_SIZE_BYTES];
        secureRandom.nextBytes(nonce);

        Cipher aesCipher = Cipher.getInstance(AES_TRANSFORMATION);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_SIZE_BITS, nonce);
        aesCipher.init(Cipher.ENCRYPT_MODE, sessionKey, gcmSpec);

        byte[] cipherBytes = aesCipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        String encodedNonce = Base64.getEncoder().encodeToString(nonce);
        String encodedCipher = Base64.getEncoder().encodeToString(cipherBytes);
        return encodedNonce + ":" + encodedCipher;
    }

    public String decryptMessage(SecretKey sessionKey, String encryptedEnvelope) throws GeneralSecurityException {
        String[] parts = encryptedEnvelope.split(":", 2);
        if (parts.length != 2) {
            throw new GeneralSecurityException("Invalid encrypted message format.");
        }

        byte[] nonce = Base64.getDecoder().decode(parts[0]);
        byte[] cipherBytes = Base64.getDecoder().decode(parts[1]);

        Cipher aesCipher = Cipher.getInstance(AES_TRANSFORMATION);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_SIZE_BITS, nonce);
        aesCipher.init(Cipher.DECRYPT_MODE, sessionKey, gcmSpec);

        byte[] plainBytes = aesCipher.doFinal(cipherBytes);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    private static MLKEMPublicKeyParameters decodeKemPublicKey(String encodedPublicKey)
            throws GeneralSecurityException {

        byte[] keyBytes = decodeBase64(encodedPublicKey, "Invalid ML-KEM public key encoding.");
        return new MLKEMPublicKeyParameters(ML_KEM_PARAMETER_SET, keyBytes);
    }

    private static MLDSAPublicKeyParameters decodeSigningPublicKey(String encodedPublicKey)
            throws GeneralSecurityException {

        byte[] keyBytes = decodeBase64(encodedPublicKey, "Invalid ML-DSA public key encoding.");
        return new MLDSAPublicKeyParameters(ML_DSA_PARAMETER_SET, keyBytes);
    }

    private static byte[] decodeBase64(String encoded, String errorMessage) throws GeneralSecurityException {
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new GeneralSecurityException(errorMessage, ex);
        }
    }

    private static SecretKey deriveAesKeyFromSharedSecret(byte[] sharedSecret) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest(sharedSecret);
        byte[] keyBytes = Arrays.copyOf(hashed, AES_KEY_SIZE_BYTES);
        return new SecretKeySpec(keyBytes, AES_ALGORITHM);
    }

    private AsymmetricCipherKeyPair generateMlKemKeyPair() {
        MLKEMKeyPairGenerator keyPairGenerator = new MLKEMKeyPairGenerator();
        keyPairGenerator.init(new MLKEMKeyGenerationParameters(secureRandom, ML_KEM_PARAMETER_SET));
        return keyPairGenerator.generateKeyPair();
    }

    private AsymmetricCipherKeyPair generateMlDsaKeyPair() {
        MLDSAKeyPairGenerator keyPairGenerator = new MLDSAKeyPairGenerator();
        keyPairGenerator.init(new MLDSAKeyGenerationParameters(secureRandom, ML_DSA_PARAMETER_SET));
        return keyPairGenerator.generateKeyPair();
    }
}