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

import static com.mastfrog.java.vogon.ClassBuilder.forPackage;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Tim Boudreau
 */
public class ArgsTest {

    @Test
    public void testConstructorArgChecking() {
        ClassBuilder<String> cb = forPackage("boo.blah")
                .named("Foo");
        cb.constructor(con -> {
            con.addArgument("int", "i1")
                    .addArgument("int", "i2")
                    .addArgument("int", "i3")
                    .body(bb -> {
                        bb.lineComment("blah");
                    });
        });
        System.out.println(cb);

        assertTrue(cb.toString().replaceAll("\\s+", "")
                .contains("inti1,inti2,inti3"));

        try {
            cb.constructor(con -> {
                con.addArgument("int", "i")
                        .addArgument("int", "i")
                        .addArgument("int", "i")
                        .noBody();
            });

            fail("Exception should have been thrown.");
        } catch (IllegalStateException ise) {
            // ok
        }
    }

    @Test
    public void testMethodArgChecking() {
        ClassBuilder<String> cb = forPackage("boo.blah")
                .named("Foo");
        cb.method("foo", con -> {
            con.addArgument("int", "i1")
                    .addArgument("int", "i2")
                    .addArgument("int", "i3")
                    .body(bb -> {
                        bb.lineComment("blah");
                    });
        });
        System.out.println(cb);

        assertTrue(cb.toString().replaceAll("\\s+", "")
                .contains("inti1,inti2,inti3"));

        try {
            cb.method("bar", mth -> {
                mth.addArgument("int", "i")
                        .addArgument("int", "i")
                        .addArgument("int", "i")
                        .noBody();
            });

            fail("Exception should have been thrown.");
        } catch (IllegalStateException ise) {
            // ok
        }
    }

}
