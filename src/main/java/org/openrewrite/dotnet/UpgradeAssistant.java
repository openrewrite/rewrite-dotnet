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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Value
@EqualsAndHashCode(callSuper = false)
public class UpgradeAssistant extends UpgradeAssistantRecipe {

    @Option(
            displayName = "Target framework version",
            description = "Target framework to which source project should be upgraded.",
            example = "net9.0")
    String targetFramework;

    @Override
    public String getDisplayName() {
        return "Upgrade a .NET project using upgrade-assistant";
    }

    @Override
    public String getDescription() {
        return "Run [upgrade-assistant upgrade](https://learn.microsoft.com/en-us/dotnet/core/porting/upgrade-assistant-overview) " +
                "across a project to upgrade it to a newer .NET framework version.";
    }

    @Override
    public List<String> buildUpgradeAssistantCommand(Accumulator acc, ExecutionContext ctx, Path projectFile) {
        List<String> command = new ArrayList<>();
        command.add(getUpgradeAssistantPath().toString());
        command.add("upgrade");
        command.add(projectFile.toString());
        command.add("--non-interactive");
        command.add("--operation");
        command.add("Inplace");
        command.add("--targetFramework");
        command.add(targetFramework);
        return command;
    }

    @Override
    public void runUpgradeAssistant(Accumulator acc, ExecutionContext ctx) {
        for (Path projectFile : acc.getProjectFiles()) {
            execUpgradeAssistant(projectFile, acc, ctx);

        }
    }

    @Override
    protected void processOutput(Path projectFile, Path output, Accumulator acc) {
        Path projectDir = projectFile.getParent();

        try (BufferedReader reader = Files.newBufferedReader(output)) {
            String fileName = null;
            List<String> transformerLogs = new ArrayList<>();

            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.startsWith("Unknown target framework")) {
                    acc.addFileError(projectFile, line);
                    break;
                }
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
                            String error = transformerLogs.stream().map(String::trim).collect(Collectors.joining("\n"));
                            acc.addFileError(projectDir.resolve(fileName), error);
                        }
                        fileName = null;
                        transformerLogs.clear();
                    } else {
                        transformerLogs.add(line);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
