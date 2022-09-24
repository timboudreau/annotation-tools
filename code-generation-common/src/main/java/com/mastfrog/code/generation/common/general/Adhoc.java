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
package com.mastfrog.code.generation.common.general;

import com.mastfrog.code.generation.common.LinesBuilder;
import com.mastfrog.code.generation.common.util.Utils;
import java.util.Objects;

/**
 * BodyBuilder for a single word, which simply uses LinesBuilder.word() to emit
 * its contents.
 *
 * @author Tim Boudreau
 */
public final class Adhoc extends CodeGeneratorBase {

    private final String what;
    private boolean hangingWrap;

    public Adhoc(String what) {
        this(what, true);
    }

    public Adhoc(String what, boolean hangingWrap) {
        this.what = Utils.notNull("what", what);
    }

    @Override
    public String toString() {
        return what;
    }

    @Override
    public void generateInto(LinesBuilder lines) {
        lines.word(what, hangingWrap);
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 67 * hash + Objects.hashCode(this.what);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Adhoc other = (Adhoc) obj;
        return Objects.equals(this.what, other.what);
    }

}
