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

import jakarta.servlet.MultipartConfigElement;
import net.william278.schematicupload.SchematicUpload;
import org.bukkit.Bukkit;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Objects;
import java.util.logging.Level;

import static net.william278.schematicupload.command.DownloadCommand.DOWNLOAD_DIRECTORY;

public class WebServer {

    private final SchematicUpload plugin;
    private Server jettyServer;

    private WebServer(@NotNull SchematicUpload plugin) {
        this.plugin = plugin;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final int maxThreads = 32;
            final int minThreads = 8;
            final int idleTimeout = 120;
            final int port = plugin.getSettings().getWebServerSettings().getPort();
            final QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, minThreads, idleTimeout);

            plugin.log(Level.INFO, "Starting the internal webserver on port " + port);
            jettyServer = new Server(threadPool);
            try (ServerConnector connector = new ServerConnector(jettyServer)) {
                connector.setPort(port);
                jettyServer.setConnectors(new Connector[]{connector});
            }
            initialize();
        });
    }

    // Copy files from the classpath resources folder to the plugin data folder
    public void copyWebFiles(final String source, final Path target) throws URISyntaxException, IOException {
        final URI resource = Objects.requireNonNull(getClass().getResource("")).toURI();
        try (FileSystem fileSystem = FileSystems.newFileSystem(resource, Collections.<String, String>emptyMap())) {
            final Path jarPath = fileSystem.getPath(source);
            Files.walkFileTree(jarPath, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path currentTarget = target.resolve(jarPath.relativize(dir).toString());
                    Files.createDirectories(currentTarget);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, target.resolve(jarPath.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }

            });
        }
    }

    // Initialize the webserver
    private void initialize() {
        try {
            // Copy web resources if needed
            final File targetDir = new File(plugin.getDataFolder(), "web");
            if (!targetDir.exists()) {
                plugin.getLogger().log(Level.INFO, "Generating files for the webserver...");
                if (!targetDir.mkdirs()) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to create web directory");
                    return;
                }
                copyWebFiles("/web", Paths.get(targetDir.getPath()));

                // Create a file in the /web folder with the current version
                final File versionFile = new File(targetDir, "version.txt");
                if (!versionFile.exists()) {
                    if (!versionFile.createNewFile()) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to create version file");
                        return;
                    }
                }
                Files.write(versionFile.toPath(), plugin.getDescription().getVersion().getBytes());
            }

            // Clear web/download folder
            final File downloadDir = new File(targetDir, DOWNLOAD_DIRECTORY);
            if (downloadDir.exists()) {
                Files.walkFileTree(downloadDir.toPath(), new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return super.visitFile(file, attrs);
                    }
                });
            }

            // Create multipart upload handler directory
            final Path uploadTempDirectory = plugin.getSchematicDirectory().resolve(".temp");
            if (uploadTempDirectory.toFile().mkdirs()) {
                plugin.getLogger().log(Level.INFO, "Prepared temporary upload folder for the webserver...");
            }

            // Upload size limits
            final long maxFileSize = 20 * 1024 * 1024; // 20 MB
            final long maxRequestSize = 20 * 1024 * 1024; // 20 MB
            final int fileSizeThreshold = 64; // 64 bytes

            // Create servlet context handler - combines servlets and static file serving
            final ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
            contextHandler.setContextPath("/");
            contextHandler.setResourceBase(targetDir.getPath());
            contextHandler.setWelcomeFiles(new String[]{"index.html"});

            // Create multipart upload handler
            final MultipartConfigElement multipartConfig = new MultipartConfigElement(uploadTempDirectory.toString(),
                    maxFileSize, maxRequestSize, fileSizeThreshold);
            final FileUploadServlet saveUploadServlet = new FileUploadServlet(plugin);
            final ServletHolder servletHolder = new ServletHolder(saveUploadServlet);
            servletHolder.getRegistration().setMultipartConfig(multipartConfig);

            // Create schematic list/download handler
            final SchematicListServlet listServlet = new SchematicListServlet(plugin);
            final ServletHolder listServletHolder = new ServletHolder(listServlet);

            // Create page routing handler for /list and /upload
            final PageRoutingServlet pageRouter = new PageRoutingServlet(plugin);
            final ServletHolder pageRouterHolder = new ServletHolder(pageRouter);

            // Register servlets - specific routes first
            contextHandler.addServlet(servletHolder, "/api");
            contextHandler.addServlet(listServletHolder, "/api/list/*");
            contextHandler.addServlet(pageRouterHolder, "/list");
            contextHandler.addServlet(pageRouterHolder, "/list/");
            contextHandler.addServlet(pageRouterHolder, "/upload");
            contextHandler.addServlet(pageRouterHolder, "/upload/");

            // Add default servlet for static files - must be last to catch unmatched requests
            final ServletHolder defaultHolder = new ServletHolder("default", DefaultServlet.class);
            defaultHolder.setInitParameter("resourceBase", targetDir.getPath());
            defaultHolder.setInitParameter("dirAllowed", "true");
            defaultHolder.setInitParameter("welcomeServlets", "false");
            defaultHolder.setInitParameter("redirectWelcome", "false");
            contextHandler.addServlet(defaultHolder, "/");

            // Set handler, start server
            HandlerList handlers = new HandlerList();
            handlers.setHandlers(new Handler[]{contextHandler});
            jettyServer.setHandler(handlers);
            jettyServer.start();
            jettyServer.join();
        } catch (Throwable e) {
            plugin.log(Level.SEVERE, "Failed to start the internal webserver.", e);
        }
    }

    // Gracefully terminate the webserver
    public void end() {
        try {
            plugin.log(Level.INFO, "Shutting down the internal webserver.");
            jettyServer.stop();
        } catch (Throwable e) {
            plugin.log(Level.SEVERE, "Failed to gracefully shutdown the internal webserver.", e);
        }
    }


    // Create a new WebServer and start it on the port
    @NotNull
    public static WebServer createAndStart(@NotNull SchematicUpload plugin) {
        return new WebServer(plugin);
    }

}
