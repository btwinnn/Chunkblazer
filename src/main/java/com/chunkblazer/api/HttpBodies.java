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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.ResponseBody;

/**
 * Reads HTTP response bodies through a hard ceiling, so a hostile or compromised server
 * can't exhaust the client's memory. Complements the stores' on-disk protections:
 * sandboxed {@link net.runelite.client.util.Filepath} writes, sha256 content
 * verification and an LRU disk cap.
 */
final class HttpBodies
{
	private HttpBodies()
	{
	}

	/**
	 * Read a response body fully into memory, but never more than {@code maxBytes}.
	 *
	 * <p>{@code Content-Length} is only a cheap early-out and is never trusted, so the
	 * ceiling is also enforced during the read. Callers treat the {@link IOException} like
	 * any failed fetch and keep the last-good cache.
	 *
	 * @param body     the response body, or null
	 * @param maxBytes the hard ceiling on bytes read
	 * @return the full body, guaranteed no larger than {@code maxBytes}
	 * @throws IOException if the body is null, advertises a length over the ceiling,
	 *                     or streams more than the ceiling
	 */
	static byte[] readBounded(ResponseBody body, long maxBytes) throws IOException
	{
		if (body == null)
		{
			throw new IOException("no response body");
		}
		long advertised = body.contentLength();
		if (advertised > maxBytes)
		{
			throw new IOException("response too large: " + advertised + " > " + maxBytes);
		}
		try (InputStream in = body.byteStream())
		{
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			long total = 0;
			int n;
			while ((n = in.read(chunk)) != -1)
			{
				total += n;
				if (total > maxBytes)
				{
					throw new IOException("response exceeded " + maxBytes + " bytes");
				}
				buf.write(chunk, 0, n);
			}
			return buf.toByteArray();
		}
	}
}
