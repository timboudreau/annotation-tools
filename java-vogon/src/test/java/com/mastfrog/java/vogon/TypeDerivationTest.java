/*
 * The MIT License
 *
 * Copyright 2019 Mastfrog Technologies.
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

import com.mastfrog.java.vogon.ClassBuilder.GenericTypeVisitor;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Tim Boudreau
 */
public class TypeDerivationTest {

    @Test
    public void testBug1() {
        String sig = "ResolutionConsumer<GrammarSource<?>,NamedSemanticRegions<RuleTypes>,RuleTypes,X>";
        GenericTypeVisitor v = new GenericTypeVisitor();
        ClassBuilder.visitGenericTypes(sig, 0, v);

        BodyBuilder bb = v.result();
        LinesBuilder lb = new LinesBuilder();
        bb.buildInto(lb);

        String g = lb.toString();

        List<String> expected = parts(sig);

        System.out.println("EXP: " + expected);

        List<String> got = parts(g);

        assertEquals(expected, got);

    }

    @Test
    public void testTypesNotLost() {
        String nm = "ThrowingTriFunction<MessageId, ? super T, ThrowingBiConsumer<Throwable, Acknowledgement>, Acknowledgement>";

        GenericTypeVisitor v = new GenericTypeVisitor();
        ClassBuilder.visitGenericTypes(nm, 0, v);

        BodyBuilder bb = v.result();
        LinesBuilder lb = new LinesBuilder();
        bb.buildInto(lb);

        String g = lb.toString();

        List<String> expected = parts(nm);

        System.out.println("EXP: " + expected);

        List<String> got = parts(g);

        assertEquals(expected, got);
    }

    @Test
    public void testNoTrailingCommas() {
        String nm = "ThrowingTriConsumer<T, Throwable, ThrowingRunnable> ";
        GenericTypeVisitor v = new GenericTypeVisitor();
        ClassBuilder.visitGenericTypes(nm, 0, v);

        BodyBuilder bb = v.result();
        LinesBuilder lb = new LinesBuilder();
        bb.buildInto(lb);

        String g = lb.toString();

        System.out.println("G IS '" + g + "'");
        assertFalse(g.trim().endsWith(","), g);
    }

    private List<String> parts(String nm) {
        String[] expectedParts = nm.split("[<>,]");
        List<String> result = new ArrayList<>(expectedParts.length);
        for (int i = 0; i < expectedParts.length; i++) {
            String part = expectedParts[i].trim();
            if (!part.isEmpty() && !",".equals(part) && !"<".equals(part) && !">".equals(part)) {
                result.add(expectedParts[i].trim());
            }
        }
        return result;
    }
}
