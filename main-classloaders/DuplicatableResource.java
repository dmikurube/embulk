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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

class DuplicatableResource extends AbstractResource {
    DuplicatableResource(final LinkedHashMap<String, ExclusiveResource> singulars) {
        this.singulars = Collections.unmodifiableMap(singulars);
    }

    DuplicatableResource(final String origin, final ExclusiveResource singular) {
        final LinkedHashMap<String, ExclusiveResource> singulars = new LinkedHashMap<>();
        singulars.put(origin, singular);
        this.singulars = Collections.unmodifiableMap(singulars);
    }

    @Override
    protected final boolean isDuplicatable() {
        return true;
    }

    DuplicatableResource withAnotherOrigin(final String origin, final ExclusiveResource singular) {
        final LinkedHashMap<String, ExclusiveResource> originsBuilt = new LinkedHashMap<>(this.singulars);
        // TODO: Check duplication of origin.
        originsBuilt.put(origin, singular);
        return new DuplicatableResource(originsBuilt);
    }

    final Map<String, ExclusiveResource> singulars;
}
