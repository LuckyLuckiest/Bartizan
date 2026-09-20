package org.luckyraven.bartizan.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeaponLoaderBundledNamesTest {

	@TempDir
	Path temp;

	@Test
	void listsOnlyTopLevelWeaponYamlInsideAJar() throws IOException {
		Path jar = temp.resolve("plugin.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			for (String entry : List.of("weapon/rifle.yml", "weapon/awp.yml", "weapon/README.txt",
			                            "weapon/sub/nested.yml", "items/ammunition.yml")) {
				out.putNextEntry(new JarEntry(entry));
				out.closeEntry();
			}
		}

		assertEquals(List.of("awp", "rifle"), WeaponLoader.bundledWeaponNames(jar.toUri().toURL()));
	}

	@Test
	void listsWeaponYamlInsideAClassesDirectory() throws IOException {
		Files.createDirectories(temp.resolve("weapon"));
		Files.writeString(temp.resolve("weapon/knife.yml"), "");
		Files.writeString(temp.resolve("weapon/notes.md"), "");

		assertEquals(List.of("knife"), WeaponLoader.bundledWeaponNames(temp.toUri().toURL()));
	}

	@Test
	void everyShippedDefaultIsExpected() {
		List<String> names = WeaponLoader.bundledWeaponNames(
				WeaponLoader.class.getProtectionDomain().getCodeSource().getLocation());

		assertEquals(24, names.size(), names.toString());
		assertTrue(names.containsAll(List.of("pistol", "awp", "golden_ak47", "arc_lance", "syringe_gun")));
	}

	@Test
	void missingCodeSourceIsEmpty() {
		assertEquals(List.of(), WeaponLoader.bundledWeaponNames(null));
	}
}
