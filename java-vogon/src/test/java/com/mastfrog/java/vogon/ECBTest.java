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

import java.util.Arrays;
import javax.lang.model.element.Modifier;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Tim Boudreau
 */
public class ECBTest {

    @Test
    public void testEnumConstantsWithConsumer() {
        ClassBuilder<String> cb = ClassBuilder.forPackage("com.foo")
                .named("EnumThing")
                .field("fnork", fb -> {
                    fb.withModifier(Modifier.FINAL, Modifier.PUBLIC, Modifier.STATIC)
                            .initializedWith("Snurkles");
                })
                .enumConstants(ecb -> {
                    ecb.add("ONE")
                            .add("TWO").add("THREE");
                });

        String result = cb.toString();
        assertTrue(result.contains("ONE,"));
        assertTrue(result.contains("TWO,"));
        assertTrue(result.contains("THREE"));

        cb.enumConstants().add("FOUR").endEnumConstants();
        result = cb.toString();
        assertTrue(result.contains("FOUR"));

        cb.enumConstants().addWithArgs("FOOZ").withStringLiteral("Wookie").inScope();

        cb.enumConstants(ecb -> {
            ecb.addWithArgs("FLORK", ib -> {
                ib.withArgument(23);
            });
        });

        cb.field("glark").ofType("String");

        result = cb.build();
        assertTrue(result.contains("public static final String fnork = \"Snurkles\";"));
        assertTrue(result.contains("FLORK(23)"));
        assertTrue(result.contains("String glark;"));

        cb.annotatedWith("ActionBindings", ab -> {
            ab.addArgument("mimeType", "text/x-foo")
                    .addAnnotationArgument("bindings", "ActionBinding", actB -> {
                        actB.addExpressionArgument("action", "BuiltInAction.ToggleComment")
                                .addAnnotationArgument("bindings", "Keybinding", keyb -> {
                                    keyb.addExpressionArgument("modifiers", "KeyModifiers.CTRL_OR_COMMAND")
                                            .addExpressionArgument("key", "Key.SLASH");
                                    // should not produce a doubled annotation
                                    keyb.closeAnnotation();
                                });
                        // should be implicitly closed when we exit the closure
                    });
        });

        result = cb.toString();
        assertTrue(result.contains("(mimeType = \"text/x-foo\""));
        assertTrue(result.contains("modifiers = KeyModifiers.CTRL_OR_COMMAND"));

        cb.iteratively(Arrays.<String>asList("a", "b", "c"), (clb, str) -> {
            clb.field(str).ofType("int");
        });

        result = cb.toString();
        assertTrue(result.contains("int a"));
        assertTrue(result.contains("int b"));
        assertTrue(result.contains("int c"));
    }
}
