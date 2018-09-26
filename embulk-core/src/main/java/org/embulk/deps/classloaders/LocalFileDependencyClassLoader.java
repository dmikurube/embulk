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

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;

class LocalFileDependencyClassLoader extends DependencyClassLoader {
    private LocalFileDependencyClassLoader(final URLClassLoader urlClassLoader) {
        super(urlClassLoader);
    }

    static LocalFileDependencyClassLoader ofUrl(
            final ClassLoader parentClassLoader,
            final Collection<URL> localJarUrls) {
        return new LocalFileDependencyClassLoader(new URLClassLoader(
                localJarUrls.toArray(new URL[localJarUrls.size()]),
                parentClassLoader));
    }

    static LocalFileDependencyClassLoader of(
            final ClassLoader parentClassLoader,
            final Collection<Path> localJarPaths) {
        final ArrayList<URL> localJarUrls = new ArrayList<>();
        for (final Path localJarPath : localJarPaths) {
            final URL url;
            try {
                url = localJarPath.toUri().toURL();
            } catch (MalformedURLException ex) {
                throw new RuntimeException(ex);
            }
            localJarUrls.add(url);
        }
        return ofUrl(parentClassLoader, localJarUrls);
    }
}
