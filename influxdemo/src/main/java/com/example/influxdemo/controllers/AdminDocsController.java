package com.example.influxdemo.controllers;

import com.example.influxdemo.models.DocFile;
import com.example.influxdemo.services.DocsService;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Controller
public class AdminDocsController {

    private final DocsService docs;

    public AdminDocsController(DocsService docs) {
        this.docs = docs;
    }

    @GetMapping("/admin/docs")
    public String docsPage(@RequestParam(required = false) String area, Model model) {

        List<DocFile> list = docs.listAllDocs();

        model.addAttribute("active", "docs");
        model.addAttribute("docs", list);
        model.addAttribute("selectedArea", area == null ? "" : area);

        return "admin-docs";
    }

    @PostMapping("/admin/docs/upload")
    public String upload(
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "GLOBAL") String scope,
            @RequestParam(required = false) String ambientArea
    ) throws Exception {

        docs.saveUpload(file, scope, ambientArea);
        return "redirect:/admin/docs";
    }

    @GetMapping("/admin/docs/file/{id}")
    public ResponseEntity<Resource> view(@PathVariable Long id) {

        DocFile meta = docs.getMeta(id);
        Resource file = docs.getFile(id);

        MediaType mt;
        try {
            mt = meta.getContentType() == null
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(meta.getContentType());
        } catch (Exception e) {
            mt = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(mt)
                .header("Content-Disposition", "inline; filename=\"" + meta.getOriginalName() + "\"")
                .body(file);
    }
}
