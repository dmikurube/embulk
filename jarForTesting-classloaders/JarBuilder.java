/*
 * Copyright 2018 The Embulk Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.embulk.deps.classloaders;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class JarBuilder {
    public JarBuilder() {
        this.manifest = new Manifest();
        this.entries = new HashMap<String, Path>();
        this.entriesByteArray = new HashMap<String, byte[]>();
    }

    public void build(final Path pathToExistingFile) throws Exception {
        try (final OutputStream outputToExistingFile = Files.newOutputStream(pathToExistingFile)) {
            this.build(outputToExistingFile);
        }
    }

    public void build(final OutputStream outputStream) throws Exception {
        try (final JarOutputStream output = buildPluginJar(outputStream, this.manifest)) {
            for (String entryName : new TreeSet<String>(this.entries.keySet())) {
                final Path pathToRealFile = this.entries.get(entryName);
                if (pathToRealFile == null) {
                    final JarEntry entry = new JarEntry(entryName + "/");
                    entry.setMethod(JarEntry.STORED);
                    entry.setSize(0);
                    entry.setCrc(0);
                    output.putNextEntry(entry);
                    output.closeEntry();
                } else {
                    final JarEntry entry = new JarEntry(entryName);
                    output.putNextEntry(entry);
                    Files.copy(pathToRealFile, output);
                    output.closeEntry();
                }
            }
            for (String entryName : new TreeSet<String>(this.entriesByteArray.keySet())) {
                final byte[] bytes = this.entriesByteArray.get(entryName);
                final JarEntry entry = new JarEntry(entryName);
                output.putNextEntry(entry);
                output.write(bytes);
                output.closeEntry();
            }
        }
    }

    public void addClass(final Class<?> klass) throws Exception {
        final Path classFileRelativePath = getClassFileRelativePath(klass);
        final Path classFileFullPath = getClassFileFullPath(klass);
        this.addFile(classFileRelativePath.toString(), classFileFullPath);

        Path directoryPath = classFileRelativePath.getParent();
        while (directoryPath != null) {
            this.addDirectoryIfAbsent(directoryPath.toString());
            directoryPath = directoryPath.getParent();
        }
    }

    public void addResource(final String name, final byte[] bytes) {
        this.entriesByteArray.put(name, bytes);
    }

    private void addDirectoryIfAbsent(final String name) {
        if (!(this.entries.containsKey(name))) {
            this.entries.put(name, null);
        }
    }

    private void addFile(final String name, final Path pathToRealFile) {
        this.entries.put(name, pathToRealFile);
    }

    private JarOutputStream buildPluginJar(final OutputStream outputStream, final Manifest embeddedManifest)
            throws Exception {
        return new JarOutputStream(outputStream, embeddedManifest);
    }

    private Path getClassFileRelativePath(final Class<?> klass) {
        return Paths.get(klass.getName().replace('.', '/') + ".class");
    }

    private Path getClassFileFullPath(final Class<?> klass) throws Exception {
        return Paths.get(klass.getClassLoader().getResource(klass.getName().replace('.', '/') + ".class").toURI());
    }

    private final Manifest manifest;
    private final HashMap<String, Path> entries;
    private final HashMap<String, byte[]> entriesByteArray;
}
