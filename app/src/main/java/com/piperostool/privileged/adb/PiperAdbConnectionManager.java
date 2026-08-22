package com.piperostool.privileged.adb;

import android.content.Context;
import android.os.Build;
import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/** App-private ADB identity used only by the PiperOS privileged backend. */
public final class PiperAdbConnectionManager extends AbsAdbConnectionManager {
    private static volatile PiperAdbConnectionManager instance;

    public static PiperAdbConnectionManager getInstance(@NonNull Context context) throws Exception {
        PiperAdbConnectionManager current = instance;
        if (current == null) {
            synchronized (PiperAdbConnectionManager.class) {
                current = instance;
                if (current == null) {
                    current = new PiperAdbConnectionManager(context.getApplicationContext());
                    instance = current;
                }
            }
        }
        return current;
    }

    private final PrivateKey privateKey;
    private final Certificate certificate;

    private PiperAdbConnectionManager(@NonNull Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setTimeout(12, TimeUnit.SECONDS);
        File identityDir = new File(context.getFilesDir(), "piperos/adb");
        if (!identityDir.exists() && !identityDir.mkdirs()) {
            throw new IllegalStateException("Cannot create PiperOS ADB identity directory");
        }
        PrivateKey storedKey = readPrivateKey(new File(identityDir, "private.key"));
        Certificate storedCertificate = readCertificate(new File(identityDir, "certificate.pem"));
        if (storedKey == null || storedCertificate == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair pair = generator.generateKeyPair();
            storedKey = pair.getPrivate();
            storedCertificate = generateCertificate(pair.getPublic(), storedKey);
            writePrivateKey(new File(identityDir, "private.key"), storedKey);
            writeCertificate(new File(identityDir, "certificate.pem"), storedCertificate);
        }
        privateKey = storedKey;
        certificate = storedCertificate;
    }

    @NonNull @Override protected PrivateKey getPrivateKey() { return privateKey; }
    @NonNull @Override protected Certificate getCertificate() { return certificate; }
    @NonNull @Override protected String getDeviceName() { return "PiperOS Tool"; }

    private static Certificate generateCertificate(PublicKey publicKey, PrivateKey privateKey) throws Exception {
        String algorithm = "SHA512withRSA";
        Date notBefore = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1));
        Date notAfter = new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650));
        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(
                new KeyIdentifier(publicKey).getIdentifier()));
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
        X500Name name = new X500Name("CN=PiperOS Tool");
        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("extensions", extensions);
        X509CertImpl result = new X509CertImpl(info);
        result.sign(privateKey, algorithm);
        return result;
    }

    @Nullable private static PrivateKey readPrivateKey(File file) {
        if (!file.isFile()) return null;
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception ignored) { return null; }
    }

    @Nullable private static Certificate readCertificate(File file) {
        if (!file.isFile()) return null;
        try (InputStream input = new FileInputStream(file)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(input);
        } catch (Exception ignored) { return null; }
    }

    private static void writePrivateKey(File file, PrivateKey key) throws Exception {
        try (OutputStream output = new FileOutputStream(file)) { output.write(key.getEncoded()); }
    }

    private static void writeCertificate(File file, Certificate value) throws Exception {
        BASE64Encoder encoder = new BASE64Encoder();
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            encoder.encode(value.getEncoded(), output);
            output.write('\n');
            output.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }
}
