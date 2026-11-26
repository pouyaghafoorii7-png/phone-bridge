package com.phonebridge.android;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.asn1.x500.X500Name;

import java.math.BigInteger;

/**
 * Holds the RSA key and certificate the built-in ADB client authenticates with (the iTell way).
 * The key is made once and kept in the app's private files, so the phone trusts this app after the
 * first pairing. Written in Java to match the libadb-android example and its sun.security.x509 API.
 */
public class AdbManager extends AbsAdbConnectionManager {
    private static AbsAdbConnectionManager instance;

    public static AbsAdbConnectionManager getInstance(Context ctx) throws Exception {
        if (instance == null) instance = new AdbManager(ctx.getApplicationContext());
        return instance;
    }

    private final PrivateKey privateKey;
    private final Certificate certificate;

    private AdbManager(Context ctx) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        File keyFile = new File(ctx.getFilesDir(), "adb_key");
        File certFile = new File(ctx.getFilesDir(), "adb_cert.pem");

        if (keyFile.exists() && certFile.exists()) {
            privateKey = readKey(keyFile);
            certificate = readCert(certFile);
        } else {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair pair = gen.generateKeyPair();
            privateKey = pair.getPrivate();
            certificate = makeCertificate(pair.getPublic(), privateKey);
            writeKey(keyFile, privateKey);
            writeCert(certFile, certificate);
        }
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "Ghafoorii";
    }

    private static Certificate makeCertificate(PublicKey publicKey, PrivateKey privateKey) throws Exception {
        X500Name subject = new X500Name("CN=Ghafoorii");
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000); // 10 years
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        JcaX509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, publicKey);
        ContentSigner signer = new JcaContentSignerBuilder("SHA512withRSA").build(privateKey);
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static void writeKey(File f, PrivateKey key) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(key.getEncoded()); // PKCS#8
        }
    }

    private static PrivateKey readKey(File f) throws Exception {
        byte[] bytes = readAll(f);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private static void writeCert(File f, Certificate cert) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(cert.getEncoded());
        }
    }

    private static Certificate readCert(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] bytes = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return bytes;
    }
}
