package com.homes.zipsai.common.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

public final class PdfFixtures {

    private PdfFixtures() {
    }

    public static byte[] plainPdf() {
        return pdf(null, null);
    }

    public static byte[] userPasswordPdf() {
        return pdf("owner-secret", "user-secret");
    }

    public static byte[] ownerPasswordOnlyPdf() {
        return pdf("owner-secret", "");
    }

    private static byte[] pdf(String ownerPassword, String userPassword) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            if (ownerPassword != null) {
                AccessPermission permission = new AccessPermission();
                permission.setCanExtractContent(false);
                StandardProtectionPolicy policy =
                    new StandardProtectionPolicy(ownerPassword, userPassword, permission);
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
