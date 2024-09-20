/*
 * Copyright 2024 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.dotnet;

import lombok.*;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.quark.Quark;
import org.openrewrite.scheduling.WorkingDirectoryExecutionContextView;
import org.openrewrite.text.PlainText;
import org.openrewrite.tree.ParseError;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Value
@EqualsAndHashCode(callSuper = true)
public class UpgradeAssistant extends ScanningRecipe<UpgradeAssistant.Accumulator> {
    private static final String FIRST_RECIPE = UpgradeAssistant.class.getName() + ".FIRST_RECIPE";
    private static final String PREVIOUS_RECIPE = UpgradeAssistant.class.getName() + ".PREVIOUS_RECIPE";
    private static final String INIT_REPO_DIR = UpgradeAssistant.class.getName() + ".INIT_REPO_DIR";
    private static final String UPGRADE_ASSISTANT = "upgrade-assistant";
    private static final String DOTNET_HOME = System.getProperty("user.home") + File.separator + ".dotnet";

    @Option(
            displayName = "Target framework version",
            description = "Target framework to which source project should be upgraded.",
            example = "net9.0")
    @Nullable
    String targetFramework;

    @Override
    public String getDisplayName() {
        return "Upgrade a .NET project using upgrade-assistant";
    }

    @Override
    public String getDescription() {
        return "Run [upgrade-assistant](https://learn.microsoft.com/en-us/dotnet/core/porting/upgrade-assistant-overview) " +
                "across a project to upgrade it to a newer .NET framework version.";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        Path directory = createDirectory(ctx);
        if (ctx.getMessage(INIT_REPO_DIR) == null) {
            ctx.putMessage(INIT_REPO_DIR, directory);
            ctx.putMessage(FIRST_RECIPE, ctx.getCycleDetails().getRecipePosition());
        }
        return new Accumulator(directory);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile &&
                        !(tree instanceof Quark) &&
                        !(tree instanceof ParseError) &&
                        !tree.getClass().getName().equals("org.openrewrite.java.tree.J$CompilationUnit")) {
                    SourceFile sourceFile = (SourceFile) tree;
                    String fileName = sourceFile.getSourcePath().getFileName().toString();
                    if (fileName.indexOf('.') > 0) {
                        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
                        acc.extensionCounts.computeIfAbsent(extension, e -> new AtomicInteger(0)).incrementAndGet();
                    }

                    // only extract initial source files for first upgrade-assistant recipe
                    if (Objects.equals(ctx.getMessage(FIRST_RECIPE), ctx.getCycleDetails().getRecipePosition())) {
                        acc.writeSource(sourceFile);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        Path previous = ctx.getMessage(PREVIOUS_RECIPE);
        if (previous != null &&
                !Objects.equals(ctx.getMessage(FIRST_RECIPE), ctx.getCycleDetails().getRecipePosition())) {
            acc.copyFromPrevious(previous);
        }

        runUpgradeAssistant(acc, ctx);
        ctx.putMessage(PREVIOUS_RECIPE, acc.getDirectory());

        // FIXME check for generated files
        return Collections.emptyList();
    }

    private void runUpgradeAssistant(Accumulator acc, ExecutionContext ctx) {
        Map<String, String> env = buildUpgradeAssistantEnv();

        for (Path projectFile : acc.getProjectFiles()) {
            List<String> command = buildUpgradeAssistantCommand(projectFile);
            Path out = null;
            Path err = null;

            try {
                ProcessBuilder builder = new ProcessBuilder();
                builder.command(command);
                builder.directory(acc.getDirectory().toFile());
                env.forEach(builder.environment()::put);

                out = Files.createTempFile(
                        WorkingDirectoryExecutionContextView.view(ctx).getWorkingDirectory(),
                        UPGRADE_ASSISTANT,
                        null);
                err = Files.createTempFile(
                        WorkingDirectoryExecutionContextView.view(ctx).getWorkingDirectory(),
                        UPGRADE_ASSISTANT,
                        null);
                builder.redirectOutput(ProcessBuilder.Redirect.to(out.toFile()));
                builder.redirectError(ProcessBuilder.Redirect.to(err.toFile()));

                Process process = builder.start();
                process.waitFor(20, TimeUnit.MINUTES);
                if (process.exitValue() != 0) {
                    String error = "Command failed: " + String.join(" ", command);
                    if (Files.exists(err)) {
                        error += "\n" + new String(Files.readAllBytes(err));
                    }
                    throw new RuntimeException(error);
                } else {
                    for (Map.Entry<Path, Long> entry : acc.beforeModificationTimestamps.entrySet()) {
                        Path path = entry.getKey();
                        if (!Files.exists(path) || Files.getLastModifiedTime(path).toMillis() > entry.getValue()) {
                            acc.modified(path);
                        }
                    }
                    processOutput(projectFile.getParent(), out, acc);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                deleteFile(out);
                deleteFile(err);
            }
        }
    }

    private Map<String, String> buildUpgradeAssistantEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("TERM", "dumb"); // FIXME is this node specific?
        String path = System.getenv("PATH");
        // This is required to find .NET SDKs
        env.put("PATH", path + File.pathSeparator + DOTNET_HOME);
        return env;
    }

    private void deleteFile(@Nullable Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // FIXME recipe logger?
            }
        }
    }

    private List<String> buildUpgradeAssistantCommand(Path projectFile) {
        List<String> command = new ArrayList<>();
        command.add(getUpgradeAssistantPath().toString());
        command.add("upgrade");
        command.add(projectFile.toString());
        command.add("--non-interactive");
        command.add("--operation");
        command.add("Inplace");
        if (targetFramework != null) {
            command.add("--targetFramework");
            command.add(targetFramework);
        }
        return command;
    }

    private Path getUpgradeAssistantPath() {
        String cmdName = UPGRADE_ASSISTANT;
        if (System.getProperty("os.name").contains("Windows")) {
            cmdName += ".exe";
        }

        // Look for upgrade-assistant in conventional installation locations
        Path cmdPath = Paths.get(DOTNET_HOME).resolve("tools").resolve(cmdName);
        if (Files.exists(cmdPath)) {
            return cmdPath;
        }

        for (String path : System.getenv("PATH").split(File.pathSeparator)) {
            cmdPath = Paths.get(path).resolve(cmdName);
            if (Files.exists(cmdPath)) {
                return cmdPath;
            }
        }

        throw new IllegalStateException("Unable to find " + cmdName + " on PATH");
    }

    private void processOutput(Path projectDir, Path output, Accumulator acc) {
        try (BufferedReader reader = Files.newBufferedReader(output)) {
            String fileName = null;
            List<String> transformerLogs = new ArrayList<>();

            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String[] parts = line.split("\\s+");
                if (parts.length == 0) {
                    continue;
                }
                String token = parts[0];
                if (token.startsWith("file.")) {
                    fileName = parts[1].replace("...", "");
                } else if (fileName != null) {
                    if ("Succeeded".equals(token) || "Skipped".equals(token) || "Failed".equals(token)) {
                        if ("Failed".equals(token)) {
                            acc.addFileErrors(projectDir.resolve(fileName), transformerLogs);
                        }
                        fileName = null;
                        transformerLogs.clear();
                    } else {
                        transformerLogs.add(line.replaceFirst("\\s+", ""));
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    return createAfter(sourceFile, acc);
                }
                return tree;
            }
        };
    }

    private SourceFile createAfter(SourceFile before, Accumulator acc) {
        if (!acc.wasModified(before)) {
            return before;
        }

        String content;
        String beforeContent = acc.content(before);
        List<PlainText.Snippet> snippets = new ArrayList<>();

        List<String> errors = acc.getFileErrors(acc.resolvedPath(before));
        if (errors != null) {
            content = "";
            PlainText.Snippet snippet = new PlainText.Snippet(Tree.randomId(), Markers.EMPTY, "");
            snippet = snippet.withText(beforeContent.substring(0, beforeContent.indexOf('\n')));
            String errorText = String.join("\n", errors);
            Marker marker = new SearchResult(Tree.randomId(), errorText);
            snippet = snippet.withMarkers(snippet.getMarkers().add(marker));
            snippets.add(snippet);
        } else {
            content = beforeContent;
        }

        return new PlainText(
                before.getId(),
                before.getSourcePath(),
                before.getMarkers(),
                Optional.ofNullable(before.getCharset()).map(Charset::name).orElse(null),
                before.isCharsetBomMarked(),
                before.getFileAttributes(),
                null,
                content,
                snippets);
    }

    private static Path createDirectory(ExecutionContext ctx) {
        WorkingDirectoryExecutionContextView view = WorkingDirectoryExecutionContextView.view(ctx);
        return Optional.of(view.getWorkingDirectory()).map(d -> d.resolve("repo")).map(d -> {
            try {
                return Files.createDirectory(d).toRealPath();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).orElseThrow(() -> new IllegalStateException("Failed to create working directory for repo"));
    }

    @ToString
    @EqualsAndHashCode
    @RequiredArgsConstructor
    public static class Accumulator {
        @Getter
        private final Path directory;
        private final Map<Path, List<String>> fileErrors = new HashMap<>();
        final Map<Path, Long> beforeModificationTimestamps = new HashMap<>();
        final Set<Path> modified = new LinkedHashSet<>();
        final Map<String, AtomicInteger> extensionCounts = new HashMap<>();
        @Getter
        private final List<Path> projectFiles = new ArrayList<>();

        private void copyFromPrevious(Path previous) {
            try {
                Files.walkFileTree(previous, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Path target = directory.resolve(previous.relativize(dir));
                        if (!target.equals(directory)) {
                            Files.createDirectory(target);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        try {
                            Path target = directory.resolve(previous.relativize(file));
                            Files.copy(file, target);
                            beforeModificationTimestamps.put(target, Files.getLastModifiedTime(target).toMillis());
                            if (isProjectFile(target)) {
                                projectFiles.add(target);
                            }
                        } catch (NoSuchFileException ignore) {
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void writeSource(SourceFile sourceFile) {
            try {
                Path path = resolvedPath(sourceFile);
                Files.createDirectories(path.getParent());
                PrintOutputCapture.MarkerPrinter markerPrinter = new PrintOutputCapture.MarkerPrinter() {
                };
                Path written = Files.write(
                        path,
                        sourceFile.printAll(new PrintOutputCapture<>(0, markerPrinter))
                                .getBytes(Optional.ofNullable(sourceFile.getCharset()).orElse(StandardCharsets.UTF_8)));
                beforeModificationTimestamps.put(written, Files.getLastModifiedTime(written).toMillis());
                if (isProjectFile(written)) {
                    projectFiles.add(written);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private boolean isProjectFile(Path path) {
            String pathString = path.toString();
            return pathString.endsWith(".csproj") || pathString.endsWith(".vbproj");
        }

        private void modified(Path path) {
            modified.add(path);
        }

        private boolean wasModified(SourceFile tree) {
            return modified.contains(resolvedPath(tree));
        }

        private String content(SourceFile tree) {
            try {
                Path path = resolvedPath(tree);
                return tree.getCharset() != null ? new String(Files.readAllBytes(path), tree.getCharset()) : new String(
                        Files.readAllBytes(path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private Path resolvedPath(SourceFile tree) {
            return directory.resolve(tree.getSourcePath());
        }

        private void addFileErrors(Path path, List<String> errors) {
            fileErrors.put(path, new ArrayList<>(errors));
        }

        @Nullable private List<String> getFileErrors(Path path) {
            return fileErrors.get(path);
        }
    }
}
