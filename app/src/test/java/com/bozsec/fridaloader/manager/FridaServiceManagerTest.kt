package com.bozsec.fridaloader.manager

import com.bozsec.fridaloader.utils.RootUtil
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FridaServiceManagerTest {

    private lateinit var mockRootUtil: RootUtil
    private lateinit var manager: FridaServiceManager

    @Before
    fun setUp() {
        mockRootUtil = mockk()
        manager = FridaServiceManager(mockRootUtil)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `startServer with default name execution flow`() = runBlocking {
        // Mock successful execution
        coEvery { mockRootUtil.execute(any()) } returns RootUtil.CommandResult(true, "", "")

        manager.startServer(27042, false)

        // Verify stop called first
        coVerify { mockRootUtil.execute(match { it.contains("pkill") }) }
        
        // Verify start command
        coVerify { 
            mockRootUtil.execute(match { 
                it.contains("./frida-server") && it.contains("-l 0.0.0.0:27042")
            }) 
        }
    }

    @Test
    fun `startServer with random name renames binary`() = runBlocking {
        coEvery { mockRootUtil.execute(any()) } returns RootUtil.CommandResult(true, "", "")

        manager.startServer(1337, true)

        // Verify copy command
        coVerify { 
             mockRootUtil.execute(match { it.startsWith("cp") && it.contains("fs-") })
        }
        
        // Verify execution of new name
        coVerify { 
            mockRootUtil.execute(match { 
                it.contains("./fs-") && it.contains("-l 0.0.0.0:1337")
            }) 
        }
    }
}
