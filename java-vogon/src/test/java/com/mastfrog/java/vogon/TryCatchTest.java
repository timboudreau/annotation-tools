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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Tim Boudreau
 */
public class TryCatchTest {

    private ClassBuilder<String> cb;

    @Test
    public void testTryCatch() {
        cb.method("whatever", mb -> {
            mb.body(bb -> {
                bb.trying(tri -> {
                    tri.invoke("println").withStringLiteral("Hello world").onField("out").of("System");
                    tri.catching(cb -> {
                        cb.invoke("printStackTrace").on("thrown");
                    }, "Exception", "Error");
//                    tri.fynalli(fi -> {
//                        fi.invoke("println").withStringLiteral("Goodbye world").onField("out").of("System");
//                    });
                });
            });
        });
        String txt = cb.build();
        assertTrue(txt.contains("Hello world"));
        assertTrue(txt.contains("Exception | Error"));
    }

    @Test
    public void testTryFinally() {
        cb.method("stuff", mb -> {
            mb.body(bb -> {
                bb.trying(tri -> {
                    tri.invoke("println").withStringLiteral("Hello world").onField("out").of("System");
                    tri.fynalli(fi -> {
                        fi.invoke("println").withStringLiteral("Goodbye world").onField("out").of("System");
                    });
                });
            });
        });
        String txt = cb.build();
        assertTrue(txt.contains("Hello world"));
        assertTrue(txt.contains("finally"));
        assertTrue(txt.contains("Goodbye world"));
    }

    @Test
    public void testTryCatchFinally() {
        cb.method("whatever", mb -> {
            mb.body(bb -> {
                bb.trying(tri -> {
                    tri.invoke("println").withStringLiteral("Hello world").onField("out").of("System");
                    tri.catching(cb -> {
                        cb.invoke("printStackTrace").on("thrown");
                    }, "Exception", "Error");
                    tri.fynalli(fi -> {
                        fi.invoke("println").withStringLiteral("Goodbye world").onField("out").of("System");
                    });
                });
            });
        });
        String txt = cb.build();
        assertTrue(txt.contains("printStackTrace"));
        assertTrue(txt.contains("Hello world"));
        assertTrue(txt.contains("Goodbye world"));
        assertTrue(txt.contains("catch"));
        assertTrue(txt.contains("Exception | Error"));
        assertTrue(txt.contains("finally"));
    }

    @Test
    public void tryCatchLogging1() {
        cb.method("whatever", mb -> {
            mb.body(bb -> {
                bb.trying(tri -> {
                    tri.invoke("println").withStringLiteral("Hello world").onField("out").of("System");
                    tri.catching(cb -> {
                        cb.log();
                    }, "Exception", "Error");
                    tri.fynalli(fi -> {
                        fi.invoke("println").withStringLiteral("Goodbye world").onField("out").of("System");
                    });
                });
            });
        });
        String txt = cb.build();
        assertTrue(txt.contains("static final Logger LOGGER"));
        assertTrue(txt.contains("LOGGER.log(Level.SEVERE, null, thrown)"));
    }

    @Test
    public void testMustBeValid() {
        try {
            cb.method("foo").body().trying().endBlock().endBlock();
            fail("Should not be able to closes a try block with no catch or finally blocks");
        } catch (IllegalStateException ex) {

        }
        System.out.println(cb.toString());
    }

    @Test
    public void testMustBeValidLambda() {
        cb.method("bar").body(bb -> {
            bb.trying(tri -> {
                tri.invoke("println").withStringLiteral("Uh oh").onField("out").of("System");
            });
        });
        String txt = cb.toString();
        assertTrue(txt.contains("finally"));
        assertTrue(txt.contains("did not have any catch or finally blocks"));
        assertTrue(txt.contains("Uh oh"));
    }

    @BeforeEach
    public void setup() {
        cb = ClassBuilder.forPackage("com.foo").named("Type");
    }
}
