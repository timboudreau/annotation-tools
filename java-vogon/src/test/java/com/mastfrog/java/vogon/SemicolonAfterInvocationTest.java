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

import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Tim Boudreau
 */
public class SemicolonAfterInvocationTest {

    @Test
    public void testInComplexInvocation() {
        String[] args = new String[]{"foo", "bar", "baz"};
        ClassBuilder<String> cb = ClassBuilder.forPackage("moo.rar")
                .named("TestInComplexInvocation")
                .generateDebugLogCode();

        ClassBuilder.MethodBuilder<ClassBuilder<String>> meth = cb.method("glork");
        meth.body(bb -> {
            bb.invoke("shutdown", ib -> {
                for (String ve : args) {
                    ib.withArgument(ve);
                }
                ib.onField("poogers_1")
                        .ofThis();
            });
        });

        String s = cb.build();
        assertTrue(s.contains("this.poogers_1.shutdown(foo, bar, baz);"),
                () -> {
                    if (s.contains("this.poogers_1.shutdown(foo, bar, baz)")) {
                        return "Missing semicolon: '"
                        + s.substring(s.indexOf("this.poogers_1"), s.length());
                    } else {
                        return s;
                    }
                });
    }

    @Test
    public void testSemiInTryCatch() {
        ClassBuilder<String> cb = ClassBuilder.forPackage("moo.rar")
                .named("TestSemiInTryCatch") //                .generateDebugLogCode()
                ;

        cb.method("main").withModifier(PUBLIC, STATIC)
                .addVarArgArgument("String", "args", bb -> {
                    bb.trying(tri -> {
                        tri.debugLog("Hello world");
                        tri.invoke("doSomething").onField("reportWriter_8").ofThis();
                        tri.fynalli(fin -> {
                            fin.debugLog("stuff");
                            fin.invoke("close").onField("reportWriter_8").ofThis();
                        });
                    });
                });
        String s = cb.build();

        assertTrue(s.contains("this.reportWriter_8.doSomething();"), s);
        assertTrue(s.contains("this.reportWriter_8.close();"), s);
    }

    @Test
    public void testMoreSemis() {

        ClassBuilder<String> cb = ClassBuilder.forPackage("moo.rar")
                .named("TestMoreSemis");

        cb.method("testIt").body(bb -> {
            bb.invoke("close").onField("reportWriter_8").ofThis();
        });
        cb.build();
        cb.build();

        assertTrue(cb.build().contains("this.reportWriter_8.close();"),
                cb.build());
    }

    @Test
    public void testSemicolon() {
        ClassBuilder<String> cb = ClassBuilder.forPackage("moo.rar")
                .named("SemiTest");
        cb.method("testOne").body(bb -> {
            bb.invoke("testTwo").withArgument("this")
                    .onThis();
        });

        cb.method("testTwo").body()
                .invoke("testThree").inScope()
                .endBlock();

        cb.method("testThree").withModifier(PUBLIC, STATIC)
                .addArgument("String", "foo")
                .body(bb -> {
                    bb.invoke("gurg").onField("moo").ofThis();
                });

        cb.method("testFour").withModifier(PUBLIC, STATIC)
                .returning("String")
                .body(bb -> {
                    bb.returningInvocationOf("gurg").onThis();
                });

        String s = cb.build();
        assertTrue(s.contains("return this.gurg();"), s);
        assertTrue(s.contains("this.moo.gurg();"), s);
        assertTrue(s.contains("testThree();"), s);
        assertTrue(s.contains("this.testTwo(this);"), s);
    }
}
