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

import com.fasterxml.jackson.databind.JsonNode;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
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

abstract class UpgradeAssistantRecipe extends ScanningRecipe<UpgradeAssistantRecipe.Accumulator> {
    private static final String FIRST_RECIPE = UpgradeAssistantRecipe.class.getName() + ".FIRST_RECIPE";
    private static final String PREVIOUS_RECIPE = UpgradeAssistantRecipe.class.getName() + ".PREVIOUS_RECIPE";
    private static final String INIT_REPO_DIR = UpgradeAssistantRecipe.class.getName() + ".INIT_REPO_DIR";
    private static final String UPGRADE_ASSISTANT = "upgrade-assistant";
    protected static final String DOTNET_HOME = System.getProperty("user.home") + File.separator + ".dotnet";

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
                if (tree instanceof SourceFile && !(tree instanceof Quark) && !(tree instanceof ParseError)) {
                    SourceFile sourceFile = (SourceFile) tree;

                    // Only extract initial source files for first upgrade-assistant recipe
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

        if (ctx.getCycle() == 1) {
            // upgrade-assistant run more than once on a project will log an "Unknown target framework" message
            runUpgradeAssistant(acc, ctx);
        }

        ctx.putMessage(PREVIOUS_RECIPE, acc.getDirectory());

        return Collections.emptyList();
    }

    abstract public void runUpgradeAssistant(Accumulator acc, ExecutionContext ctx);

    protected void execUpgradeAssistant(Path inputFile, Accumulator acc, ExecutionContext ctx) {
        List<String> command = buildUpgradeAssistantCommand(acc, ctx, inputFile);
        Path out = null;

        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command(command);
            builder.directory(acc.getDirectory().toFile());
            Map<String, String> env = buildUpgradeAssistantEnv();
            env.forEach(builder.environment()::put);

            out = Files.createTempFile(
                    WorkingDirectoryExecutionContextView.view(ctx).getWorkingDirectory(),
                    UPGRADE_ASSISTANT,
                    null);

            builder.redirectOutput(ProcessBuilder.Redirect.to(out.toFile()));
            builder.redirectError(ProcessBuilder.Redirect.to(out.toFile()));

            Process process = builder.start();
            process.waitFor(20, TimeUnit.MINUTES);

            // upgrade-assistent currently does not exit with non-zero on error nor does it
            // log errors to stderr. Here we look for known fatal errors in stdout suggesting
            // the command failed.
            if (Files.exists(out)) {
                try (BufferedReader reader = Files.newBufferedReader(out)) {
                    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                        if (line.startsWith("Project path does not exist")) {
                            throw new RuntimeException("upgrade-assistant: " + line);
                        }
                    }
                }
            }

            for (Map.Entry<Path, Long> entry : acc.beforeModificationTimestamps.entrySet()) {
                Path path = entry.getKey();
                if (!Files.exists(path) || Files.getLastModifiedTime(path).toMillis() > entry.getValue()) {
                    acc.addModifiedFile(path);
                }
            }
            processOutput(inputFile, out, acc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            deleteFile(out);
        }
    }

    private Map<String, String> buildUpgradeAssistantEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("TERM", "dumb");
        env.put("DOTNET_UPGRADEASSISTANT_TELEMETRY_OPTOUT", "1");
        env.put("DOTNET_UPGRADEASSISTANT_SKIP_FIRST_TIME_EXPERIENCE", "1");
        String path = System.getenv("PATH");
        // This is required to find .NET SDKs
        env.put("PATH", path + File.pathSeparator + DOTNET_HOME);
        return env;
    }

    protected void deleteFile(@Nullable Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Ignored
            }
        }
    }

    abstract public List<String> buildUpgradeAssistantCommand(Accumulator acc, ExecutionContext ctx, Path projectFile);

    protected Path getUpgradeAssistantPath() {
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

    abstract void processOutput(Path inputFile, Path output, Accumulator acc);

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    return createAfter(sourceFile, acc, ctx);
                }
                return tree;
            }
        };
    }

    protected SourceFile createAfter(SourceFile before, Accumulator acc, ExecutionContext ctx) {
        String error = acc.getFileError(acc.resolvedPath(before));
        if (error != null) {
            throw new RecipeException(error);
        }

        if (!acc.wasModified(before)) {
            return before;
        }

        return new PlainText(
                before.getId(),
                before.getSourcePath(),
                before.getMarkers(),
                Optional.ofNullable(before.getCharset()).map(Charset::name).orElse(null),
                before.isCharsetBomMarked(),
                before.getFileAttributes(),
                null,
                acc.content(before),
                Collections.emptyList());
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

        private final Map<Path, List<JsonNode>> fileResults = new HashMap<>();
        private final Map<Path, String> fileErrors = new HashMap<>();

        @Getter
        private final Map<Path, Long> beforeModificationTimestamps = new HashMap<>();

        private final Set<Path> modified = new LinkedHashSet<>();

        @Getter
        private final List<Path> projectFiles = new ArrayList<>();

        @Getter
        private final List<Path> solutionFiles = new ArrayList<>();

        private final Map<String, JsonNode> rules = new HashMap<>();

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
                String pathString = written.toString();
                if (isProjectFile(pathString)) {
                    projectFiles.add(written);
                } else if (isSolutionFile(pathString)) {
                    solutionFiles.add(written);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private boolean isProjectFile(String pathString) {
            return pathString.endsWith(".csproj") || pathString.endsWith(".vbproj") || pathString.endsWith(".fsproj");
        }

        private boolean isSolutionFile(String pathString) {
            return pathString.endsWith(".sln");
        }

        private void addModifiedFile(Path path) {
            modified.add(path);
        }

        private boolean wasModified(SourceFile tree) {
            return modified.contains(resolvedPath(tree));
        }

        public String content(SourceFile tree) {
            try {
                Path path = resolvedPath(tree);
                return tree.getCharset() != null ?
                        new String(Files.readAllBytes(path), tree.getCharset()) :
                        new String(Files.readAllBytes(path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public Path resolvedPath(SourceFile tree) {
            return directory.resolve(tree.getSourcePath());
        }

        public void addFileResult(Path path, JsonNode jsonNode) {
            fileResults.computeIfAbsent(path, key -> new ArrayList<>()).add(jsonNode);
        }

        public @Nullable List<JsonNode> getFileResults(Path path) {
            return fileResults.get(path);
        }

        public void addFileError(Path path, String error) {
            fileErrors.put(path, error);
        }

        public @Nullable String getFileError(Path path) {
            return fileErrors.get(path);
        }

        public void addRule(String ruleId, JsonNode jsonNode) {
            rules.put(ruleId, jsonNode);
        }

        public String getRuleLabel(String ruleId) {
            return rules.get(ruleId).get("label").asText();
        }
    }
}
