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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.test.SourceSpecs.text;

@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
public class UpgradeAssistantAnalyzeTest implements RewriteTest {

    @Test
    void analyzeSingleProject() {
        rewriteRun(
                spec -> spec.recipe(new UpgradeAssistantAnalyze("net9.0")),
                text(
                        """
                        <Project Sdk="Microsoft.NET.Sdk">
                          <PropertyGroup>
                            <TargetFrameworks>net6.0</TargetFrameworks>
                          </PropertyGroup>
                        </Project>
                        """,
                        spec -> spec.path("src/Proj.csproj")
                ),
                text(
                        """
                        Microsoft Visual Studio Solution File, Format Version 12.00
                        # Visual Studio Version 16
                        VisualStudioVersion = 16.0.29709.97
                        MinimumVisualStudioVersion = 10.0.40219.1
                        Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "Proj", "src/Proj.csproj", "{38434103-76E0-4820-B4AF-F5EA5D08A7BD}"
                        EndProject
                        """,
                        spec -> spec.path("Proj.sln")
                )
        );
    }
}
