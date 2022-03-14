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

import com.mastfrog.java.vogon.ClassBuilder.BlockBuilder;
import com.mastfrog.java.vogon.ClassBuilder.MethodBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.lang.model.element.Modifier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Tim Boudreau
 */
public class AssertionsTest {

    @Test
    public void testAssertions() {
        ClassBuilder<String> cb = ClassBuilder.forPackage("com.moozle")
                .named("Poozle").withModifier(Modifier.FINAL);
        MethodBuilder<ClassBuilder<String>> mb = cb.method("flork").withModifier(Modifier.SYNCHRONIZED)
                .addArgument("String", "arg1").addArgument("int", "arg2")
                .addArgument("boolean", "arg3").addArgument("T", "arg4")
                .withTypeParam("T extends Collection<R>").withTypeParam("R");

        cb.method("empty").body().endBlock();
        int[] count = new int[1];
        mb.body(bb -> {
//            bb.probeAdditionsWith(new ProbeImpl());
            assertEquals(1, ++count[0], "Body builder called more than once");
            bb.asserting("arg3");
            bb.asserting().literal(23).equals().expression("arg2").endCondition().build();
            bb.assertingNotNull("arg1");
        });

        MethodBuilder<ClassBuilder<String>> mb2 = cb.method("snork")
                .addArgument("String", "thing").addArgument("int", "val");
        mb2.body(bb -> {
            bb.assertingNotNull().literal("foober");
            bb.assertingNotNull((ClassBuilder.ValueExpressionBuilder<ClassBuilder.AssertionBuilder<?>> veb) -> {
                assertNotNull(veb, "Value expression builder should not be null");
                ClassBuilder.FieldReferenceBuilder<ClassBuilder.AssertionBuilder<?>> vebf = veb.field("out");
                assertNotNull(vebf, "FieldReferenceBuilder should not be null");
                ClassBuilder.AssertionBuilder<?> vebOf = vebf.of("System");
                assertNotNull(vebOf, "of value for of(name) should be the assertion builder so a message "
                        + "can be applied");
                veb.field("out").of("System").withMessage("System out is null.  That's not good");
            });
        });

        assertTrue(cb.build().contains("assert arg3;"),
                () -> "Text should contain the string 'assert arg3;' " + cb);

        MethodBuilder<ClassBuilder<String>> mb3 = cb.method("glark")
                .addArgument("String", "thing").addArgument("int", "val");
        mb3.body(bb -> {
            bb.asserting(clause -> {
                clause.booleanExpression("val == 1")
                        .withMessage(msg -> {
                            msg.with().literal("Hey you").with().expression("thing")
                                    .with().field("out").of("System").endConcatenation();
                        });
            });

            bb.assertingNotNull().field("out").of("System").withMessage("Hello world");
            bb.assertingNotNull().field("out").of("System").withMessage(scb -> {
                scb.with().literal("Hey").with().literal(23).with().literal('x').with()
                        .invoke("currentTimeMillis").on("System");
            });
        });
        assertLine(cb, "assert arg3;");
        assertLine(cb, "assert 23 == arg2;");
        assertLine(cb, "assert arg1 != null;");
        assertLine(cb, "assert System.out != null : \"System out is null.  That's not good\";");
        assertLine(cb, "// do nothing");
        assertSequence(cb, "void glark(String thing, int val)");
        assertSequence(cb, "assert val == 1 : \"Hey you\" + thing + System.out;");
        assertSequence(cb, "assert System.out != null : \"Hello world\";");
        assertSequence(cb, "assert System.out != null : \"Hey\" + 23 + 'x' + System.currentTimeMillis();");

        System.out.println("--------------------------");
        System.out.println(cb);
    }

