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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class PageRoutingServlet extends HttpServlet {
    
    private final SchematicUpload plugin;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        final String requestPath = request.getRequestURI();
        final String pathInfo = request.getPathInfo();
        
        // Handle both /list and /list/ (and same for /upload)
        String htmlFile = null;
        if (requestPath != null) {
            if (requestPath.equals("/list") || requestPath.equals("/list/")) {
                htmlFile = "list.html";
            } else if (requestPath.equals("/upload") || requestPath.equals("/upload/")) {
                htmlFile = "upload.html";
            }
        }
        
        // Also check pathInfo as fallback
        if (htmlFile == null && pathInfo != null) {
            if (pathInfo.equals("/list") || pathInfo.equals("/list/") || pathInfo.equals("")) {
                htmlFile = "list.html";
            } else if (pathInfo.equals("/upload") || pathInfo.equals("/upload/")) {
                htmlFile = "upload.html";
            }
        }
        
        if (htmlFile == null) {
            response.setStatus(404);
            return;
        }
        
        try {
            final File webDir = new File(plugin.getDataFolder(), "web");
            final Path htmlPath = webDir.toPath().resolve(htmlFile);
            
            if (!Files.exists(htmlPath) || !Files.isRegularFile(htmlPath)) {
                response.setStatus(404);
                response.getWriter().println("Page not found");
                return;
            }
            
            // Set content type and serve the file
            response.setContentType("text/html; charset=UTF-8");
            Files.copy(htmlPath, response.getOutputStream());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to serve page: " + htmlFile, e);
            response.setStatus(500);
            response.getWriter().println("Internal server error");
        }
    }
}

