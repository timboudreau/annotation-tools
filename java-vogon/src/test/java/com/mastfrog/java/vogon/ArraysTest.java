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

import static com.mastfrog.java.vogon.AssertionsTest.assertSequence;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Tim Boudreau
 */
public class ArraysTest {

    @Test
    public void testArrays() {

        ClassBuilder<String> cb = ClassBuilder.forPackage("foo.bar").named("Wurgle");

        cb.method("whuck").body(bb -> {

            bb.declare("matrix").initializedAsNewArray("int").withDimension(96).withDimension(48).closeArrayLiteral();

            bb.assignArrayElement(4).element(5).of("matrix")
                    .toExpression("23");

            bb.assign("pixel").toArrayElement(12).element(15).of("matrix");
            bb.blankLine();
            bb.declare("pxs").initializedAsNewArray("short", alb -> {
                alb.add("5").add("4").add("3");
            });

            bb.assignArrayElement(el -> {
                el.element(6).element(7).of("matrix").toArrayElement(0).element(1).of("matrix");
            });

            bb.assignArrayElement(aeb -> {
                aeb.elementFromInvocationOf("maxAcross").withArgument(0).withArgument(96).onThis()
                        .of("pxs")
                        .castTo("short").toField("wookies").of("this");
            });

            bb.invoke("println")
                    .withArgumentFromInvoking("charAt").onNew().withArgumentFromInvoking("toString")
                    .withArgument("matrix").on("Arrays").ofType("String")
                    .onField("out")
                    .of("System");

        });

        assertSequence(cb, "matrix[4][5] = 23;");
        assertSequence(cb, "pixel = matrix[12][15];");
        assertSequence(cb, " int[][] matrix = new int[96][48]");
        assertSequence(cb, "short[] pxs = new short[] {5, 4, 3};");
        assertSequence(cb, "matrix[6][7] = matrix[0][1]");
        assertSequence(cb, "pxs[this.maxAcross(0, 96)] = (short) this.wookies;");
        assertSequence(cb, "System.out.println(new String(Arrays.toString(matrix)).charAt());");
    }
}
