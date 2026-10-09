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

package com.chunkblazer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Longer text (chat, panel, overlay and log messages) lives in strings.properties,
 * read once as UTF-8, to keep the Java source under the Plugin Hub's token cap.
 */
public final class Strings
{
	private static final Properties TEXT = new Properties();

	static
	{
		try (Reader reader = new InputStreamReader(Strings.class.getResourceAsStream("strings.properties"),
			StandardCharsets.UTF_8))
		{
			TEXT.load(reader);
		}
		catch (IOException e)
		{
			throw new ExceptionInInitializerError(e);
		}
	}

	private Strings()
	{
	}

	/**
	 * The text for a key, with any %s placeholders filled from args (String.format).
	 * Without args the text is returned as stored, so a literal % needs no escaping.
	 * Log templates keep SLF4J's {} placeholders and are passed to the logger as is.
	 */
	public static String t(String key, Object... args)
	{
		String text = TEXT.getProperty(key, key);
		return args.length == 0 ? text : String.format(text, args);
	}
}
