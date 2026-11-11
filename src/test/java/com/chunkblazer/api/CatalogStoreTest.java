/*
 * Copyright (c) 2026, btwinnn
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */


package com.chunkblazer.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.chunkblazer.ChunkBlazerConfig;
import com.google.gson.Gson;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The plugin's login-screen catalog check depends on the store always calling back. */
@ExtendWith(MockitoExtension.class)
class CatalogStoreTest
{
	@Mock
	private ChunkBlazerConfig config;

	private CatalogStore store;

	@BeforeEach
	void setUp()
	{
		store = new CatalogStore(new OkHttpClient(), config, new Gson());
		store.init(null);
	}

	@AfterEach
	void tearDown()
	{
		store.shutdown();
	}

	/**
	 * With sync off there is nothing to fetch, but the callback still runs: it is what
	 * applies a catalog an earlier check downloaded.
	 */
	@Test
	void callsBackOffline() throws Exception
	{
		when(config.apiEnabled()).thenReturn(false);
		CountDownLatch checked = new CountDownLatch(1);
		store.refresh(checked::countDown);
		assertTrue(checked.await(5, TimeUnit.SECONDS));
	}

	/** A logout that races the plugin shutting down neither throws nor calls back. */
	@Test
	void refreshAfterShutdown() throws Exception
	{
		store.shutdown();
		CountDownLatch checked = new CountDownLatch(1);
		assertDoesNotThrow(() -> store.refresh(checked::countDown));
		assertFalse(checked.await(200, TimeUnit.MILLISECONDS));
	}
}
