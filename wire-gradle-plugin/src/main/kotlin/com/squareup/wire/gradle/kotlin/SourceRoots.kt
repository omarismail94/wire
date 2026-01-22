/*
 * Copyright (C) 2020 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire.gradle.kotlin

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.squareup.wire.gradle.JavaOutput
import com.squareup.wire.gradle.KotlinOutput
import com.squareup.wire.gradle.WireExtension
import com.squareup.wire.gradle.WirePlugin
import com.squareup.wire.gradle.WireTask
import com.squareup.wire.gradle.inputLocations
import com.squareup.wire.gradle.internal.targetDefaultOutputPath
import com.squareup.wire.schema.ProtoTarget
import com.squareup.wire.schema.Target
import com.squareup.wire.schema.newEventListenerFactory
import java.io.File
import java.lang.reflect.Array as JavaArray
import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal fun interface WireTaskFactory {
  fun createWireTasks(project: Project, extension: WireExtension)
}

internal fun getWireTaskFactory(
  project: Project,
  kotlin: Boolean,
  java: Boolean,
): WireTaskFactory {
  project.extensions.findByType(KotlinMultiplatformExtension::class.java)?.let {
    return KotlinMultiplatformWireTaskFactory(it)
  }

  project.extensions.findByName("androidComponents")?.let {
    return AndroidWireTaskFactory(kotlin)
  }

  if (!kotlin && java) {
    return JavaWireTaskFactory(project.property("sourceSets") as SourceSetContainer)
  }

  return KotlinJvmWireTaskFactory((project.extensions.getByName("kotlin") as KotlinProjectExtension).sourceSets)
}

private class KotlinMultiplatformWireTaskFactory(
  private val kotlinMultiplatformExtension: KotlinMultiplatformExtension,
) : WireTaskFactory {
  override fun createWireTasks(project: Project, extension: WireExtension) {
    val sources = kotlinMultiplatformExtension.sourceRoots()
    val hasMultipleSources = sources.size > 1
    sources.forEach { source ->
      setupWireTask(project, extension, source, hasMultipleSources)
    }
  }
}

private class AndroidWireTaskFactory(
  private val kotlin: Boolean,
) : WireTaskFactory {
  override fun createWireTasks(project: Project, extension: WireExtension) {
    val androidComponents =
      project.extensions.getByType(AndroidComponentsExtension::class.java)

    androidComponents.onVariants { variant ->
      val sourceSetNames = mutableListOf("main")
      variant.buildType?.let { sourceSetNames.add(it) }
      sourceSetNames.addAll(variant.productFlavors.map { it.second })
      sourceSetNames.add(variant.name)

      val source = AndroidSource(
        name = variant.name,
        sourceSets = sourceSetNames.distinct(),
        variant = variant,
        kotlin = kotlin,
      )
      setupWireTask(project, extension, source, hasMultipleSources = true)
    }
  }
}

private class JavaWireTaskFactory(
  private val sourceSets: SourceSetContainer,
) : WireTaskFactory {
  override fun createWireTasks(project: Project, extension: WireExtension) {
    val sources = javaSourceRoots(sourceSets)
    val hasMultipleSources = sources.size > 1
    sources.forEach { source ->
      setupWireTask(project, extension, source, hasMultipleSources)
    }
  }
}

private class KotlinJvmWireTaskFactory(
  private val sourceSets: NamedDomainObjectContainer<KotlinSourceSet>,
) : WireTaskFactory {
  override fun createWireTasks(project: Project, extension: WireExtension) {
    val sources = kotlinJvmSourceRoots(sourceSets)
    val hasMultipleSources = sources.size > 1
    sources.forEach { source ->
      setupWireTask(project, extension, source, hasMultipleSources)
    }
  }
}

private val SOURCE_FUNCTION by lazy(LazyThreadSafetyMode.NONE) {
  KotlinCompile::class.java.getMethod(
    "source",
    JavaArray.newInstance(Any::class.java, 0).javaClass,
  )
}

private fun setupWireTask(
  project: Project,
  extension: WireExtension,
  source: Source,
  hasMultipleSources: Boolean,
) {
  val outputs = extension.outputs
  val hasJavaOutput = outputs.any { it is JavaOutput }
  val hasKotlinOutput = outputs.any { it is KotlinOutput }

  val protoSourceProtoRootSets = extension.protoSourceProtoRootSets.toMutableList()
  val protoPathProtoRootSets = extension.protoPathProtoRootSets.toMutableList()

  if (protoSourceProtoRootSets.all { it.isEmpty }) {
    val sourceSetProtoRootSet = WireExtension.ProtoRootSet(
      project = project,
      name = "${source.name}ProtoSource",
    )
    protoSourceProtoRootSets += sourceSetProtoRootSet
    for (sourceFolder in source.defaultSourceFolders(project)) {
      sourceSetProtoRootSet.srcDir(sourceFolder)
    }
  }

  val targets = outputs.map { output ->
    output.toTarget(project.relativePath(output.out ?: source.outputDir(project, hasMultipleSources)))
  }
  val generatedSourcesDirectories: Set<File> =
    targets
      // Emitted `.proto` files have a special treatment. Their root should be a resource, not a
      // source. We exclude the `ProtoTarget` and we'll add its output to the resources below.
      .filterNot { it is ProtoTarget }
      .map { target -> project.file(target.outDirectory) }
      .toSet()

  val protoTarget = targets.filterIsInstance<ProtoTarget>().firstOrNull()

  if (hasJavaOutput) {
    project.tasks
      .withType(JavaCompile::class.java)
      .matching { it.name == "compileJava" }
      .configureEach {
        it.source(generatedSourcesDirectories)
      }
  }
  val hasKotlinPlugin = project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") ||
    project.plugins.hasPlugin("org.jetbrains.kotlin.jvm") ||
    project.plugins.hasPlugin("org.jetbrains.kotlin.js") ||
    project.plugins.hasPlugin("kotlin2js") ||
    project.plugins.hasPlugin("com.android.experimental.built-in-kotlin") ||
    project.plugins.hasPlugin("org.jetbrains.kotlin.android")

  if ((hasJavaOutput || hasKotlinOutput) && hasKotlinPlugin) {
    val kotlinCompileClass = Class.forName(
      "org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile",
      false,
      WirePlugin::class.java.classLoader,
    ) as Class<out org.gradle.api.Task>
    project.tasks
      .withType(kotlinCompileClass)
      .matching {
        it.name.equals("compileKotlin") || it.name == "compile${source.name.replaceFirstChar { it.uppercase() }}Kotlin"
      }.configureEach {
        // Note that [KotlinCompile.source] will process files but will ignore strings.
        SOURCE_FUNCTION.invoke(it, arrayOf(generatedSourcesDirectories))
      }
  }

  val taskName = "generate${source.name.replaceFirstChar { it.uppercase() }}Protos"
  val task = project.tasks.register(taskName, WireTask::class.java) { task: WireTask ->
    task.group = WirePlugin.GROUP
    task.description = "Generate protobuf implementation for ${source.name}"

    var addedSourcesDependencies = false
    // Flatten all the input files here. Changes to any of them will cause the task to re-run.
    for (rootSet in protoSourceProtoRootSets) {
      task.source(rootSet.configuration)
      if (!rootSet.isEmpty) {
        // Use the isEmpty flag to avoid resolving the configuration eagerly
        addedSourcesDependencies = true
      }
    }
    // We only want to add ProtoPath sources if we have other sources already. The WireTask
    // would otherwise run even through we have no sources.
    if (addedSourcesDependencies) {
      for (rootSet in protoPathProtoRootSets) {
        task.source(rootSet.configuration)
      }
    }

    val outputDirectories: List<String> = buildList {
      addAll(
        targets
          // Emitted `.proto` files have a special treatment. Their root should be a resource, not
          // a source. We exclude the `ProtoTarget` and we'll add its output to the resources
          // below.
          .filterNot { it is ProtoTarget }
          .map(Target::outDirectory),
      )
    }
    task.outputDirectories.setFrom(outputDirectories)
    task.protoSourceConfiguration.setFrom(project.configurations.getByName("protoSource"))
    task.protoPathConfiguration.setFrom(project.configurations.getByName("protoPath"))
    task.projectDependenciesJvmConfiguration.setFrom(project.configurations.getByName("protoProjectDependenciesJvm"))
    if (protoTarget != null) {
      task.protoLibraryOutput.set(project.file(protoTarget.outDirectory))
    }
    task.sourceInput.set(project.provider { protoSourceProtoRootSets.inputLocations })
    task.protoInput.set(project.provider { protoPathProtoRootSets.inputLocations })
    task.roots.set(extension.roots.toList())
    task.prunes.set(extension.prunes.toList())
    task.moves.set(extension.moves.toList())
    task.opaques.set(extension.opaques.toList())
    task.sinceVersion.set(extension.sinceVersion)
    task.untilVersion.set(extension.untilVersion)
    task.onlyVersion.set(extension.onlyVersion)
    task.rules.set(extension.rules)
    task.targets.set(targets)
    task.permitPackageCycles.set(extension.permitPackageCycles)
    task.loadExhaustively.set(extension.loadExhaustively)
    task.dryRun.set(extension.dryRun)
    task.rejectUnusedRootsOrPrunes.set(extension.rejectUnusedRootsOrPrunes)

    task.projectDirProperty.set(project.layout.projectDirectory)
    task.buildDirProperty.set(project.layout.buildDirectory)

    val factories = extension.eventListenerFactories + extension.eventListenerFactoryClasses().map(::newEventListenerFactory)
    task.eventListenerFactories.set(factories)
  }

  val taskOutputDirectories = task.map { it.outputDirectories }
  source.registerGeneratedSources(project, task, taskOutputDirectories, generatedSourcesDirectories)

  val protoOutputDirectory = task.map { it.protoLibraryOutput }
  if (protoTarget != null) {
    val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
    // Note that there are no source sets for some platforms such as native.
    // TODO(Benoit) Probably should be checking for other names than `main`. As well, source
    //  sets might be created 'afterEvaluate'. Does that mean we should do this work in
    //  `afterEvaluate` as well? See: https://kotlinlang.org/docs/multiplatform-dsl-reference.html#source-sets
    if (sourceSets.findByName("main") != null) {
      sourceSets.getByName("main") { main: SourceSet ->
        main.resources.srcDir(protoOutputDirectory)
      }
    } else {
      project.logger.warn("${project.displayName} doesn't have a 'main' source sets. The .proto files will not automatically be added to the artifact.")
    }
  }

  project.tasks.named(WirePlugin.ROOT_TASK).configure {
    it.dependsOn(task)
  }
}

internal fun javaSourceRoots(sourceSets: SourceSetContainer): List<Source> {
  return listOf(
    JvmSource(
      name = "main",
      sourceSets = listOf("main"),
      kotlinSourceDirectorySet = null,
      javaSourceDirectorySet = sourceSets.getByName("main").java,
    ),
  )
}

internal fun kotlinJvmSourceRoots(sourceSets: NamedDomainObjectContainer<KotlinSourceSet>): List<Source> {
  return listOf(
    JvmSource(
      name = "main",
      sourceSets = listOf("main"),
      kotlinSourceDirectorySet = sourceSets.getByName("main").kotlin,
      javaSourceDirectorySet = null,
    ),
  )
}

internal fun KotlinMultiplatformExtension.sourceRoots(): List<Source> {
  // Wire only supports commonMain as in other cases, we'd be expected to generate both
  // `expect` and `actual` classes which doesn't make much sense for what Wire does.
  return listOf(
    JvmSource(
      name = "commonMain",
      kotlinSourceDirectorySet = sourceSets.getByName("commonMain").kotlin,
      javaSourceDirectorySet = null,
      sourceSets = listOf("commonMain"),
    ),
  )
}

internal sealed class Source(
  val name: String,
  val sourceSets: List<String>,
) {
  fun outputDir(project: Project, hasMultipleSources: Boolean): File {
    return if (hasMultipleSources) {
      File(project.targetDefaultOutputPath(), name)
    } else {
      File(project.targetDefaultOutputPath())
    }
  }

  fun defaultSourceFolders(project: Project): Set<String> {
    return sourceSets.map { "src/$it/proto" }
      .filter { path -> File(project.projectDir, path).exists() }
      .toSet()
  }

  abstract fun registerGeneratedSources(
    project: Project,
    wireTask: TaskProvider<WireTask>,
    taskOutputDirectories: Provider<ConfigurableFileCollection>,
    generatedSourcesDirectories: Set<File>,
  )
}

internal class JvmSource(
  name: String,
  sourceSets: List<String>,
  private val kotlinSourceDirectorySet: SourceDirectorySet?,
  private val javaSourceDirectorySet: SourceDirectorySet?,
) : Source(name, sourceSets) {
  override fun registerGeneratedSources(
    project: Project,
    wireTask: TaskProvider<WireTask>,
    taskOutputDirectories: Provider<ConfigurableFileCollection>,
    generatedSourcesDirectories: Set<File>,
  ) {
    kotlinSourceDirectorySet?.srcDir(taskOutputDirectories)
    javaSourceDirectorySet?.srcDir(taskOutputDirectories)
  }
}

internal class AndroidSource(
  name: String,
  sourceSets: List<String>,
  private val variant: Variant,
  private val kotlin: Boolean,
) : Source(name, sourceSets) {
  override fun registerGeneratedSources(
    project: Project,
    wireTask: TaskProvider<WireTask>,
    taskOutputDirectories: Provider<ConfigurableFileCollection>,
    generatedSourcesDirectories: Set<File>,
  ) {
    generatedSourcesDirectories.toList().forEachIndexed { i, dir ->
      val dummyTaskName = "wire${variant.name.replaceFirstChar { it.uppercase() }}Output$i"
      val dummyTask = project.tasks.register(dummyTaskName, DummyTask::class.java) {
        it.output.set(dir)
      }
      dummyTask.configure { it.dependsOn(wireTask) }

      variant.sources.java?.addGeneratedSourceDirectory(dummyTask, DummyTask::output)
      if (kotlin) {
        variant.sources.kotlin?.addGeneratedSourceDirectory(dummyTask, DummyTask::output)
      }
    }
  }
}

private abstract class DummyTask : DefaultTask() {
  @get:OutputDirectory
  abstract val output: DirectoryProperty
}
