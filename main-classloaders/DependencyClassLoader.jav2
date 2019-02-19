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

import java.security.SecureClassLoader;

public abstract class DependencyClassLoader extends SecureClassLoader {
    public DependencyClassLoader(final ClassLoader parentClassLoader) {
        super(parentClassLoader);
    }

    public static DependencyClassLoader of() {
        // TODO(dmikurube): Judge whether its self-contained or local file.
        if (true) {
            return SelfContainedDependencyClassLoader.of(
                containerJarClassLoader,  // It must be URLClassLoader to get a base code source URL.
                jarResourceNames /* List<String> */);
        } else {
            return LocalFileDependencyClassLoader.of(
                parentClassLoader,
                localJarPaths /* Collection<Path> */);
        }
    }
}
