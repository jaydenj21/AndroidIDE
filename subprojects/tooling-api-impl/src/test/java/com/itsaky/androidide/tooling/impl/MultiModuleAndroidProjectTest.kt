/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.tooling.impl

import com.android.builder.model.v2.ide.LibraryType.PROJECT
import com.android.builder.model.v2.ide.ProjectType.APPLICATION
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.IProject
import com.itsaky.androidide.tooling.api.IProject.Type.Gradle
import com.itsaky.androidide.tooling.api.IToolingApiServer
import com.itsaky.androidide.tooling.api.messages.InitializeProjectMessage
import com.itsaky.androidide.tooling.api.model.AndroidModule
import com.itsaky.androidide.tooling.api.model.JavaModule
import com.itsaky.androidide.tooling.api.model.JavaModuleExternalDependency
import com.itsaky.androidide.tooling.api.model.JavaModuleProjectDependency
import com.itsaky.androidide.tooling.testing.ToolingApiTestLauncher
import com.itsaky.androidide.tooling.testing.ToolingApiTestLauncher.MultiVersionTestClient
import com.itsaky.androidide.utils.FileProvider
import kotlin.io.path.deleteExisting
import kotlin.io.path.pathString
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** @author Akash Yadav */
@RunWith(JUnit4::class)
class MultiModuleAndroidProjectTest {

  @Test
  fun `test simple multi module project initialization`() {
    val (server, project) = ToolingApiTestLauncher().launchServer()
    server.initialize(InitializeProjectMessage(FileProvider.testProjectRoot().pathString)).get()
    doAssertions(project, server)
  }

  private fun doAssertions(project: IProject, server: IToolingApiServer) {
    assertThat(project).isNotNull()
    assertThat(project.type.get()).isEqualTo(Gradle)
    // As the returned project is just a proxy,
    // project instanceOf IdeGradleProject will always return false
    val isInitialized = server.isServerInitialized().get()
    assertThat(isInitialized).isTrue()
    val app = project.findByPath(":app").get()
    assertThat(app).isNotNull()
    assertThat(app).isInstanceOf(AndroidModule::class.java)
    assertThat((app as AndroidModule).javaCompileOptions).isNotNull()
    assertThat(app.javaCompileOptions.sourceCompatibility).isEqualTo("11")
    assertThat(app.javaCompileOptions.targetCompatibility).isEqualTo("11")
    assertThat(app.javaCompileOptions.javaSourceVersion).isEqualTo("11")
    assertThat(app.javaCompileOptions.javaBytecodeVersion).isEqualTo("11")
    assertThat(app.javaCompileOptions.isCoreLibraryDesugaringEnabled).isFalse()
    assertThat(app.projectType).isEqualTo(APPLICATION)
    assertThat(app.packageName).isEqualTo("com.itsaky.test.app")
    assertThat(app.viewBindingOptions).isNotNull()
    assertThat(app.viewBindingOptions!!.isEnabled).isTrue()
    // There are always more than 100 tasks in an android module
    // Also, the tasks must contain the user defined tasks
    assertThat(app.tasks.size).isAtLeast(100)
    assertThat(app.tasks.first { it.path == "${app.projectPath}:thisIsATestTask" }).isNotNull()
    assertThat(app.libraries).isNotEmpty()
    // At least one project library
    assertThat(app.libraryMap.values.filter { it.type == PROJECT }).isNotEmpty()
    // :app module includes :java-library as a dependency. But it is not transitive
    assertThat(
        app.libraryMap.values.firstOrNull {
          it.type == PROJECT &&
            it.projectInfo!!.projectPath == ":another-java-library" &&
            it.projectInfo!!.attributes["org.gradle.usage"] == "java-api"
        }
      )
      .isNull()
    val androidLib = project.findByPath(":android-library").get()
    assertThat(androidLib).isNotNull()
    assertThat(androidLib).isInstanceOf(AndroidModule::class.java)
    // Make sure that transitive dependencies are included here because :android-library includes
    // :java-library project which further includes :another-java-libraries project with 'api'
    // configuration
    assertThat(
        (androidLib as AndroidModule).libraryMap.values.firstOrNull {
          it.type == PROJECT &&
            it.projectInfo!!.projectPath == ":another-java-library" &&
            it.projectInfo!!.attributes["org.gradle.usage"] == "java-api"
        }
      )
      .isNotNull()
    assertThat(androidLib.javaCompileOptions.javaSourceVersion).isEqualTo("11")
    assertThat(androidLib.javaCompileOptions.javaBytecodeVersion).isEqualTo("11")
    val javaLibrary = project.findByPath(":java-library").get()
    assertThat(javaLibrary).isNotNull()
    assertThat(javaLibrary).isInstanceOf(JavaModule::class.java)
    assertThat((javaLibrary as JavaModule).compilerSettings.javaSourceVersion).isEqualTo("11")
    assertThat(javaLibrary.compilerSettings.javaBytecodeVersion).isEqualTo("11")
    assertThat(
        javaLibrary.javaDependencies.firstOrNull {
          it is JavaModuleExternalDependency &&
            it.gradleArtifact != null &&
            it.run {
              gradleArtifact!!.group == "io.github.itsaky" &&
                gradleArtifact!!.name == "nb-javac-android" &&
                gradleArtifact!!.version == "17.0.0.0"
            }
        }
      )
      .isNotNull()
    assertThat(
        javaLibrary.javaDependencies.firstOrNull {
          it is JavaModuleProjectDependency && it.moduleName == "another-java-library"
        }
      )
      .isNotNull()
    // In case we have multiple dependencies with same name but different path
    val nested =
      javaLibrary.javaDependencies
        .filterIsInstance(JavaModuleProjectDependency::class.java)
        .filter { it.moduleName.endsWith("nested-java-library") }
    assertThat(nested).hasSize(2)
    assertThat(nested[0].projectPath).isNotEqualTo(nested[1].projectPath)
    assertThat(project.findByPath(":does-not-exist").get()).isNull()
    val anotherJavaLib = project.findByPath(":another-java-library").get()
    assertThat(anotherJavaLib).isNotNull()
    assertThat(anotherJavaLib).isInstanceOf(JavaModule::class.java)
    assertThat((anotherJavaLib as JavaModule).compilerSettings.javaSourceVersion).isEqualTo("1.8")
    assertThat(anotherJavaLib.compilerSettings.javaBytecodeVersion).isEqualTo("1.8")
  }

