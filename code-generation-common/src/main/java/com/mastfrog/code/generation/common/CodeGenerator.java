/*
 * The MIT License
 *
 * Copyright 2019 Mastfrog Technologies.
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

import java.util.function.Supplier;

/**
 * Element of a source file, which can format itself into a LinesBuilder, which
 * handles formatting details and settable things like indentation spacings.
 *
 * @author Tim Boudreau
 */
public interface CodeGenerator {

    /**
     * Drive the passed LinesBuilder to append the contents of this element to
     * it.
     *
     * @param lines A LinesBuilder
     */
    void generateInto(LinesBuilder lines);

    public static CodeGenerator lazy(Supplier<CodeGenerator> s) {
        return lb -> {
            s.get().generateInto(lb);
        };
    }

    /**
     * Create a new LinesBuilder, preconfigured with default formatting settings
     * for the language being generated (subclasses should override this to
     * configure appropriately).
     *
     * @return A new LinesBuilder
     */
    default LinesBuilder newLinesBuilder() {
        return new LinesBuilder();
    }

    /**
     * Render this source element into a new LinesBuilder; useful in toString()
     * methods (but the interface cannot override that).
     *
     * @return A string
     */
    default String stringify() {
        LinesBuilder lb = newLinesBuilder();
        generateInto(lb);
        return lb.toString();
    }
}
