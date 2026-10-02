package com.homes.zipsai.common.service;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

public final class PdfInspector {

    private PdfInspector() {
    }


    public static boolean isEncrypted(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            return document.isEncrypted();
        } catch (InvalidPasswordException exception) {
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}