  /**
   * Tests the functionality of the tooling API implementation against multiple versions of the
   * Android Gradle Plugin. This test runs only in the CI environment.
   */
  @Test
  @Throws(CIOnlyException::class)
  fun `test CI-only simple multi module project initialization with multiple AGP versions`() {
    ciOnlyTest {
      // Test the minimum supported and the latest AGP version
      val versions =
        listOf(
          // AGP to Gradle
          "7.2.0" to "7.3.3",
          "8.0.0-rc01" to "8.0.2"
        )

      val client = MultiVersionTestClient()
      for ((agpVersion, gradleVersion) in versions) {
        client.agpVersion = agpVersion
        client.gradleVersion = gradleVersion
        val (server, project) = ToolingApiTestLauncher().launchServer(client = client)
        server.initialize(InitializeProjectMessage(FileProvider.testProjectRoot().pathString)).get()
        doAssertions(project = project, server = server)
        FileProvider.testProjectRoot().resolve(MultiVersionTestClient.buildFile).deleteExisting()
      }
    }
  }

  private fun ciOnlyTest(test: () -> Unit) {
    try {
      assertIsCI()
      test()
    } catch (err: CIOnlyException) {
      if (shouldTestMultipleVersions()) {
        throw err
      }
    }
  }

  private fun assertIsCI() {
    if (!shouldTestMultipleVersions()) {
      throw CIOnlyException()
    }
  }

  private fun shouldTestMultipleVersions(): Boolean {
    return System.getenv("TEST_TOOLING_API_IMPL").let { it == "true" }
  }

  private class CIOnlyException : IllegalStateException()
}
