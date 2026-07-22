package io.github.zapretkvn.android.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateJsonTest {
    @Test
    fun `metadata and matching checksum are strict`() {
        val metadata = UpdateJson.metadata(METADATA)

        assertEquals("1.2.3", metadata.versionName)
        assertEquals(102_003_099, metadata.versionCode)
        assertEquals(listOf("arm64-v8a"), metadata.abi)
        assertEquals(SHA, UpdateJson.checksum("$SHA  Zapret-KVN-v1.2.3-arm64-v8a.apk\n", metadata.apkFile))
    }

    @Test
    fun `checksum for another file and unsafe apk name are rejected`() {
        assertThrows(UpdateException::class.java) {
            UpdateJson.checksum("$SHA  another.apk", "Zapret-KVN-v1.2.3-arm64-v8a.apk")
        }
        assertThrows(UpdateException::class.java) {
            UpdateJson.metadata(METADATA.replace("Zapret-KVN-v1.2.3-arm64-v8a.apk", "../update.apk"))
        }
    }

    @Test
    fun `github release parser keeps draft prerelease and digest`() {
        val release = UpdateJson.releases(RELEASE).single()

        assertEquals("v1.2.3", release.tag)
        assertEquals(false, release.draft)
        assertEquals(true, release.prerelease)
        assertEquals("sha256:$SHA", release.assets[1].digest)
    }

    private companion object {
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val METADATA = """
            {
              "schema":1,
              "version_name":"1.2.3",
              "version_code":102003099,
              "application_id":"io.github.zapretkvn.android",
              "core_tag":"v1.13.14-extended-2.5.2",
              "core_commit":"ff11f007ec798136a5de258f947a4f34011a37ea",
              "abi":["arm64-v8a"],
              "apk_file":"Zapret-KVN-v1.2.3-arm64-v8a.apk",
              "apk_sha256":"$SHA",
              "apk_size":1234
            }
        """
        const val RELEASE = """
            {
              "tag_name":"v1.2.3",
              "name":"Beta 1.2.3",
              "html_url":"https://github.com/ZapretKVN/ZapretKVN/releases/tag/v1.2.3",
              "draft":false,
              "prerelease":true,
              "assets":[
                {"name":"release-metadata.json","browser_download_url":"https://github.com/a","size":10},
                {"name":"app.apk","browser_download_url":"https://github.com/b","size":1234,"digest":"sha256:$SHA"}
              ]
            }
        """
    }
}
