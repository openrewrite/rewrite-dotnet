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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.RecipeException;
import org.openrewrite.SourceFile;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Value
@EqualsAndHashCode(callSuper = false)
public class UpgradeAssistantAnalyze extends UpgradeAssistantRecipe {
    private static final Pattern RECOMMENDATION_SNIPPET_PATTERN =
            Pattern.compile("(.*)\\n\\nRecommendation:\\n\\n(.*)");
    private static final Pattern CURRENT_NEW_SNIPPET_PATTERN =
            Pattern.compile("Current: (.*)\\nNew: (.*)");

    transient UpgradeAssistantAnalysis analysisTable = new UpgradeAssistantAnalysis(this);

    @Option(
            displayName = "Target framework version",
            description = "Target framework to which source project should be upgraded.",
            example = "net9.0")
    String targetFramework;

    @Override
    public String getDisplayName() {
        return "Analyze a .NET project using upgrade-assistant";
    }

    @Override
    public String getDescription() {
        return "Run [upgrade-assistant](https://learn.microsoft.com/en-us/dotnet/core/porting/upgrade-assistant-overview) " +
                "across a project to analyze changes required to upgrade it to a newer .NET framework version.";
    }

    @Override
    public List<String> buildUpgradeAssistantCommand(Accumulator acc, ExecutionContext ctx, Path solutionFile) {
        List<String> command = new ArrayList<>();
        command.add(getUpgradeAssistantPath().toString());
        command.add("analyze");
        command.add(solutionFile.toString());
        command.add("--source");
        command.add("Solution");
        command.add("--non-interactive");
        command.add("--targetFramework");
        command.add(targetFramework);
        command.add("--serializer");
        command.add("JSON");
        command.add("--report");
        command.add(buildReportPath(acc, solutionFile).getFileName().toString());
        return command;
    }

    @Override
    public void runUpgradeAssistant(Accumulator acc, ExecutionContext ctx) {
        for (Path solutionFile : acc.getSolutionFiles()) {
            Path reportPath = buildReportPath(acc, solutionFile);
            deleteFile(reportPath);
            execUpgradeAssistant(solutionFile, acc, ctx);
        }
    }

    @Override
    protected void processOutput(Path solutionFile, Path output, Accumulator acc) {
        Path reportPath = buildReportPath(acc, solutionFile);

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode reportNode = objectMapper.readTree(reportPath.toFile());
            JsonNode rulesNode = reportNode.get("rules");
            Iterator<Map.Entry<String, JsonNode>> iterator = rulesNode.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                acc.addRule(entry.getKey(), entry.getValue());
            }

            for (JsonNode projectNode : reportNode.get("projects")) {
                for (JsonNode ruleInstanceNode : projectNode.get("ruleInstances")) {
                    JsonNode locationNode = ruleInstanceNode.get("location");
                    Path filePath = Paths.get(locationNode.get("path").asText());
                    acc.addFileResult(acc.getDirectory().resolve(filePath), ruleInstanceNode);
                }
            }
        } catch (IOException e) {
            throw new RecipeException(e);
        }
    }

    private Path buildReportPath(Accumulator acc, Path solutionFile) {
        String reportName = solutionFile.getFileName().toString().replace(".sln", "-analyze.json");
        return acc.getDirectory().resolve(reportName);
    }

    @Override
    protected SourceFile createAfter(SourceFile before, Accumulator acc, ExecutionContext ctx) {
        List<JsonNode> results = acc.getFileResults(acc.resolvedPath(before));
        if (results != null) {
            for (JsonNode ruleInstanceNode : results) {
                analysisTable.insertRow(ctx, buildUpgradeAssistantAnalysisRow(ruleInstanceNode, acc));
            }
        }

        return super.createAfter(before, acc, ctx);
    }

    private UpgradeAssistantAnalysis.Row buildUpgradeAssistantAnalysisRow(JsonNode ruleInstanceNode, Accumulator acc) {
        JsonNode locationNode = ruleInstanceNode.get("location");
        URL link = null;
        if (locationNode.has("links")) {
            try {
                JsonNode linkNode = locationNode.get("links").get(0);
                link = new URL(linkNode.get("url").asText());
            } catch (IOException ignored) {
                // Ignored
            }
        }

        String projectPath = ruleInstanceNode.get("projectPath").asText();
        String sourcePath = locationNode.get("path").asText();
        String ruleId = ruleInstanceNode.get("ruleId").asText();
        String ruleLabel = acc.getRuleLabel(ruleId);

        String snippet = locationNode.get("snippet").asText();
        String codeSnippet;
        String recommendation;
        Matcher matcher = RECOMMENDATION_SNIPPET_PATTERN.matcher(snippet);
        if (matcher.find()) {
            codeSnippet = matcher.group(1);
            recommendation = matcher.group(2);
        } else {
            matcher = CURRENT_NEW_SNIPPET_PATTERN.matcher(snippet);
            if (matcher.find()) {
                codeSnippet = matcher.group(1);
                recommendation = matcher.group(2);
            } else {
                codeSnippet = snippet;
                recommendation = null;
            }
        }

        return new UpgradeAssistantAnalysis.Row(
                projectPath,
                sourcePath,
                ruleId,
                ruleLabel,
                codeSnippet,
                recommendation,
                link);
    }
}
