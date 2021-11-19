/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

import org.gradle.api.Project
import software.amazon.smithy.utils.CodeWriter
import java.io.File

/**
 * Generate a basic docs landing page into [outputDir]
 *
 * The generated docs will include links to crates.io, docs.rs and GitHub examples for all generated services. The generated docs will
 * be written to `docs.md` in the provided [outputDir].
 */
fun Project.docsLandingPage(awsServices: List<AwsService>, outputDir: File) {
    val project = this
    val writer = CodeWriter()
    with(writer) {
        write("# AWS SDK for Rust")
        write(
            """The AWS SDK for Rust contains one crate for each AWS service, as well as ${cratesIo("aws-config")} ${docsRs("aws-config")},
            |a crate implementing configuration loading such as credential providers.""".trimMargin()
        )

        writer.write("## AWS Services")
        writer.write("") // empty line between header and table
        /* generate a basic markdown table */
        writer.write("| Service | Package |")
        writer.write("| ------- | ------- |")
        awsServices.sortedBy { it.humanName }.forEach {
            val items = listOfNotNull(cratesIo(it), docsRs(it), examples(it, project)).joinToString(" ")
            writer.write(
                "| ${it.humanName} | $items |"
            )
        }
    }
    outputDir.resolve("index.md").writeText(writer.toString())
}

/**
 * Generate a link to the examples for a given service
 */
private fun examples(service: AwsService, project: Project) = if (with(service) { project.examples() }) {
    "([examples](https://github.com/awslabs/aws-sdk-rust/tree/main/examples/${service.module}))"
} else {
    null
}

/**
 * Generate a link to the docs
 */
private fun docsRs(service: AwsService) = docsRs(service.crate())
private fun docsRs(crate: String) = "([docs](https://docs.rs/$crate))"
private fun cratesIo(service: AwsService) = cratesIo(service.crate())
private fun cratesIo(crate: String) = "[$crate](https://crates.io/crates/$crate)"