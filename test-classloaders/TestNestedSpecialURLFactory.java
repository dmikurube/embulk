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

import static org.junit.Assert.assertEquals;

import java.io.InputStream;
import java.net.URL;
import org.junit.Test;

@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
public class TestNestedSpecialURLFactory {
    @Test
    public void testSimple() throws Exception {
        final NestedSpecialURLFactory factory =
                NestedSpecialURLFactory.ofFileURL(new URL("file:///path/to/top.jar"), "spproto");
        final URL url = factory.create("/path2/to/contained.jar", "/path3/to/resource.file");
        assertEquals("spproto:jar:file:/path/to/top.jar!/path2/to/contained.jar!/path3/to/resource.file",
                     url.toString());
        assertEquals("jar:file:/path/to/top.jar!/path2/to/contained.jar!/path3/to/resource.file", url.getFile());
        assertEquals("", url.getHost());
        assertEquals("jar:file:/path/to/top.jar!/path2/to/contained.jar!/path3/to/resource.file", url.getPath());
        assertEquals(-1, url.getPort());
        assertEquals("spproto", url.getProtocol());
        assertEquals(null, url.getQuery());
        assertEquals(null, url.getRef());
        assertEquals(null, url.getUserInfo());
    }

    @Test
    public void testInsideWithDifferentProtocol() throws Exception {
        final URL topJarUrl = getResourceUrl("jarForTesting1.jar");
        final NestedSpecialURLFactory factory = NestedSpecialURLFactory.ofFileURL(topJarUrl, "sp");
        final URL url = factory.create("inner.jar", "innerinner.txt");

        final String expectedPath = String.format("jar:file:%s!/inner.jar!/innerinner.txt", topJarUrl.getPath());
        assertEquals("sp:" + expectedPath, url.toString());
        assertEquals(expectedPath, url.getFile());
        assertEquals("", url.getHost());
        assertEquals(expectedPath, url.getPath());
        assertEquals(-1, url.getPort());
        assertEquals("sp", url.getProtocol());
        assertEquals(null, url.getQuery());
        assertEquals(null, url.getRef());
        assertEquals(null, url.getUserInfo());

        final InputStream innerInnerInputStream = url.openStream();
        final byte[] buffer = new byte[1024];
        final int lengthRead = innerInnerInputStream.read(buffer);

        final String actualContent = new String(buffer, 0, lengthRead);
        assertEquals("The quick brown fox jumps over the lazy dog.", actualContent);
    }

    @Test
    public void testInsideWithDuplicatedJarProtocol() throws Exception {
        final URL topJarUrl = getResourceUrl("jarForTesting1.jar");
        final NestedSpecialURLFactory factory = NestedSpecialURLFactory.ofFileURL(topJarUrl);
        final URL url = factory.create("inner.jar", "innerinner.txt");

        final String expectedPath = String.format("jar:file:%s!/inner.jar!/innerinner.txt", topJarUrl.getPath());
        assertEquals("jar:" + expectedPath, url.toString());
        assertEquals(expectedPath, url.getFile());
        assertEquals("", url.getHost());
        assertEquals(expectedPath, url.getPath());
        assertEquals(-1, url.getPort());
        assertEquals("jar", url.getProtocol());
        assertEquals(null, url.getQuery());
        assertEquals(null, url.getRef());
        assertEquals(null, url.getUserInfo());

        final InputStream innerInnerInputStream = url.openStream();
        final byte[] buffer = new byte[1024];
        final int lengthRead = innerInnerInputStream.read(buffer);

        final String actualContent = new String(buffer, 0, lengthRead);
        assertEquals("The quick brown fox jumps over the lazy dog.", actualContent);
    }

    private static URL getResourceUrl(final String resourceName) throws Exception {
        return TestNestedSpecialURLFactory.class.getClassLoader().getResource(resourceName);
    }
}
