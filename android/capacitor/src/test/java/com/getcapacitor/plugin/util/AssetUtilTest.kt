package com.getcapacitor.plugin.util

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.os.StrictMode
import androidx.core.content.FileProvider
import com.getcapacitor.RecordingLogSink
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AssetUtilTest {
    @get:Rule
    val logs = RecordingLogSink()

    class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private val cacheDir: File = Files.createTempDirectory("asset-util").toFile()
    private val assets = mock<AssetManager>()
    private val context = mock<Context>()
    private val providedUri = mock<Uri>()
    private lateinit var fileProvider: MockedStatic<FileProvider>

    @Before
    fun setUp() {
        whenever(context.externalCacheDir).thenReturn(cacheDir)
        whenever(context.assets).thenReturn(assets)
        whenever(context.packageName).thenReturn("app")
        fileProvider = mockStatic(FileProvider::class.java)
        fileProvider.`when`<Uri> { FileProvider.getUriForFile(any(), any(), any()) }.thenReturn(providedUri)
    }

    @After
    fun tearDown() {
        fileProvider.close()
        cacheDir.deleteRecursively()
    }

    @Test
    fun assetIsCopiedAndItsStreamClosed() {
        val asset = TrackingStream(byteArrayOf(1, 2, 3))
        whenever(assets.open("www/img/icon.png")).thenReturn(asset)

        val uri = AssetUtil.getInstance(context).parse("file://img/icon.png")

        assertSame(providedUri, uri)
        assertTrue(asset.closed)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(cacheDir, "capacitorassets/icon.png").readBytes())
    }

    @Test
    fun missingAssetIsLoggedWithItsCause() {
        whenever(assets.open("www/img/missing.png")).thenThrow(FileNotFoundException("missing.png"))

        assertReturnsUriEmpty { AssetUtil.getInstance(context).parse("file://img/missing.png") }

        assertTrue(logs.entries.any { it.message == "File not found: assets/www/img/missing.png" && it.throwable is FileNotFoundException })
    }

    /** Serves fixed bodies over HTTP/1.1 on the loopback interface; any other path is a 404. */
    private class TinyHttpServer(private val bodies: Map<String, ByteArray>) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val baseUrl: String = "http://127.0.0.1:${socket.localPort}"

        init {
            thread(isDaemon = true) {
                while (true) {
                    val client =
                        try {
                            socket.accept()
                        } catch (e: IOException) {
                            break
                        }
                    client.use { answer(it) }
                }
            }
        }

        private fun answer(client: java.net.Socket) {
            val reader = client.getInputStream().bufferedReader()
            val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return
            while (reader.readLine()?.isNotEmpty() == true) {
                // Skip the request headers.
            }
            val body = bodies[path]
            val status = if (body != null) "200 OK" else "404 Not Found"
            val bytes = body ?: ByteArray(0)
            val out = client.getOutputStream()
            out.write("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }

        override fun close() {
            socket.close()
        }
    }

    @Test
    fun remoteDownloadRestoresTheCallersStrictModePolicy() {
        TinyHttpServer(mapOf("/icon.png" to byteArrayOf(4, 5, 6))).use { server ->
            assertStrictModeRestored { assertSame(providedUri, it.parse("${server.baseUrl}/icon.png")) }
            val downloads = cacheDir.resolve("capacitorassets").listFiles()!!
            assertTrue(downloads.any { file -> file.readBytes().contentEquals(byteArrayOf(4, 5, 6)) })
        }
    }

    @Test
    fun failedDownloadAlsoRestoresTheCallersStrictModePolicy() {
        TinyHttpServer(emptyMap()).use { server ->
            assertStrictModeRestored { assertReturnsUriEmpty { it.parse("${server.baseUrl}/missing.png") } }
        }
    }

    /**
     * The unit-test android.jar leaves Uri.EMPTY null, so a function that returns it fails its non-null check
     * with this NullPointerException instead.
     */
    private fun assertReturnsUriEmpty(block: () -> Uri) {
        try {
            block()
            fail("expected Uri.EMPTY")
        } catch (e: NullPointerException) {
            assertEquals("EMPTY must not be null", e.message)
        }
    }

    /**
     * Runs [block] with StrictMode mocked and checks that the permissive policy set for the download is replaced
     * by the caller's own policy afterwards.
     */
    private fun assertStrictModeRestored(block: (AssetUtil) -> Unit) {
        val callerPolicy = mock<StrictMode.ThreadPolicy>()
        val permissivePolicy = mock<StrictMode.ThreadPolicy>()
        val policiesSet = ArrayList<StrictMode.ThreadPolicy>()

        val strictMode: MockedStatic<StrictMode> = mockStatic(StrictMode::class.java)
        val builders: MockedConstruction<StrictMode.ThreadPolicy.Builder> =
            mockConstruction(StrictMode.ThreadPolicy.Builder::class.java) { builder, _ ->
                whenever(builder.permitAll()).thenReturn(builder)
                whenever(builder.build()).thenReturn(permissivePolicy)
            }
        try {
            strictMode.`when`<StrictMode.ThreadPolicy> { StrictMode.getThreadPolicy() }.thenReturn(callerPolicy)
            strictMode.`when`<Unit> { StrictMode.setThreadPolicy(any()) }.thenAnswer {
                policiesSet.add(it.getArgument(0))
                null
            }

            block(AssetUtil.getInstance(context))
        } finally {
            builders.close()
            strictMode.close()
        }

        assertEquals(listOf(permissivePolicy, callerPolicy), policiesSet)
    }
}
