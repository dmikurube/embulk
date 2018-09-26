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
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * A ClassLoader for JAR file resources contained in a JAR file.
 *
 * <p>It loads all the contained JAR resource contents (just as byte sequences) onto memory when instantiating
 * though they are not loaded as Java classes. Keep it in mind if a large JAR file resource is contained.
 *
 * <p>Resources with duplicated names are permitted only under {@code META-INF/}.
 */
class SelfContainedDependencyClassLoader extends DependencyClassLoader {
    private SelfContainedDependencyClassLoader(
            final ClassLoader containerJarClassLoader,
            final URL codeSourceUrlBase,
            final List<String> jarResourceNames,
            final NestedSpecialURLFactory urlFactory,
            final Map<String, AbstractResource> resourceContents,
            final Map<String, Manifest> manifests,
            final Collection<Throwable> exceptions) {
        super(containerJarClassLoader);
        this.containerJarClassLoader = containerJarClassLoader;
        this.codeSourceUrlBase = codeSourceUrlBase;
        this.jarResourceNames = jarResourceNames;
        this.urlFactory = urlFactory;
        this.resourceContents = resourceContents;
        this.manifests = manifests;
        this.exceptions = exceptions;
        this.accessControlContext = AccessController.getContext();
    }

    static SelfContainedDependencyClassLoader of(
            final URLClassLoader containerJarClassLoader,  // It must be URLClassLoader to get a base code source URL.
            final List<String> jarResourceNames)
            throws IOException, ContainerJarException, UnacceptableDuplicatedResourceException {
        final URL[] classLoaderUrls = containerJarClassLoader.getURLs();
        if (classLoaderUrls.length == 0) {
            throw new ContainerJarException("No code source URLs in the container ClassLoader.");
        } else if (classLoaderUrls.length > 1) {
            throw new ContainerJarException("Multiple code source URLs in the container ClassLoader.");
        }
        final URL classLoaderUrl = classLoaderUrls[0];

        final URL codeSourceUrlBase;
        final String protocol = classLoaderUrl.getProtocol();
        if ("file".equals(protocol)) {
            codeSourceUrlBase = new URL("jar", "", -1, classLoaderUrl.toString() + "!/");
        } else if ("jar".equals(protocol)) {
            final URL innerUrl;
            try {
                innerUrl = new URL(classLoaderUrl.getPath());
            } catch (MalformedURLException ex) {
                throw new ContainerJarException("The code source URL is invalid: " + classLoaderUrl, ex);
            }

            final String innerProtocol = innerUrl.getProtocol();
            if (!"file".equals(innerProtocol)) {
                throw new ContainerJarException("The code source URL is invalid: " + classLoaderUrl);
            }
            final String innerPath = innerUrl.getPath();
            if (innerPath.indexOf("!/") < innerPath.length() - 2) {  // If it contains "!/" not at last.
                throw new ContainerJarException("The code source URL is invalid: " + classLoaderUrl);
            }
            codeSourceUrlBase = new URL(
                    "jar", "", -1, innerPath.endsWith("!/") ? innerUrl.toString() : innerUrl.toString() + "!/");
        } else {
            throw new ContainerJarException("The code source URL is invalid: " + classLoaderUrl);
        }

        final ExtractedContents extracted = extractContainedJars(
                containerJarClassLoader,
                codeSourceUrlBase,
                jarResourceNames);

        return new SelfContainedDependencyClassLoader(
                containerJarClassLoader,
                codeSourceUrlBase,
                jarResourceNames,
                NestedSpecialURLFactory.ofJarFileURL(codeSourceUrlBase, "jar"),
                extracted.resources,
                extracted.manifests,
                extracted.exceptions);
    }

    /**
     * Finds the class with the specified binary name.
     *
     * <p>It should not be called when the class has already been loaded. The default {@code loadClass} checks
     * if the class has already been loaded before calling {@code findClass}.
     */
    @Override
    protected Class<?> findClass(final String className) throws ClassNotFoundException {
        try {
            return AccessController.doPrivileged(
                new PrivilegedExceptionAction<Class<?>>() {
                    @Override
                    public Class<?> run() throws ClassNotFoundException {
                        try {
                            return defineClassFromExtractedResources(className);
                        } catch (ClassNotFoundException | LinkageError | ClassCastException ex) {
                            throw ex;
                        } catch (Throwable ex) {
                            // Found a resource in the container JAR, but failed to load it as a class.
                            throw new ClassNotFoundException(className, ex);
                        }
                    }
                },
                this.accessControlContext);
        } catch (PrivilegedActionException ex) {
            final Throwable internalException = ex.getException();
            if (internalException instanceof ClassNotFoundException) {
                throw (ClassNotFoundException) internalException;
            }
            if (internalException instanceof LinkageError) {
                throw (LinkageError) internalException;
            }
            if (internalException instanceof ClassCastException) {
                throw (ClassCastException) internalException;
            }
            throw new ClassNotFoundException(className, ex);
        }
    }

