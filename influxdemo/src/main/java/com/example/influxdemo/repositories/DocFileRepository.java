package com.example.influxdemo.repositories;

import com.example.influxdemo.models.DocFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocFileRepository extends JpaRepository<DocFile, Long> {
    List<DocFile> findByScopeOrderByUploadedAtDesc(String scope);
    List<DocFile> findByScopeAndAmbientAreaOrderByUploadedAtDesc(String scope, String ambientArea);
    List<DocFile> findAllByOrderByUploadedAtDesc();
}
