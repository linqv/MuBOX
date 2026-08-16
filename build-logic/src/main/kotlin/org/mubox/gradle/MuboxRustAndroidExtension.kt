package org.mubox.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

abstract class MuboxRustAndroidExtension {
    abstract val crateDirectory: DirectoryProperty
    abstract val libraryName: Property<String>
}
