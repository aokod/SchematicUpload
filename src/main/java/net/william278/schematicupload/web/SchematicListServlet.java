/*
 * This file is part of SchematicUpload, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.schematicupload.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import net.william278.schematicupload.SchematicUpload;
import org.eclipse.jetty.util.IO;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

import static net.william278.schematicupload.SchematicUpload.ALLOWED_EXTENSIONS;

@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class SchematicListServlet extends HttpServlet {
    
    private final SchematicUpload plugin;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        final String path = request.getPathInfo();
        
        if (path == null || path.equals("/") || path.equals("/list")) {
            // List schematics
            handleList(request, response);
        } else if (path.startsWith("/download/")) {
            // Download schematic
            handleDownload(request, response);
        } else {
            response.setStatus(404);
            response.getWriter().println("{\"error\":\"Not found\"}");
        }
    }

    private void handleList(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            final List<SchematicInfo> schematics = getSchematicList();
            
            response.setContentType("application/json");
            response.setStatus(200);
            
            final String json = "[" + schematics.stream()
                    .map(s -> String.format(
                            "{\"name\":\"%s\",\"size\":%d,\"encodedName\":\"%s\"}",
                            escapeJson(s.name),
                            s.size,
                            URLEncoder.encode(s.name, StandardCharsets.UTF_8)
                    ))
                    .collect(Collectors.joining(",")) + "]";
            
            response.getWriter().println(json);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to list schematics", e);
            response.setStatus(500);
            response.getWriter().println("{\"error\":\"Failed to list schematics\"}");
        }
    }

    private void handleDownload(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            final String fileName = request.getPathInfo().substring("/download/".length());
            final String decodedFileName = URLDecoder.decode(fileName, StandardCharsets.UTF_8);
            
            // Validate file name
            if (decodedFileName.contains("..") || decodedFileName.contains("/") || decodedFileName.contains("\\")) {
                response.setStatus(400);
                response.getWriter().println("{\"error\":\"Invalid file name\"}");
                return;
            }
            
            if (ALLOWED_EXTENSIONS.stream().noneMatch(decodedFileName::endsWith)) {
                response.setStatus(400);
                response.getWriter().println("{\"error\":\"Invalid file type\"}");
                return;
            }
            
            final Path schematicFile = plugin.getSchematicDirectory().resolve(decodedFileName);
            
            if (!Files.exists(schematicFile) || !Files.isRegularFile(schematicFile)) {
                response.setStatus(404);
                response.getWriter().println("{\"error\":\"File not found\"}");
                return;
            }
            
            // Set headers for file download
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", 
                    String.format("attachment; filename=\"%s\"", 
                            URLEncoder.encode(decodedFileName, StandardCharsets.UTF_8)));
            response.setContentLengthLong(Files.size(schematicFile));
            
            // Stream the file
            try (InputStream inputStream = Files.newInputStream(schematicFile);
                 OutputStream outputStream = response.getOutputStream()) {
                IO.copy(inputStream, outputStream);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to download schematic", e);
            response.setStatus(500);
            response.getWriter().println("{\"error\":\"Failed to download schematic\"}");
        }
    }

    @NotNull
    private List<SchematicInfo> getSchematicList() {
        final List<SchematicInfo> schematics = new ArrayList<>();
        final File schematicDir = plugin.getSchematicDirectory().toFile();
        
        if (!schematicDir.exists() || !schematicDir.isDirectory()) {
            return schematics;
        }
        
        final File[] files = schematicDir.listFiles((dir, name) -> 
                ALLOWED_EXTENSIONS.stream().anyMatch(name::endsWith));
        
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && !file.getName().startsWith(".")) {
                    schematics.add(new SchematicInfo(
                            file.getName(),
                            file.length()
                    ));
                }
            }
        }
        
        // Sort by name
        schematics.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        
        return schematics;
    }

    private String escapeJson(String str) {
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    private static class SchematicInfo {
        final String name;
        final long size;

        SchematicInfo(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }
}

