plugins {
    id("org.openrewrite.build.recipe-library") version "latest.release"
}

group = "org.openrewrite.recipe"
description = "OpenRewrite recipes based on .NET tools"

val rewriteVersion = rewriteRecipe.rewriteVersion.get()
dependencies {
    implementation(platform("org.openrewrite:rewrite-bom:$rewriteVersion"))

    implementation("org.openrewrite:rewrite-core")

    testImplementation("org.openrewrite:rewrite-test")
}