    @Test
    public void testIfExtensions() {
        ClassBuilder<String> cb = ClassBuilder.forPackage("foo.bar").named("WuggleWhatzitMakeThisLineWrap")
                .withModifier(Modifier.FINAL, Modifier.PUBLIC)
                .implementing("PoozleHoozle", "Wuzzle<T>", "Waggle<R>")
                .withTypeParameters("T extends Collection<R>", "R extends X", "X");
        cb.method("whatevs", mb -> {
            mb.withTypeParam("G extends F", "F extends Foo & M", "M")
                    .withModifier(Modifier.PUBLIC, Modifier.STATIC)
                    .addArgument("int", "amount")
                    .addArgument("String", "thing").body(bb -> {
                bb.iff(cond -> {
                    cond.variable("thing").isEqualTo("foo").invoke("println").withStringLiteral("foo")
                            .onField("out").of("System").orElse().lineComment("do nothing").endIf();
                });
                bb.iff(cond -> {
                    cond.invoke("currentTimeMillis").on("System").isGreaterThan(10000)
                            .invoke("println").onField("out").ofField("foo").of("System");
                });
                bb.iff(cond -> {
                    cond.variable("thing").isNotEqualToString("foober").invoke("println")
                            .withStringLiteral("Have a foober")
                            .onField("out").of("System");
                });
                bb.iff().invoke("buz").onField("wubble").ofField("foo").arrayElement(3).of("this")
                        .instanceOf("HoogerBooger").andThrow(nb -> {
                    nb.withStringLiteral("Hello!").ofType("IllegalArgumentException");
                }).elseIf().literal(23).isGreaterThan(5.5).trying(tb -> {
                    tb.asserting("false").fynalli().invoke("println")
                            .withStringLiteral("Uh oh")
                            .onField("out").of("System").endBlock();
                }).endIf();
                bb.iff().literal(23).equallingInvocation("currentTimeMillis").on("System")
                        .asserting("true").endIf();

                bb.iff().invoke("getDefault").on("Locale").instanceOf("Pwudge")
                        .lineComment("whatever").endIf();

                bb.iff().invoke("getDefault").on("Charset")
                        .equalsField("UTF_8").of("StandardCharsets")
                        .invoke("println").withStringConcatentationArgument("Charset is ")
                        .with().invoke("getDefault").on("Charset").endConcatenation().on("System")
                        .endIf();
            });
        });
        System.out.println(cb);
        assertLine(cb, "assert false;");
        assertLine(cb, "System.out.println(\"Have a foober\");");
        assertSequence(cb, "23>5.5D");
        assertSequence(cb, "System.currentTimeMillis() > 10_000");
        assertSequence(cb, "thing == \"foo\"");
        assertLine(cb, "if (this.foo[3].wubble.buz() instanceof HoogerBooger){");
        assertLine(cb, "//do nothing");
        assertLine(cb, "if (!thing .equals(\"foober\")) {");
        assertSequence(cb, "if (thing == \"foo\") {\nSystem.out.println(\"foo\");\n} else {\n// do nothing\n}");
        assertLine(cb, "System.out.println(\"Uh oh\");");
        assertLine(cb, "throw new IllegalArgumentException(\"Hello!\");");
        assertLine(cb, "if (Locale.getDefault() instanceof Pwudge) {");
        assertLine(cb, "if (Charset.getDefault() .equals(StandardCharsets.UTF_8)) {");
        assertLine(cb, "System.println(\"Charset is \" + Charset.getDefault());");
        assertLine(cb, "// whatever");
        assertSequence(cb, "<G extends F, F extends Foo & M, M>");
        assertSequence(cb, "T extends Collection<R>, R extends X, X>");
        assertSequence(cb, "implements PoozleHoozle, Wuzzle<T>, Waggle<R>");
    }

    static void assertSequence(ClassBuilder<String> cb, String seq) {
        String noSpaces = cb.build().replaceAll("\\s+", "");
        String expNoSpaces = seq.replaceAll("\\s+", "");
        assertTrue(noSpaces.contains(expNoSpaces), "Did not find '" + seq + "' in " + cb);
    }

    static void assertLine(ClassBuilder<String> cb, String text) {
        String exp = text.replaceAll("\\s+", "");
        boolean found = false;
        for (String line : cb.build().split("\n")) {
            String noSpaces = line.replaceAll("\\s+", "");
            if (noSpaces.equals(exp)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Did not find line '" + text + "' in " + cb);
    }

    static final class ProbeImpl implements BiConsumer<BlockBuilder<?>, BodyBuilder> {

        List<String> additions = new ArrayList<>();
        List<BodyBuilder> statements = new ArrayList<>();
        List<Exception> addedAt = new ArrayList<>();

        @Override
        public void accept(BlockBuilder t, BodyBuilder u) {
            LinesBuilder lb = new LinesBuilder(4);
            u.buildInto(lb);
            String body = lb.toString();
            statements.add(u);
            additions.add(body);
            addedAt.add(new Exception());

            System.out.println("ADD " + body);
        }
    }
}
