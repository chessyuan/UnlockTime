package com.phoneuse.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicensesTest {
    private val sample = """
        {"libraries":[
          {"uniqueId":"com.squareup.okhttp3:okhttp","artifactVersion":"4.12.0","name":"OkHttp",
           "developers":[{"name":"Square, Inc."}],"organization":{"name":"Square"},"licenses":["Apache-2.0"],"funding":[]},
          {"uniqueId":"androidx.compose:compose-bom","artifactVersion":"2026.06.01","name":"Jetpack Compose Libraries BOM",
           "developers":[],"licenses":["Apache-2.0"]},
          {"uniqueId":"com.google.android.gms:play-services-base","artifactVersion":"18.5.0","name":"",
           "developers":[],"licenses":["ASDKL"]},
          {"uniqueId":"org.osmdroid:osmdroid-android","artifactVersion":"6.1.20","name":"osmdroid",
           "developers":[],"organization":{"name":"osmdroid"},"licenses":["Apache-2.0"],"somethingNew":1}
        ],
        "licenses":{
          "Apache-2.0":{"name":"Apache License 2.0","url":"https://spdx.org/licenses/Apache-2.0.html","content":"  Apache text\n","hash":"x"},
          "ASDKL":{"name":"Android Software Development Kit License","url":"https://developer.android.com/studio/terms.html"},
          "MIT":{"name":"MIT License","content":"unused"}
        }}
    """.trimIndent()

    @Test
    fun `libraries are sorted by name and fall back to the artifact id`() {
        val notices = Licenses.parse(sample)
        assertEquals(
            listOf("Jetpack Compose Libraries BOM", "OkHttp", "osmdroid", "play-services-base"),
            notices.libraries.map { it.name },
        )
    }

    @Test
    fun `author is the first developer, else the organization, else empty`() {
        val byName = Licenses.parse(sample).libraries.associateBy { it.name }
        assertEquals("Square, Inc.", byName.getValue("OkHttp").author)
        assertEquals("osmdroid", byName.getValue("osmdroid").author)
        assertEquals("", byName.getValue("Jetpack Compose Libraries BOM").author)
        assertEquals("18.5.0", byName.getValue("play-services-base").version)
    }

    @Test
    fun `only licences a library uses are kept, with their text trimmed`() {
        val texts = Licenses.parse(sample).texts
        assertEquals(listOf("Android Software Development Kit License", "Apache License 2.0"), texts.map { it.name })
        assertEquals("Apache text", texts.first { it.id == "Apache-2.0" }.text)
        assertTrue(texts.first { it.id == "ASDKL" }.text.isEmpty())
    }

    @Test
    fun `an empty list parses to nothing`() {
        val notices = Licenses.parse("""{"libraries":[],"licenses":{}}""")
        assertTrue(notices.libraries.isEmpty() && notices.texts.isEmpty())
    }

    @Test fun projectNoticesRetainEverySourceTextAndGeneratedDependencyNotice() {
        val dependency = Licenses.parse(sample)
        val combined = Licenses.withProjectNotices(dependency,
            projectText = "Project copyright\nFull project permission text",
            openPhoneUseText = "Upstream copyright\nFull upstream permission text",
            openClawText = "Adapted copyright\nFull adapted permission text",
            thirdPartyNotices = "Source attribution\nModification notes")
        assertTrue(combined.libraries.containsAll(dependency.libraries))
        assertTrue(combined.texts.containsAll(dependency.texts))
        val texts = combined.texts.associateBy { it.id }
        assertEquals("Project copyright\nFull project permission text", texts.getValue(Licenses.PROJECT_LICENSE_ID).text)
        assertEquals("Upstream copyright\nFull upstream permission text", texts.getValue("unlocktime:openphoneuse-mit").text)
        assertEquals("Adapted copyright\nFull adapted permission text", texts.getValue("unlocktime:openclaw-mit").text)
        assertEquals("Source attribution\nModification notes", texts.getValue("unlocktime:source-notices").text)
        combined.libraries.take(3).forEach { project ->
            assertTrue(project.author.isNotBlank())
            assertTrue(project.licenseIds.all { texts.getValue(it).text.isNotEmpty() })
        }
    }

    @Test fun projectLicenseStillLoadsWhenGeneratedDependenciesAreUnavailable() {
        val combined = Licenses.withProjectNotices(LicenseNotices(emptyList(), emptyList()),
            "Project MIT", "Upstream MIT", "Adapted MIT", "Source notices")
        assertEquals(3, combined.libraries.size)
        assertEquals(4, combined.texts.size)
    }
}
