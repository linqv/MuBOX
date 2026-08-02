plugins {
    id("mubox.jvm.library")
    `java-test-fixtures`
}

dependencies {
    testFixturesApi(project(":core:model"))
    testFixturesApi(libs.coroutines.core)
    testFixturesApi(libs.coroutines.test)
    testFixturesApi(libs.junit)
}
