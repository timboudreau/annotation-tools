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

import static com.mastfrog.java.vogon.AssertionsTest.assertSequence;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 *
 * @author Tim Boudreau
 */
public class AssignmentOperatorsTest {

    @Test
    public void testAssignmentOperators() {
        ClassBuilder<String> cb
                = ClassBuilder.forPackage("com.moo")
                        .named("AssigTest");
        for (AssignmentOperator op : AssignmentOperator.values()) {
            cb.method(op.name().toLowerCase(), mth -> {
                mth.returning("int")
                        .body(bb -> {
                            bb.declare("val")
                                    .initializedTo(8);
//                                    .initializedTo().literal(8)
//                                    .as("int");

                            bb.assign("val")
                                    .using(op)
                                    .toExpression("8");

                            bb.assign("val")
                                    .using(op)
                                    .complement()
                                    .toLiteral(8);
                            bb.returning("val");
                        });
            });
        }
        for (AssignmentOperator op : AssignmentOperator.values()) {
            String expected1 = "val" + op + "8;";
            String expected2 = "val" + op + "~8;";
            assertSequence(cb, expected1);
            assertSequence(cb, expected2);
        }
    }

    @Test
    public void testFriendlyNumberLong() {
        long[] longs = new long[]{
            0, // 0
            1, // 1
            1000000, // 2
            1211111, // 3
            111222333444L, // 4
            -111222333444L, // 5
            11122233344L, // 6
            Long.MAX_VALUE, // 7
            Long.MIN_VALUE + 1, // 8
            3211111,};
        for (int i = 0; i < longs.length; i++) {
            long l = longs[i];
            String s = ClassBuilder.friendlyLong(l);
            long nue = Long.parseLong(stripNonDigit(s));
            assertEquals(l, nue, s);
            switch (i) {
                case 0:
                    assertEquals("0", s);
                    break;
                case 1:
                    assertEquals("1", s);
                    break;
                case 2:
                    assertEquals("1_000_000", s);
                    break;
                case 3:
                    assertEquals("1_211_111", s);
                    break;
                case 4:
                    assertEquals("111_222_333_444", s);
                    break;
                case 5:
                    assertEquals("-111_222_333_444", s);
                    break;
                case 6:
                    assertEquals("11_122_233_344", s);
                    break;
                case 7:
                    assertEquals("9_223_372_036_854_775_807", s);
                    break;
                case 8:
                    assertEquals("-9_223_372_036_854_775_807", s);
                    break;
                case 9:
                    assertEquals("3_211_111", s);
                    break;
            }
        }
    }

    private String stripNonDigit(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || Character.isDigit(c)) {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }
}