    /**
     * Finds a resource recognized as the given name from given JARs and JARs in the given JAR.
     *
     * Resources directly in the given JARs are always prioritized. Only if no such a resource is found
     * directly in the given JAR, it tries to find the resource in JARs in the given JAR.
     *
     * Note that URLClassLoader#findResource is public while ClassLoader#findResource is protected.
     *
     * @param resourceName
     * @return URL of the resource
     */
    @Override
    protected URL findResource(final String resourceName) {
        // TODO: Consider duplicated resources.
        final AbstractResource resource = this.resourceContents.get(resourceName);
        if (resource == null) {
            return null;
        }

        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<URL>() {
                    @Override
                    public URL run() throws MalformedURLException {
                        if (resource.isDuplicatable()) {
                            // TODO:
                            throw new RuntimeException("");
                        }
                        return urlFactory.create(resource.asExclusive().resourceNameOfJarInJar, resourceName);
                    }
                }, this.accessControlContext);
        } catch (PrivilegedActionException ignored) {
            // Passing through intentionally.
        }
        return null;
    }

    /**
     * Finds resources recognized as the given name from given JARs and JARs in the given JAR.
     *
     * Resources directly in the given JARs precede. Resources in JARs in the given JAR follow resources
     * directly in the given JARs.
     *
     * Note that URLClassLoader#findResources is public while ClassLoader#findResources is protected.
     */
    /*
    @Override
    protected Enumeration<URL> findResources(final String resourceName) throws IOException {
        final ResourceContent resource = this.resourceContents.get(resourceName);
        if (resource == null) {
            return null;
        }

        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<Enumeration<URL>>() {
                    @Override
                    public Enumeration<URL> run() throws IOException {
                        final Vector<URL> urls = new Vector<URL>();
                        if (resource.origins == null) {
                            urls.
                                return urlFactory.create(resource.resourceNameOfJarInJar, resourceName);
                        } else {
                        }
                        return urls.elements();
                    }
                },
                this.accessControlContext);
            urls.addAll(childUrls);
        } catch (PrivilegedActionException ignored) {
            // Passing through intentionally.
        }

        return urls.elements();
    }
    */

    /*
    @Override
    public URL getResource(String name) {
        boolean childFirst = isParentFirstPath(name);

        if (childFirst) {
            URL childUrl = findResource(name);
            if (childUrl != null) {
                return childUrl;
            }
        }

        URL parentUrl = getParent().getResource(name);
        if (parentUrl != null) {
            return parentUrl;
        }

        if (!childFirst) {
            URL childUrl = findResource(name);
            if (childUrl != null) {
                return childUrl;
            }
        }

        return null;
    }

    @Override
    public InputStream getResourceAsStream(final String resourceName) {
        final boolean childFirst = isParentFirstPath(resourceName);

        if (childFirst) {
            final InputStream childInputStream = getResourceAsStreamFromChild(resourceName);
            if (childInputStream != null) {
                return childInputStream;
            }
        }

        final InputStream parentInputStream = getParent().getResourceAsStream(resourceName);
        if (parentInputStream != null) {
            return parentInputStream;
        }

        if (!childFirst) {
            final InputStream childInputStream = getResourceAsStreamFromChild(resourceName);
            if (childInputStream != null) {
                return childInputStream;
            }
        }

        return null;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<Iterator<URL>> resources = new ArrayList<>();

        boolean parentFirst = isParentFirstPath(name);

        if (!parentFirst) {
            Iterator<URL> childResources = Iterators.forEnumeration(findResources(name));
            resources.add(childResources);
        }

        Iterator<URL> parentResources = Iterators.forEnumeration(getParent().getResources(name));
        resources.add(parentResources);

        if (parentFirst) {
            Iterator<URL> childResources = Iterators.forEnumeration(findResources(name));
            resources.add(childResources);
        }

        return Iterators.asEnumeration(Iterators.concat(resources.iterator()));
    }
    */

    private Class<?> defineClassFromExtractedResources(final String className)
            throws ClassNotFoundException {
        final String resourceName = className.replace('.', '/').concat(".class");
        final int indexLastPeriod = className.lastIndexOf('.');
        // Class must be singular.
        final ExclusiveResource resource = this.resourceContents.get(resourceName).asExclusive();
        if (resource == null) {
            throw new ClassNotFoundException(className);
        }
        final URL codeSourceUrl = resource.codeSourceUrl;

        if (indexLastPeriod != -1) {  // If |className| has a package part.
            final String packageName = className.substring(0, indexLastPeriod);
            final Manifest manifest = resource.manifest;  // Class must be singular.

            if (!this.checkPackageSealing(packageName, manifest, codeSourceUrl)) {
                try {
                    if (manifest != null) {
                        this.definePackageFromManifest(packageName, manifest, codeSourceUrl);
                    } else {
                        this.definePackage(packageName, null, null, null, null, null, null, null);
                    }
                } catch (IllegalArgumentException ex) {
                    if (!this.checkPackageSealing(packageName, manifest, codeSourceUrl)) {
                        throw new RuntimeException(
                                "FATAL: Unexpected double failures to define package: " + packageName, ex);
                    }
                }
            }
        }

        final CodeSource codeSource = new CodeSource(codeSourceUrl, resource.codeSigners);
        return this.defineClass(className, resource.bytes, 0, resource.bytes.length, codeSource);
    }

    private boolean checkPackageSealing(final String packageName, final Manifest manifest, final URL url) {
        final Package packageInstance = this.getPackage(packageName);

        if (packageInstance == null) {
            return false;
        }

        if (packageInstance.isSealed()) {
            if (!packageInstance.isSealed(url)) {
                throw new SecurityException(String.format(
                        "Package \"%s\" is already loaded, and sealed with a different code source URL.", packageName));
            }
        } else {
            if ((manifest != null) && isManifestToSeal(packageName, manifest)) {
                throw new SecurityException(String.format(
                        "Package \"%s\" is already loaded, and unsealed.", packageName));
            }
        }
        return true;
    }

    private static boolean isManifestToSeal(final String packageName, final Manifest manifest) {
        final Attributes perEntryAttributes = manifest.getAttributes(packageName.replace('.', '/').concat("/"));
        final Attributes mainAttributes = manifest.getMainAttributes();

        return "true".equalsIgnoreCase(
                (String) (perEntryAttributes.getOrDefault(
                              Attributes.Name.SEALED,
                              mainAttributes.getValue(Attributes.Name.SEALED))));
    }

    private Package definePackageFromManifest(
            final String packageName,
            final Manifest manifest,
            final URL codeSourceUrl)
            throws IllegalArgumentException {
        // https://docs.oracle.com/javase/8/docs/technotes/guides/jar/jar.html#Per-Entry_Attributes
        final Attributes perEntryAttributes = manifest.getAttributes(packageName.replace('.', '/').concat("/"));
        final Attributes mainAttributes = manifest.getMainAttributes();

        return this.definePackage(
                packageName,
                (String) perEntryAttributes.getOrDefault(
                        Attributes.Name.SPECIFICATION_TITLE,
                        mainAttributes.getValue(Attributes.Name.SPECIFICATION_TITLE)),
                (String) perEntryAttributes.getOrDefault(
                        Attributes.Name.SPECIFICATION_VERSION,
                        mainAttributes.getValue(Attributes.Name.SPECIFICATION_VERSION)),
                (String) perEntryAttributes.getOrDefault(
                        Attributes.Name.SPECIFICATION_VENDOR,
                        mainAttributes.getValue(Attributes.Name.SPECIFICATION_VENDOR)),
                (String) perEntryAttributes.getOrDefault(
                        Attributes.Name.IMPLEMENTATION_TITLE,
                        mainAttributes.getValue(Attributes.Name.IMPLEMENTATION_TITLE)),
                (String) perEntryAttributes.getOrDefault(
                        Attributes.Name.IMPLEMENTATION_VERSION,
                        mainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION)),
                (String) perEntryAttributes.getOrDefault(
                        Attributes.Name.IMPLEMENTATION_VENDOR,
                        mainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VENDOR)),
                "true".equalsIgnoreCase(
                        (String) (perEntryAttributes.getOrDefault(
                                      Attributes.Name.SEALED,
                                      mainAttributes.getValue(Attributes.Name.SEALED))))
                        ? codeSourceUrl
                        : null);
    }

    private static class ExtractedContents {
        private ExtractedContents(
                final Map<String, AbstractResource> resources,
                final Map<String, Manifest> manifests,
                final Collection<Throwable> exceptions) {
            this.resources = resources;
            this.manifests = manifests;
            this.exceptions = exceptions;
        }

        public final Map<String, AbstractResource> resources;
        public final Map<String, Manifest> manifests;
        public final Collection<Throwable> exceptions;
    }

    private static ExtractedContents extractContainedJars(
            final ClassLoader containerJarClassLoader,
            final URL codeSourceUrlBase,
            final List<String> jarResourceNames)
            throws IOException, UnacceptableDuplicatedResourceException {
        final HashMap<String, AbstractResource> resourceContents = new HashMap<>();
        final LinkedHashMap<String, Manifest> manifestsBuilt = new LinkedHashMap<>();
        final ArrayList<Throwable> exceptions = new ArrayList<>();

        for (final String jarResourceName : jarResourceNames) {
            // TODO: Better to read as JAR, not with getResourceAsStream?
            final InputStream inputStream = containerJarClassLoader.getResourceAsStream(jarResourceName);
            if (inputStream == null) {
                exceptions.add(new Exception(String.format("%s is not contained.", jarResourceName)));
                continue;
            }

            final JarInputStream jarInputStream;
            try {
                jarInputStream = new JarInputStream(inputStream, false);
            } catch (IOException ex) {
                exceptions.add(new Exception(String.format("%s is invalid.", jarResourceName)));
                continue;
            }
            final Manifest manifest = jarInputStream.getManifest();
            manifestsBuilt.put(jarResourceName, manifest);
            extractContainedJar(jarInputStream, codeSourceUrlBase, jarResourceName, manifest, resourceContents);
        }

        return new ExtractedContents(Collections.unmodifiableMap(resourceContents),
                                     Collections.unmodifiableMap(manifestsBuilt),
                                     Collections.unmodifiableList(exceptions));
    }

    private static void extractContainedJar(
            final JarInputStream containedJarInputStream,
            final URL codeSourceUrlBase,
            final String jarResourceName,
            final Manifest manifest,
            final HashMap<String, AbstractResource> resourceContents)
            throws IOException, UnacceptableDuplicatedResourceException {
        final URL codeSourceJarResourceUrl = new URL(
                codeSourceUrlBase, jarResourceName.startsWith("/") ? jarResourceName.substring(1) : jarResourceName);

        final byte[] buffer = new byte[4096];

        JarEntry containedJarEntry;
        while ((containedJarEntry = (JarEntry) containedJarInputStream.getNextEntry()) != null) {
            if (containedJarEntry.isDirectory()) {
                continue;
            }
            final String entryName = containedJarEntry.getName();

            if (entryName.startsWith("META-INF")) {
                // Resources starting with "META-INF" can be duplicated.
                final byte[] resourceBytes = readAllBytes(containedJarInputStream, buffer);
                final CodeSigner[] codeSigners = containedJarEntry.getCodeSigners();
                final ExclusiveResource singular = new ExclusiveResource(
                                                          resourceBytes,
                                                          jarResourceName,
                                                          codeSourceJarResourceUrl,
                                                          codeSigners,
                                                          manifest);
                resourceContents.compute(
                        entryName,
                        (key, found) -> found == null
                                        ? new DuplicatableResource(jarResourceName, singular)
                                        : found.asDuplicatable().withAnotherOrigin(jarResourceName, singular));
            } else {
                // Resources not starting with "META-INF" are not allowed to duplicated.
                if (resourceContents.containsKey(entryName)) {
                    throw new UnacceptableDuplicatedResourceException(
                            String.format("FATAL: Duplicated resources in self-contained JARs: %s", entryName));
                }
                final byte[] resourceBytes = readAllBytes(containedJarInputStream, buffer);
                final CodeSigner[] codeSigners = containedJarEntry.getCodeSigners();
                resourceContents.put(
                        entryName,
                        new ExclusiveResource(
                                resourceBytes,
                                jarResourceName,
                                codeSourceJarResourceUrl,
                                codeSigners,
                                manifest));
            }
        }
    }

    private static byte[] readAllBytes(final InputStream input, final byte[] buffer) throws IOException {
        final ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            final int lengthRead = input.read(buffer);
            if (lengthRead < 0) {
                break;
            }
            result.write(buffer, 0, lengthRead);
        }
        return result.toByteArray();
    }

    private final ClassLoader containerJarClassLoader;
    private final URL codeSourceUrlBase;
    private final List<String> jarResourceNames;
    private final NestedSpecialURLFactory urlFactory;

    /** Maps in-JAR resource name to AbstractResource */
    private final Map<String, AbstractResource> resourceContents;

    /** Maps contained-JAR resource name to Manifest */
    private final Map<String, Manifest> manifests;

    private final Collection<Throwable> exceptions;

    private final AccessControlContext accessControlContext;
}
