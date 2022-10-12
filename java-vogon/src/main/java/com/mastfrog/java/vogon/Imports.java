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
package com.mastfrog.java.vogon;

/**
 * Thing that can have Java imports added to it. Note that implementations will
 * NOT prevent imports from <code>java.lang</code> or the current package -
 * there are edge cases (duplicate names) where those are needed, and the caller
 * knows much more about what it is generating and whether or not such imports
 * are safe to elide.
 *
 * @author Tim Boudreau
 */
public interface Imports<T, C extends Imports<T, C>> {

    /**
     * Static-import a variable defined in this type (usually for use in an
     * annotation).
     *
     * @param importName An unqualified name of a field that exist or will be
     * defined for this class
     * @return this
     */
    C importStaticFromSelf(String importName);

    /**
     * Static-import a variable defined in this type (usually for use in an
     * annotation, or which reference inner enum types).
     *
     * @param firstImport An unqualified name of a field that exist or will be
     * defined for this class
     * @param next Additional names to import
     * @return the return type, usually this
     */
    C importStaticFromSelf(String firstImport, String... next);

    /**
     * Import a collection of string names.
     *
     * @param types The types
     * @return the return type, usually this
     */
    C importing(Iterable<? extends String> types);

    /**
     * Import classes, passing explcit class objects - useful when importing JDK
     * types, not a good idea for library types, since it forces the code
     * generator to have a hard dependency on the library in question, which is
     * usually not a good thing.
     *
     * @param className A class name
     * @param more Some optional additional class names
     * @return the return type, usually this
     */
    C importing(Class<?> className, Class<?>... more);

    /**
     * Import multiple fully qualified class names.
     *
     * @param className The first class name
     * @param more More class names
     * @return the return type, usually this
     * @throws IllegalArgumentException if a name is unqualified
     */
    C importing(String className, String... more);

}
