/*
 * The MIT License
 *
 * Copyright 2019 Mastfrog Technologies.
 *
 * Permission is hereby granted, free of charge, toExpression any person obtaining a copy
 * of this software and associated documentation files (the "Software"), toExpression deal
 * in the Software without restriction, including without limitation the rights
 * toExpression use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and toExpression permit persons toExpression whom the Software is
 * furnished toExpression do so, subject toExpression the following conditions:
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

import com.mastfrog.java.vogon.ClassBuilder.TryBuilder;
import com.mastfrog.java.vogon.ClassBuilder.TypeAssignment;
import javax.lang.model.element.Modifier;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Tim Boudreau
 */
public class ClassBuilderTest {

    @Test
    public void testSomeMethod() {
        ClassBuilder<String> cb = ClassBuilder.forPackage(ClassBuilderTest.class.getPackage().getName())
                .named("Foo").withModifier(Modifier.PUBLIC, Modifier.FINAL);
        cb.field("moo").ofType("Object");
        cb.method("wookie").addArgument("String", "name").body(bb -> {

            bb.iff().literal(true).and().booleanExpression("3 == 3").invoke("println").withStringLiteral("foo")
                    .on("System.out").endIf();

            bb.declare("that").initializedTo().numeric().invoke("currentTimeMillis").on("System")
                    .parenthesized().times().literal(3).and(427L).plus().invoke("nanoTime").on("System").dividedBy(3)
                    .shiftLeft().literal(7).parenthesized().modulo(15).parenthesized()
                    .complement()
                    .endNumericExpression().as("long");

            bb.declare("fumper").initializedFromField("whoo", frb -> {
                frb.arrayElement(5).ofInvocationOf("getThings", gt -> {
                });
            }).as("String");

            bb.assign("moo").toTernary().booleanExpression("System.currentTimeMillis() % 2 == 0")
                    .expression("hey").ternary().booleanExpression("2>3").toArrayLiteral("String", alb -> {
                alb.literal("a").literal("b").literal("c");
            }).field("foo").arrayElement(3).arrayElement(5).of("bar");

            bb.declare("nums").as("int[]");

            bb.invokeConstructor(nb -> {
                nb.withNewInstanceArgument().withStringLiteral("foo foo fru")
                        .ofType("String").ofType("String");
            });

//            ClassBuilder.ComparisonBuilder<ClassBuilder.IfBuilder<ClassBuilder.BlockBuilder<T>>> x = bb.iff().literal(3).;
            // Should not be possible but is:
            bb.iff().literal('x').lessThan().literal(5).endCondition()
                    .endBlock();

            bb.iff().literal(3L).greaterThan().field("foo").of("this").endCondition()
                    .incrementVariable("bar").endIf();

            bb.iff(ib -> {
                ib.not().booleanExpression("1 == 1").
                        invoke("println").withStringLiteral("Everything is broken.").on("System.out")
                        .orElse(eb -> {
                            eb.invoke("println").withStringLiteral("Math still works").on("System.err");
                        });

                ib.booleanExpression("2 == 2").
                        invoke("println").withStringLiteral("Everything is broken.").on("System.out")
                        .elseIf(ib2 -> {
                            ib2.booleanExpression("3 == 3").invoke("exit").withArgument(1).on("System")
                                    .orElse(ecb -> {
                                        ecb.invoke("println").withStringLiteral("Math still works").on("System.err");
                                    });

                        });
            });
            bb.blankLine();
            bb.lineComment("Foo foo fru!");
            bb.iff().variable("foo").notEquals().literal("bar").endCondition()
                    .invoke("println").withStringLiteral("Hey there!").on("System.out")
                    .elseIf().variable("foo").equals().literal("baz")
                    .endCondition().invoke("println").withStringLiteral("Woo hoo!")
                    .on("System.out")
                    .elseIf().invocationOf("currentTypeMillis").on("System").greaterThanOrEqualto().literal(-20392020990990L).negated()
                    .invoke("println").withStringLiteral("It is time").on("System.out")
                    .orElse(elseBlock -> {
                        elseBlock.invoke("println").withStringLiteral("No dice")
                                .onField("out").of("System");
                    }).endBlock();
        });
    }

    @Test
    public void testInvocationWithNewArgBuilders() {
        ClassBuilder<String> cb = ClassBuilder.forPackage("com.foo").named("whatever")
                .method("foo").body(bb -> {
            bb.invoke("thing", ib -> {
                ib.withStringConcatentationArgument("called ").with().field("hey").ofThis()
                        .with().literal(" and then there is \"quoted\" stuff ")
                        .with().invoke("currentTimeMillis").on("System")
                        .endConcatenation().onThis();
                ;
            });
            bb.invoke("doSomething").withArgument().field("hey").ofThis()
                    .withArgument(23).onField("hey").ofNew(nb -> {
                nb.ofType("Thingamabob");
            });
        });
    }

    @Test
    public void testTryWithResources() {
        ClassBuilder<String> cb = ClassBuilder.forPackage("com.foo").named("Whee")
                .method("foo", mb -> {
                    mb.addArgument("Path", "path");
                    mb.body(bb -> {
                        bb.tryWithResources("stream", db -> {
                            TryBuilder<Void> block = db.initializedByInvoking("newInputStream")
                                    .withArgument("path")
                                    .withArgumentFromField("READ")
                                    .of("StandardStuff")
                                    .on("Files")
                                    .as("InputStream");
                            block.invoke("copy").withArgument("input")
                                    .withArgument("output")
                                    .on("FileUtils")
                                    .endBlock();
                        });
                    });
                });
        System.out.println(cb);
    }

}
