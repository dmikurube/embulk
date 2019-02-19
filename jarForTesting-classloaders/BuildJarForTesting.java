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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;

public class BuildJarForTesting {
    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            throw new RuntimeException("Too few arguments.");
        }
        final Path jarPath = Paths.get(args[0]);

        Files.createFile(
                jarPath,
                PosixFilePermissions.asFileAttribute(EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE)));

        final byte[] innerJarBytes = buildInnerJar();

        final JarBuilder jarBuilder = new JarBuilder();
        jarBuilder.addClass(ExampleJarClass1.class);
        jarBuilder.addResource("inner.jar", innerJarBytes);
        jarBuilder.build(jarPath);
    }

    private static byte[] buildInnerJar() throws Exception {
        final JarBuilder jarBuilder = new JarBuilder();
        jarBuilder.addClass(ExampleJarClass2.class);
        jarBuilder.addResource("innerinner.txt",
                               "The quick brown fox jumps over the lazy dog.".getBytes(StandardCharsets.UTF_8));

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        jarBuilder.build(output);
        return output.toByteArray();
    }
}
