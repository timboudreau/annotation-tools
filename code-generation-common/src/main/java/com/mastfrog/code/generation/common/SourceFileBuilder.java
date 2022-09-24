/*
 * The MIT License
 *
 * Copyright 2022 Mastfrog Technologies.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.mastfrog.code.generation.common;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 *
 * @author Tim Boudreau
 */
public interface SourceFileBuilder extends CodeGenerator {

    /**
     * The name of the top level member of the class, such as a Java class name,
     * with no file extension.
     *
     * @return A name
     */
    String name();

    /**
     * The file extension files of this builder type should get.
     *
     * @return A file extension
     */
    String fileExtension();

    /**
     * Get the relative path within a source root where the source file
     * represented by this builder should be placed.
     *
     * @return A path
     */
    default Path sourceRootRelativePath() {
        String ext = fileExtension();
        if (ext.length() > 0 && ext.charAt(0) != '.') {
            ext = '.' + ext;
        }
        Path fileOnly = Paths.get(name() + ext);
        return namespaceRelativePath().map(nsPath -> nsPath.resolve(fileOnly)).orElse(fileOnly);
    }

    default String namespaceDelimiter() {
        return ".";
    }

    default Optional<Path> namespaceRelativePath() {
        return namespace().map(ns -> {
            Pattern pat = Pattern.compile(namespaceDelimiter(), Pattern.LITERAL);
            pat.matcher(ns).replaceAll(File.separator);
            return null;
        });
    }

    /**
     * An optional namespace, such as a java package.
     *
     * @return A namespace
     */
    Optional<String> namespace();

    /**
     * Save the output of this builder to its relative path relative to the
     * passed directory; parent folders will be created as needed. Uses UTF-8
     * encoding.
     *
     * @param sourceRoot The source root, never null
     * @return The path it was saved to
     * @throws IOException if something goes wrong
     */
    default Path save(Path sourceRoot) throws IOException {
        return save(sourceRoot, null);
    }

    /**
     * Save the output of this builder to its relative path relative to the
     * passed directory; parent folders will be created as needed.
     *
     * @param sourceRoot The source root, never null
     * @param charset The character set
     * @return The path it was saved to
     * @throws IOException if something goes wrong
     */
    default Path save(Path sourceRoot, Charset charset) throws IOException {
        String text = stringify();
        Path dest = sourceRoot.resolve(sourceRootRelativePath());
        if (!Files.exists(dest.getParent())) {
            Files.createDirectories(dest.getParent());
        }
        try ( OutputStream out = Files.newOutputStream(dest, WRITE, CREATE, TRUNCATE_EXISTING)) {
            out.write(text.getBytes(charset == null ? UTF_8 : charset));
        }
        return dest;
    }
}
