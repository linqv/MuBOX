package org.mubox.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class MuboxAndroidExtension {
    abstract val minSdk: Property<Int>
    abstract val supportedAbis: ListProperty<String>
    abstract val targetAbi: Property<String>
}
