package org.embulk;

import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class EmbulkVersion
{
    private EmbulkVersion()
    {
    }

    // Expecting Embulk is always packaged in the embulk-cli jar whenever the Embulk version is checked in Java.
    static {
        VERSION = getImplementationVersion(getSelfJarManifest(), "[embulk-version-unavailable]");
    }

    private static Manifest getSelfJarManifest()
    {
        try {
            final ProtectionDomain protectionDomain;
            try {
                protectionDomain = EmbulkVersion.class.getProtectionDomain();
            }
            catch (SecurityException ex) {
                System.err.println("Embulk version unavailable due to ProtectionDomain inaccessible.");
                ex.printStackTrace();
                return null;
            }

            final CodeSource codeSource = protectionDomain.getCodeSource();
            if (codeSource == null) {
                System.err.println("Embulk version unavailable due to CodeSource unavailable.");
                return null;
            }

            final URL selfJarUrl = codeSource.getLocation();
            if (selfJarUrl == null) {
                System.err.println("Embulk version unavailable due to the location of CodeSource unavailable.");
                return null;
            }
            else if (!selfJarUrl.getProtocol().equals("file")) {
                System.err.println("Embulk version unavailable as the location of CodeSource is not local.");
                return null;
            }

            final String selfJarPathString = selfJarUrl.getPath();
            if (selfJarPathString == null) {
                System.err.println("Embulk version unavailable due to the path of CodeSource unavailable.");
                return null;
            }
            else if (selfJarPathString.isEmpty()) {
                System.err.println("Embulk version unavailable due to the path of CodeSource empty.");
                return null;
            }

            try (final JarFile selfJarFile = new JarFile(selfJarPathString)) {
                try {
                    return selfJarFile.getManifest();
                }
                catch (IllegalStateException ex) {
                    System.err.println("Embulk version unavailable due to the jar file closed unexpectedly.");
                    ex.printStackTrace();
                    return null;
                }
                catch (IOException ex) {
                    System.err.println("Embulk version unavailable due to failure to get the manifst in the jar file.");
                    ex.printStackTrace();
                    return null;
                }
            }
            catch (SecurityException ex) {
                System.err.println("Embulk version unavailable due to the jar file inaccessible.");
                ex.printStackTrace();
                return null;
            }
            catch (IOException ex) {
                System.err.println("Embulk version unavailable due to failure to access the jar file.");
                ex.printStackTrace();
                return null;
            }
        }
        catch (Throwable ex) {
            System.err.println("Embulk version unavailable due to an unknown exception.");
            ex.printStackTrace();
            return null;
        }
    }

    private static Manifest findEmbulkManifestFromAllResources()
    {
        final ClassLoader classLoader = EmbulkVersion.class.getClassLoader();
        final Collection<URL> manifestUrls;
        try {
            if (classLoader == null) {
                return null;
            }
            manifestUrls = Collections.list(classLoader.getResources(JarFile.MANIFEST_NAME));
        }
        catch (IOException ex) {
            System.err.println("Embulk version unavailable due to manifests unavailable.");
            ex.printStackTrace();
            return null;
        }
        catch (Exception ex) {
            System.err.println("Embulk version unavailable due to an unknown exception.");
            ex.printStackTrace();
            return null;
        }

        for (final URL manifestUrl : manifestUrls) {
            final URLConnection urlConnection;
            try {
                urlConnection = manifestUrl.openConnection();
            }
            catch (IOException ex) {
                continue;
            }

            try (final InputStream inputStream = urlConnection.getInputStream()) {
                final Manifest manifest = new Manifest(inputStream);
                final Attributes mainAttributes = manifest.getMainAttributes();
                final String implementationTitle = mainAttributes.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
                if ("embulk-cli".equals(implementationTitle)) {
                    return manifest;
                }
            }
            catch (IOException ex) {
                continue;
            }
        }
        return null;
    }

    private static String getImplementationVersion(final Manifest manifest, final String defaultVersion)
    {
        if (manifest == null) {
            return defaultVersion;
        }
        final Attributes mainAttributes = manifest.getMainAttributes();
        final String implementationVersion = mainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        if (implementationVersion == null) {
            System.err.println("Embulk version unavailable due to the manifest not containing Implementation-Version.");
            return defaultVersion;
        }
        return implementationVersion;
    }

    public static final String VERSION;
}
