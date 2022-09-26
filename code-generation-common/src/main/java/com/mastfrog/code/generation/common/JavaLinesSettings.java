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

/**
 *
 * @author Tim Boudreau
 */
final class JavaLinesSettings implements LinesSettings {
    
    private int wrapPoint;
    private int indentBy;
    
    public JavaLinesSettings(int wrapPoint, int indentBy) {
        if (wrapPoint <= 1) {
            throw new IllegalArgumentException("Line length too short: " + wrapPoint);
        }
        if (indentBy <0) {
            throw new IllegalArgumentException("Negative indent: " + indentBy);
        }
        this.wrapPoint = wrapPoint;
        this.indentBy = indentBy;
    }
    
    public JavaLinesSettings() {
        this.wrapPoint = 80;
        this.indentBy = 4;
    }
    
    public String lineCommentPrefix() {
        return "// ";
    }
    
    @Override
    public int indentBy() {
        return indentBy;
    }

    @Override
    public int wrapPoint() {
        return wrapPoint;
    }
    
    @Override
    public boolean indicatesNewlineNeededBeforeNextStatement(char c) {
        switch (c) {
            case ';':
            case '}':
                return true;
            default:
                return false;
        }
    }

    @Override
    public char blockOpen() {
        return '{';
    }

    @Override
    public char blockClose() {
        return '}';
    }

    @Override
    public String stringLiteralQuote() {
        return "\"";
    }

    @Override
    public String escapeStringLiteral(String lit) {
        return LinesBuilder.escape(lit);
    }

    @Override
    public String escapeCharLitera(char c) {
        return LinesBuilder.escapeCharLiteral(c);
    }

    @Override
    public boolean isDelimiterPairOpening(char c) {
        switch (c) {
            case '[':
            case '(':
            case '{':
            case '<':
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean isBackupABeforeAppendRaw(char c) {
        switch (c) {
            case ',':
            case ';':
                return true;
            default:
                return false;
        }
    }

    @Override
    public char statementTerminator() {
        return ';';
    }
    
}
