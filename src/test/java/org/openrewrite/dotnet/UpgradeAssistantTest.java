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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openrewrite.DocumentExample;
import org.openrewrite.RecipeException;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.openrewrite.test.SourceSpecs.text;

@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class UpgradeAssistantTest implements RewriteTest {

    @DocumentExample
    @ParameterizedTest
    @CsvSource(textBlock = """
      net6.0, net7.0
      net6.0, net9.0
      net8.0, net9.0
      """)
    void upgradeDotNetSingleProject(String currentVersion, String upgradedVersion) {
        rewriteRun(
          spec -> spec.recipe(new UpgradeAssistant(upgradedVersion)),
          text(
            """
              <Project Sdk="Microsoft.NET.Sdk">
                <PropertyGroup>
                  <TargetFrameworks>%s</TargetFrameworks>
                </PropertyGroup>
              </Project>
              """.formatted(currentVersion),
            """
              <Project Sdk="Microsoft.NET.Sdk">
                <PropertyGroup>
                  <TargetFrameworks>%s</TargetFrameworks>
                </PropertyGroup>
              </Project>
              """.formatted(upgradedVersion),
            spec -> spec.path("src/Proj.csproj")
          )
        );
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
      net6.0, net7.0
      net6.0, net9.0
      net7.0, net8.0
      """)
    void upgradeDotNetMultipleProject(String currentVersion, String upgradedVersion) {
        rewriteRun(
          spec -> spec.recipe(new UpgradeAssistant(upgradedVersion)),
          text(
            """
              <Project Sdk="Microsoft.NET.Sdk">
                <PropertyGroup>
                  <TargetFrameworks>%s</TargetFrameworks>
                </PropertyGroup>
              </Project>
              """.formatted(currentVersion),
            """
              <Project Sdk="Microsoft.NET.Sdk">
                <PropertyGroup>
                  <TargetFrameworks>%s</TargetFrameworks>
                </PropertyGroup>
              </Project>
              """.formatted(upgradedVersion),
            spec -> spec.path("src/Proj.csproj")
          ),
          text(
            """
              <Project Sdk="Microsoft.NET.Sdk">
                <PropertyGroup>
                  <TargetFrameworks>%s</TargetFrameworks>
                </PropertyGroup>
              </Project>
              """.formatted(currentVersion),
            """
              <Project Sdk="Microsoft.NET.Sdk">
                <PropertyGroup>
                  <TargetFrameworks>%s</TargetFrameworks>
                </PropertyGroup>
              </Project>
              """.formatted(upgradedVersion),
            spec -> spec.path("src/ProjTest.csproj")
          )
        );
    }

    @Test
    void upgradeDotNetWithInvalidVersion() {
        assertThatThrownBy(() ->
          rewriteRun(
            spec -> spec.recipe(new UpgradeAssistant("foo-bar")),
            text(
              """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <TargetFrameworks>net6.0</TargetFrameworks>
                  </PropertyGroup>
                </Project>
                """,
              spec -> spec.path("src/Proj.csproj")
            )
          ))
          .cause()
          .isInstanceOf(RecipeException.class)
          .hasMessageContaining("Unknown target framework");
    }

    @Test
    void upgradeDotNetWithNoProjectFiles() {
        assertThatThrownBy(() ->
          rewriteRun(
            spec -> spec.recipe(new UpgradeAssistant("net9.0"))
          ))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("No project files found in repository");
    }
}
