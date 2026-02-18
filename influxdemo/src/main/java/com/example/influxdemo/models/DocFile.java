package com.example.influxdemo.models;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class DocFile {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String originalName;
    private String contentType;      // pdf/docx/txt mime
    private String storedFilename;   // name on disk

    // Scope matching
    private String scope;            // "GLOBAL" or "AREA"
    private String ambientArea;      // only used if scope=AREA

    private Instant uploadedAt;

    @Lob
    private String extractedText;    // extracted content (Phase 1)

    protected DocFile() {}

    public DocFile(String originalName, String contentType, String storedFilename,
                        String scope, String ambientArea, String extractedText) {
        this.originalName = originalName;
        this.contentType = contentType;
        this.storedFilename = storedFilename;
        this.scope = scope;
        this.ambientArea = ambientArea;
        this.extractedText = extractedText;
        this.uploadedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getOriginalName() { return originalName; }
    public String getContentType() { return contentType; }
    public String getStoredFilename() { return storedFilename; }
    public String getScope() { return scope; }
    public String getAmbientArea() { return ambientArea; }
    public Instant getUploadedAt() { return uploadedAt; }
    public String getExtractedText() { return extractedText; }
}
