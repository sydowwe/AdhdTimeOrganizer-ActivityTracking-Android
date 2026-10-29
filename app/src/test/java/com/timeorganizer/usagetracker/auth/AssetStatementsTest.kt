package com.timeorganizer.usagetracker.auth

import com.timeorganizer.usagetracker.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The manifest's `asset_statements` must name the web app this build talks to: pointing at another origin, Google
 * Password Manager quietly offers no saved sign-ins, and nothing else fails. A production switch of
 * [Constants.WEB_APP_ROOT] that forgets `res/values/asset_statements.xml` fails here instead.
 */
class AssetStatementsTest {

    @Test
    fun theStatement_includesTheWebAppsAssetLinks() {
        // Gradle runs unit tests in the module's directory.
        val xml = File("src/main/res/values/asset_statements.xml").readText()
        val include = Regex("""\\"include\\":\s*\\"([^\\]+)\\"""").find(xml)?.groupValues?.get(1)

        assertEquals(Constants.WEB_APP_ROOT + ".well-known/assetlinks.json", include)
    }
}
