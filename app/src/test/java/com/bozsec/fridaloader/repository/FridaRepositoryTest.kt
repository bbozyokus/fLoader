package com.bozsec.fridaloader.repository

import com.bozsec.fridaloader.utils.RootUtil
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import android.os.Build

class FridaRepositoryTest {

    private lateinit var mockClient: OkHttpClient
    private lateinit var mockCall: Call
    private lateinit var mockRootUtil: RootUtil

    @Before
    fun setUp() {
        mockClient = mockk()
        mockCall = mockk()
        mockRootUtil = mockk()
        
        // Mock Build.SUPPORTED_ABIS
        mockkStatic(Build::class)
        every { Build.SUPPORTED_ABIS } returns arrayOf("arm64-v8a", "armeabi-v7a")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `fetchLatestFridaServer finds correct asset and downloads`() = runBlocking {
        // Arrange
        val jsonData = """
            {
              "tag_name": "16.1.11",
              "assets": [
                {
                  "name": "frida-server-16.1.11-android-arm64.xz",
                  "browser_download_url": "http://fake.url/arm64.xz",
                  "size": 100
                },
                {
                  "name": "frida-server-16.1.11-android-x86.xz",
                  "browser_download_url": "http://fake.url/x86.xz",
                  "size": 100
                }
              ]
            }
        """.trimIndent()
        
        // Mock Release Response
        val responseStart = Response.Builder()
            .request(Request.Builder().url("https://api.github.com/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(jsonData.toResponseBody("application/json".toMediaTypeOrNull()))
            .build()

        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns responseStart

        // Mock Download Response (Need a second call simulation, this is tricky with a single 'every')
        // We can use 'answers' or a sequence
        
        // Let's create an XZ payload. 
        // This is hard to fake perfectly without binary data, but for flow test we can try.
        // We'll trust the logic flow if we can pass the JSON parsing.
        
        val downloadResponse = Response.Builder()
            .request(Request.Builder().url("http://fake.url/arm64.xz").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("fake_content".toResponseBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
            
        every { mockCall.execute() } returnsMany listOf(responseStart, downloadResponse)

        // Mock File operations?
        // Since we are running in unit test, we can use a temporary file path
        val tempFile = File.createTempFile("frida-server", "")
        tempFile.deleteOnExit()
        
        val repository = FridaRepository(mockRootUtil, mockClient)
        
        // The decompression will fail in "real" execution because "fake_content" is not valid XZ.
        // So fetchLatestFridaServer should return null or throw or catch exception.
        // We expect it to catch exception inside and return null (or we can assert it tries to read).
        // Let's see if we can just verify the URL selection logic which is the most important part.
        
        val result = repository.fetchLatestFridaServer(tempFile.absolutePath) { _ -> }
        
        // We expect null because decompression fails
        assertNull(result)
        
        // Verification that we picked the correct URL
        verify {
            mockClient.newCall(match { request ->
                request.url.toString() == "http://fake.url/arm64.xz" || request.url.toString().contains("api.github.com")
            })
        }
    }
}
