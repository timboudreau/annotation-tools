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

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Tim Boudreau
 */
public class TestMultiAnnotationArguments {

    @Test
    public void testSimpleArgs() {
        ClassBuilder<String> cb = ClassBuilder.forPackage("org.wug").named("AnnoThing");
        cb.constructor(con -> {
            con.addMultiAnnotatedArgument("Wug")
                    .addArgument("type", String.class)
                    .addArgument("moo", 23)
                    .closeAnnotation()
                    .annotatedWith("Floog")
                    .addArgument("foo", true)
                    .closeAnnotation()
                    .closeAnnotations()
                    .named("buzzle")
                    .ofType("Thing")
                    .body(bb -> {
                        bb.invoke("println").withArgument("buzzle").onField("out").of("System");
                    });
        });

        String s = cb.build();

        assertTrue(s.contains("@Wug(type = java.lang.String.class, moo = 23)"));
        assertTrue(s.contains("@Floog(foo = true)"));
        assertTrue(s.contains("buzzle"));
    }

    @Test
    public void testClosures() {
        ClassBuilder<String> cb = ClassBuilder.forPackage("org.wug").named("AnnoThing");
        cb.constructor(con -> {
            con.addMultiAnnotatedArgument("Wug", firstAnno -> {
                firstAnno.addArgument("type", String.class)
                        .addArgument("moo", 23)
                        .closeAnnotation()
                        .annotatedWith("Floog", secondAnno -> {
                            secondAnno.addArgument("foo", true);
                        })
                        .closeAnnotations()
                        .named("buzzle")
                        .ofType("HooHa");
            }).body(bb -> {
                bb.invoke("println").withArgument("buzzle").onField("out").of("System");
            });
        });

        String s = cb.build();

        System.out.println(s);

        assertTrue(s.contains("@Wug(type = java.lang.String.class, moo = 23)"));
        assertTrue(s.contains("@Floog(foo = true)"));
        assertTrue(s.contains("buzzle"));
    }
}
