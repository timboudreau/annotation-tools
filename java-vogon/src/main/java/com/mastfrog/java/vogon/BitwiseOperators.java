/*
 * The MIT License
 *
 * Copyright 2020 Mastfrog Technologies.
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

import com.mastfrog.code.generation.common.LinesBuilder;

/**
 *
 * @author Tim Boudreau
 */
enum BitwiseOperators implements ClassBuilder.Operator {
    OR("|"),
    AND("&"),
    XOR("^"),
    COMPLEMENT("~"),
    SHIFT_LEFT("<<"),
    SHIFT_RIGHT(">>"),
    ROTATE(">>>");

    private final String stringRep;

    private BitwiseOperators(String stringRep) {
        this.stringRep = stringRep;
    }

    @Override
    public void generateInto(LinesBuilder lines) {
        lines.word(toString() + " ");
    }

    @Override
    public String toString() {
        return stringRep;
    }

    @Override
    public boolean applicableTo(Number num) {
        return num instanceof Integer || num instanceof Long;
    }

}
