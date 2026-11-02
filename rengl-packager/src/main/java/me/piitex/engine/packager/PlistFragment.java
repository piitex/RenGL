package me.piitex.engine.packager;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Info.plist entries the app supplies itself, for when what {@code --file-association} generates is not enough: an app
 * that wants to be offered for the system's own zip and tar types (Alternate rank, so it shows in Finder's Open With
 * without replacing Archive Utility) lists those types by UTI, which a bare extension cannot say.
 */
final class PlistFragment {
    private PlistFragment() {
    }

    /** Reads {@code file}; see {@link #documentTypes(String)}. */
    static String documentTypes(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("--document-types " + file + " cannot be read: " + e.getMessage());
        }
        try {
            return documentTypes(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--document-types " + file + ": " + e.getMessage());
        }
    }

    /**
     * The entries to put inside the plist's top-level {@code <dict>}. The text is either the {@code <array>} of document
     * types, which gets its {@code CFBundleDocumentTypes} key here, or entries that begin with a {@code <key>} (so the
     * file can add a {@code UTImportedTypeDeclarations} beside the types). It must be well-formed XML.
     */
    static String documentTypes(String text) {
        String entries = text.strip();
        if (entries.startsWith("﻿")) entries = entries.substring(1).strip();
        if (entries.isEmpty()) throw new IllegalArgumentException("the file is empty");
        if (entries.startsWith("<?xml") || entries.startsWith("<plist") || entries.startsWith("<!DOCTYPE")) {
            throw new IllegalArgumentException("give only the <array> of document types, or <key> entries, not a whole plist");
        }
        if (entries.startsWith("<array")) entries = "<key>CFBundleDocumentTypes</key>\n" + entries;
        else if (!entries.startsWith("<key>")) throw new IllegalArgumentException("it must start with <array> (the document types) or <key>");
        wellFormed(entries);
        if (!entries.contains("<key>CFBundleDocumentTypes</key>")) {
            throw new IllegalArgumentException("it has no CFBundleDocumentTypes entry");
        }
        return entries;
    }

    private static void wellFormed(String entries) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            builder.parse(new InputSource(new StringReader("<dict>" + entries + "</dict>")));
        } catch (SAXException e) {
            throw new IllegalArgumentException("it is not well-formed XML: " + e.getMessage());
        } catch (ParserConfigurationException | IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
