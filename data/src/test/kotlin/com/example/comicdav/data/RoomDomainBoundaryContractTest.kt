package com.example.comicdav.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDomainBoundaryContractTest {
    @Test
    fun publicCatalogModelsAreFreeOfRoomAnnotations() {
        listOf(
            sourceFile("filedirectory/FileDirectoryModels.kt"),
            sourceFile("library/LibraryModels.kt"),
            sourceFile("videolibrary/VideoLibraryModels.kt"),
        ).forEach { modelFile ->
            val source = modelFile.readText()
            assertFalse("${modelFile.name} must not import Room", source.contains("androidx.room"))
            ROOM_ANNOTATIONS.forEach { annotation ->
                assertFalse("${modelFile.name} must not use $annotation", source.contains(annotation))
            }
        }
    }

    @Test
    fun roomPersistenceTypesStayInternal() {
        val fileDirectoryEntities = sourceFile("filedirectory/FileDirectoryEntities.kt").readText()
        val libraryEntities = sourceFile("library/LibraryEntities.kt").readText()
        val videoEntities = sourceFile("videolibrary/VideoLibraryEntities.kt").readText()

        assertTrue(fileDirectoryEntities.contains("internal data class FileDirectorySourceEntity("))
        assertTrue(fileDirectoryEntities.contains("internal class FileDirectoryTypeConverters"))
        assertTrue(libraryEntities.contains("internal data class LibraryItemEntity("))
        assertTrue(libraryEntities.contains("internal data class LocalComicSourceEntity("))
        assertTrue(libraryEntities.contains("internal data class WebDavComicSourceEntity("))
        assertTrue(libraryEntities.contains("internal data class LibraryItemRelation("))
        assertTrue(libraryEntities.contains("internal class LibraryTypeConverters"))
        assertTrue(videoEntities.contains("internal data class VideoLibraryItemEntity("))
        assertTrue(videoEntities.contains("internal data class LocalVideoSourceEntity("))
        assertTrue(videoEntities.contains("internal data class WebDavVideoSourceEntity("))
        assertTrue(videoEntities.contains("internal data class VideoLibraryItemRelation("))
        assertTrue(videoEntities.contains("internal class VideoLibraryTypeConverters"))
    }

    @Test
    fun roomDaosAndRepositoryConstructorsStayInternal() {
        val expectedDeclarations = mapOf(
            "filedirectory/FileDirectoryDao.kt" to "internal interface FileDirectoryDao",
            "filedirectory/FileDirectoryRepository.kt" to "class FileDirectoryRepository internal constructor(",
            "library/LibraryDao.kt" to "internal abstract class LibraryDao",
            "library/LibraryRepository.kt" to "class LibraryRepository internal constructor(",
            "videolibrary/VideoLibraryDao.kt" to "internal abstract class VideoLibraryDao",
            "videolibrary/VideoLibraryRepository.kt" to "class VideoLibraryRepository internal constructor(",
        )

        expectedDeclarations.forEach { (relativePath, declaration) ->
            assertTrue(
                "$relativePath must contain $declaration",
                sourceFile(relativePath).readText().contains(declaration),
            )
        }
    }

    private fun sourceFile(relativePath: String): File =
        File("src/main/kotlin/com/example/comicdav/data/$relativePath")

    private companion object {
        val ROOM_ANNOTATIONS = listOf("@Entity", "@Embedded", "@Relation")
    }
}
