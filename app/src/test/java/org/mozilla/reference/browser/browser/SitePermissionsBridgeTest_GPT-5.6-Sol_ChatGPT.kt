package org.mozilla.reference.browser.browser

import android.Manifest
import android.content.pm.PackageManager
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SitePermissionsBridgeTest_GPT_5_6_Sol_ChatGPT {
    @Test
    fun mixedCameraAndMicrophoneResultsPreserveOriginalRequest() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        val activityResults = mapOf(Manifest.permission.RECORD_AUDIO to true)

        val actual = resolveSitePermissionGrantResults(
            permissions = permissions,
            activityResults = activityResults,
            isCurrentlyGranted = { it == Manifest.permission.CAMERA },
        )

        assertArrayEquals(
            intArrayOf(
                PackageManager.PERMISSION_GRANTED,
                PackageManager.PERMISSION_GRANTED,
            ),
            actual,
        )
    }

    @Test
    fun deniedPermissionStaysDeniedWhileAlreadyGrantedPermissionStaysGranted() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        val activityResults = mapOf(Manifest.permission.RECORD_AUDIO to false)

        val actual = resolveSitePermissionGrantResults(
            permissions = permissions,
            activityResults = activityResults,
            isCurrentlyGranted = { it == Manifest.permission.CAMERA },
        )

        assertArrayEquals(
            intArrayOf(
                PackageManager.PERMISSION_GRANTED,
                PackageManager.PERMISSION_DENIED,
            ),
            actual,
        )
    }

    @Test
    fun permissionDialogActionLabelsAreExplicitlyWhite() {
        val candidates = listOf(
            Path.of("src/main/res/layout/mozac_site_permissions_prompt.xml"),
            Path.of("app/src/main/res/layout/mozac_site_permissions_prompt.xml"),
        )
        val layout = candidates.firstOrNull(Files::exists)
        assertNotNull("permission prompt layout override missing", layout)

        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(layout!!.toFile())

        val androidNs = "http://schemas.android.com/apk/res/android"
        val buttons = document.getElementsByTagName("Button")
        val colors = buildMap {
            for (i in 0 until buttons.length) {
                val node = buttons.item(i)
                val id = node.attributes.getNamedItemNS(androidNs, "id")?.nodeValue
                val color = node.attributes.getNamedItemNS(androidNs, "textColor")?.nodeValue
                if (id != null) put(id, color)
            }
        }

        assertEquals("@android:color/white", colors["@+id/allow_button"])
        assertEquals("@android:color/white", colors["@+id/deny_button"])
    }
}
