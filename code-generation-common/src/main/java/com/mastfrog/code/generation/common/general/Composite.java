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
import java.util.List;
import com.mastfrog.code.generation.common.CodeGenerator;

/**
 * A generic BodyBuilder which encapsulates a bunch of child BodyBuilders but
 * has no content of its own.
 *
 * @author timb
 */
public final class Composite extends CodeGeneratorBase {

    final CodeGenerator[] contents;

    public Composite(CodeGenerator... all) {
        this.contents = all;
    }

    public Composite(List<? extends CodeGenerator> all) {
        this.contents = all.toArray(new CodeGenerator[all.size()]);
    }

    public boolean isEmpty() {
        return contents.length == 0;
    }

    public static Composite of(List<CodeGenerator> all) {
        return new Composite(all.toArray(new CodeGenerator[all.size()]));
    }

    @Override
    public void generateInto(LinesBuilder lines) {
        for (CodeGenerator content : contents) {
            content.generateInto(lines);
        }
    }

}
