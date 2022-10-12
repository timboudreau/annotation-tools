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

import com.mastfrog.java.vogon.ClassBuilder.Value;
import static com.mastfrog.java.vogon.ClassBuilder.invocationOf;
import static com.mastfrog.java.vogon.ClassBuilder.number;
import static com.mastfrog.java.vogon.ClassBuilder.stringLiteral;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.lang.model.element.Modifier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Tim Boudreau
 */
public class TernaryTest {

    ClassBuilder<String> cb;

    @Test
    public void testIt() {
        cb.importing("static java.lang.System.out.println")
                .method("doStuff", mb -> {
                    mb.addArgument("String", "blah")
                            .withModifier(Modifier.PUBLIC)
                            .body(bb -> {
                                bb.invoke("println")
                                        .withArgument()
                                        .ternary().isNotNull("blah")
                                        .endCondition()
                                        .expression("blah")
                                        .literal("Things")
                                        .inScope();
                            });
                });
        assertTrue(contains(cb, "blah", "!=", "null", "?", "blah", ":", "\"Things\""),
                () -> words(cb).toString());
    }

    @Test
    public void testNumeric() {
        cb.importing("static java.lang.System.out.println")
                .method("doStuff", mb -> {
                    mb.addArgument("String", "blah")
                            .withModifier(Modifier.PUBLIC)
                            .body(bb -> {
                                bb.invoke("println")
                                        .withArgument()
                                        .ternary()
                                        .numericCondition("bloog")
                                        .bitwiseAnd()
                                        .literal(23)
                                        .endNumericExpression()
                                        .isNotEqualTo(0)
                                        .literal(52)
                                        .literal(3)
                                        .inScope();
                            });
                });
        assertTrue(contains(cb, "bloog", "&", "23", "!=", "0", "?", "52", ":", "3"),
                () -> words(cb).toString());
    }

    @Test
    public void testNumericLambda() {
        cb.importing("static java.lang.System.out.println")
                .method("doStuff", mb -> {
                    mb.addArgument("String", "blah")
                            .withModifier(Modifier.PUBLIC)
                            .body(bb -> {
                                bb.invoke("println")
                                        .withArgument()
                                        .ternary()
                                        .numericCondition("bloog", num -> {
                                            num.parenthesized().bitwiseAnd()
                                                    .literal(23) //                                                    .endNumericExpression()
                                                    ;

                                        })
                                        .isNotEqualTo(0)
                                        .literal(52)
                                        .literal(3)
                                        .inScope();
                            });
                });
        assertTrue(contains(cb, "bloog", "&", "23", "!=", "0", "?", "52", ":", "3"),
                () -> words(cb).toString());
    }

    @Test
    public void testLambdaInitializers() {
        cb.importing("static java.lang.System.out.println")
                .method("doStuff", mb -> {
                    mb.addArgument("String", "blah")
                            .withModifier(Modifier.PUBLIC)
                            .body(bb -> {
                                bb.declare("ploog")
                                        .initializedFromLambda(lb -> {
                                            lb.body(bb2 -> {
                                                bb2.invoke("println")
                                                        .withArgument("blah")
                                                        .inScope();
                                            });
                                        })
                                        .as("Runnable").lineComment("foo fru");
                            });
                });
        assertTrue(contains(cb, "ploog", "=", "->", "{", "println", "blah"),
                () -> words(cb).toString());
    }

    @Test
    public void testNewTests() {
        cb.importing("static java.lang.System.out.println")
                .method("doStuff", mb -> {
                    mb.addArgument("String", "blah")
                            .withModifier(Modifier.PUBLIC)
                            .body(bb -> {
                                bb.iff().literal(3).isEqualTo(veb -> {
                                    veb.field("poof").of("blah");
                                }).invoke("println").withStringLiteral("Moob")
                                        .inScope()
                                        .endIf();

                                bb.iff().literal(5).isNotEqualTo()
                                        .field("murg").of("blah")
                                        .invoke("println").withStringConcatentationArgument("murg").append(23).endConcatenation()
                                        .inScope().endIf();
                            });
                });
        assertTrue(contains(cb, "if", "3", "==", "blah.poof"));

        assertTrue(contains(cb, "if", "5", "!=", "blah.murg"));
    }

    @Test
    public void testValueTernary() {
        Value v = invocationOf("currentTimeMillis")
                .on("System")
                .modulo(number(2))
                .parenthesized()
                .isNotEqualTo(number(1))
                .ternary(stringLiteral("even"), stringLiteral("odd"));
        assertEquals("(System.currentTimeMillis()%2)!=1?\"even\":\"odd\"",
                v.toString().replaceAll("\\s+", ""));
    }

    private static boolean contains(ClassBuilder<String> cb, String... what) {
        return contains(words(cb), what);
    }

    private static boolean contains(List<String> strs, String... what) {
        List<String> s = Arrays.asList(what);
        for (int i = 0; i < strs.size() - (s.size()); i++) {
            List<String> test = strs.subList(i, i + s.size());
            if (test.equals(s)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> words(ClassBuilder<?> cb) {
        List<String> strs = new ArrayList<>();
        for (String s : cb.toString().split("[\\s()]+")) {
            strs.add(s);
        }
        return strs;
    }

    @BeforeEach
    public void before() {
        cb = ClassBuilder.forPackage("com.mastfoo").named("TernaryTestClass");
    }
}
