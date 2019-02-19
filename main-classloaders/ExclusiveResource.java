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

import java.net.URL;
import java.security.CodeSigner;
import java.util.jar.Manifest;

class ExclusiveResource extends AbstractResource {
    ExclusiveResource(
            final byte[] bytes,
            final String resourceNameOfJarInJar,
            final URL codeSourceUrl,
            final CodeSigner[] codeSigners,
            final Manifest manifest) {
        this.bytes = bytes;
        this.resourceNameOfJarInJar = resourceNameOfJarInJar;
        this.codeSourceUrl = codeSourceUrl;
        this.codeSigners = codeSigners;
        this.manifest = manifest;
    }

    @Override
    protected final boolean isDuplicatable() {
        return false;
    }

    final byte[] bytes;
    final String resourceNameOfJarInJar;
    final URL codeSourceUrl;
    final CodeSigner[] codeSigners;
    final Manifest manifest;
}
