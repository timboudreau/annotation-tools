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

import com.mastfrog.code.generation.common.CodeGenerator;
import com.mastfrog.code.generation.common.LinesBuilder;

/**
 *
 * @author Tim Boudreau
 */
public enum AssignmentOperator implements CodeGenerator {
    EQUALS("="),
    PLUS_EQUALS("+="),
    MINUS_EQUALS("-="),
    OR_EQUALS("|="),
    AND_EQUALS("&="),
    XOR_EQUALS("^="),
    MODULO_EQUALS("%="),
    DIVIDE_EQUALS("/="),
    MULTIPLY_EQUALS("*=");
    private final String s;

    private AssignmentOperator(String s) {
        this.s = s;
    }

    public boolean applicableToBoolean() {
        switch (this) {
            case EQUALS:
            case AND_EQUALS:
            case XOR_EQUALS:
            case OR_EQUALS:
                return true;
            default:
                return false;
        }
    }

    @Override
    public void generateInto(LinesBuilder lines) {
        lines.word(s).appendRaw(' ');
    }

    @Override
    public String toString() {
        return s;
    }

}
