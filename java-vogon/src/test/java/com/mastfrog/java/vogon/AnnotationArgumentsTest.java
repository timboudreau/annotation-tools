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

import javax.lang.model.element.Modifier;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 *
 * @author timb
 */
public class AnnotationArgumentsTest {

    @Test
    public void testAnnotatedArguments() {
        ClassBuilder<String> cb = ClassBuilder.forPackage("mug.wug")
                .named("AnnoThing");

        cb.withModifier(Modifier.PUBLIC);

        cb.constructor(con -> {
            con.addAnnotatedArgument("JsonProperty")
                    .addArgument("required", true)
                    .addArgument("value", "something")
                    .closeAnnotation()
                    .ofType("Something")
                    .named("something")
                    .body(bb -> {
                        bb.assign("this.something").toExpression("something");
                    });
        });

        System.out.println("ONE");
        System.out.println(cb.build());
        assertTrue(cb.build().contains("@JsonProperty(required = true, value = \"something\")"));
    }

    @Test
    public void testAnnotatedArgumentsWithClosure() {
        ClassBuilder<String> cb = ClassBuilder.forPackage("mug.wug")
                .named("AnnoThing");

        cb.withModifier(Modifier.PUBLIC);

        cb.constructor(con -> {
            con.addAnnotatedArgument("JsonProperty", anno -> {
                anno.addArgument("required", true)
                        .addArgument("value", "something")
                        .closeAnnotation()
                        .ofType("Something")
                        .named("something");
            }).body(bb -> {
                bb.assign("this.something").toExpression("something");
            });
        });
        System.out.println("TWO");
        System.out.println(cb.build());
        assertTrue(cb.build().contains("@JsonProperty(required = true, value = \"something\")"));
    }

}
