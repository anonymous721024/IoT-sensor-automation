package com.example.influxdemo.services;

import com.example.influxdemo.models.DocFile;
import com.example.influxdemo.repositories.DocFileRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Service
public class DocsService {

    private final DocFileRepository repo;

    @Value("${docs.storage.dir}")
    private String storageDir;

    public DocsService(DocFileRepository repo) {
        this.repo = repo;
    }

    public DocFile saveUpload(MultipartFile file, String scope, String ambientArea) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required.");
        }

        String original = Optional.ofNullable(file.getOriginalFilename()).orElse("upload");
        String contentType = Optional.ofNullable(file.getContentType()).orElse("application/octet-stream");

        Files.createDirectories(Paths.get(storageDir));

        String stored = System.currentTimeMillis() + "_" + original.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path target = Paths.get(storageDir).resolve(stored);

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        String extracted = extractText(target, original, contentType);

        // normalize scope
        String sc = (scope == null || scope.isBlank()) ? "GLOBAL" : scope.trim().toUpperCase();
        String area = (ambientArea == null) ? "" : ambientArea.trim().toUpperCase();

        if ("AREA".equals(sc) && area.isBlank()) {
            throw new IllegalArgumentException("ambientArea is required when scope=AREA");
        }

        DocFile doc = new DocFile(original, contentType, stored, sc, area, extracted);
        return repo.save(doc);
    }

    public List<DocFile> listDocsFor(String ambientArea) {
        List<DocFile> global = repo.findByScopeOrderByUploadedAtDesc("GLOBAL");

        if (ambientArea == null || ambientArea.isBlank()) return global;

        String a = ambientArea.trim().toUpperCase();
        List<DocFile> area = repo.findByScopeAndAmbientAreaOrderByUploadedAtDesc("AREA", a);
        ArrayList<DocFile> merged = new ArrayList<>();
        merged.addAll(area);
        merged.addAll(global);
        return merged;
    }

    public Resource getFile(Long id) {
        DocFile doc = repo.findById(id).orElseThrow();
        Path p = Paths.get(storageDir).resolve(doc.getStoredFilename());
        return new FileSystemResource(p.toFile());
    }

    public DocFile getMeta(Long id) {
        return repo.findById(id).orElseThrow();
    }

    private String extractText(Path path, String originalName, String contentType) {
        String lower = originalName.toLowerCase();

        try {
            if (lower.endsWith(".txt")) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }

            if (lower.endsWith(".docx")) {
                try (InputStream in = Files.newInputStream(path);
                     XWPFDocument doc = new XWPFDocument(in);
                     XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
                    return ex.getText();
                }
            }

            if (lower.endsWith(".pdf")) {
                try (PDDocument pdf = PDDocument.load(path.toFile())) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    return stripper.getText(pdf);
                }
            }

            // Phase 1: unsupported types become empty text but still storable/viewable
            return "";
        } catch (Exception e) {
            // never break upload because extraction failed
            return "";
        }
    }

    public List<DocFile> listAllDocs() {
        return repo.findAllByOrderByUploadedAtDesc();
    }
}
