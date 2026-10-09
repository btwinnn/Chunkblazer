package com.chunkblazer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every t("key") in the main source must exist in strings.properties, and every
 * key in the file must be used, so a typo can never show a raw key on screen.
 */
public class StringsTest
{
	private static final Pattern KEY_USE = Pattern.compile("\\bt\\(\\s*\"([^\"]+)\"");

	@Test
	public void everyUsedKeyExistsAndEveryKeyIsUsed() throws IOException
	{
		Properties props = new Properties();
		try (Reader reader = Files.newBufferedReader(
			Paths.get("src/main/resources/com/chunkblazer/strings.properties"), StandardCharsets.UTF_8))
		{
			props.load(reader);
		}

		Set<String> used = new TreeSet<>();
		try (Stream<Path> files = Files.walk(Paths.get("src/main/java")))
		{
			for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator)
			{
				Matcher m = KEY_USE.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
				while (m.find())
				{
					used.add(m.group(1));
				}
			}
		}

		Set<String> missing = new TreeSet<>(used);
		missing.removeAll(props.stringPropertyNames());
		assertTrue(missing.isEmpty(), "keys used in code but missing from strings.properties: " + missing);

		Set<String> unused = new TreeSet<>(props.stringPropertyNames());
		unused.removeAll(used);
		assertTrue(unused.isEmpty(), "keys in strings.properties never used in code: " + unused);
	}

	@Test
	public void formatsOnlyWhenArgsGiven()
	{
		// No args: returned as stored (an unknown key falls back to itself).
		assertEquals("no.such.key %s", Strings.t("no.such.key %s"));
	}
}
