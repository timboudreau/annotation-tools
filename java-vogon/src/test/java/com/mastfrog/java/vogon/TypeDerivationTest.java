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

import com.mastfrog.code.generation.common.BodyBuilder;
import com.mastfrog.code.generation.common.LinesBuilder;
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

        List<String> got = parts(g);

        assertEquals(expected, got);
    }

    @Test
    public void testBug2() {
        String listSpec = "Map<List<Class<?>>, Foo>";
        GenericTypeVisitor v = new GenericTypeVisitor();
        ClassBuilder.visitGenericTypes(listSpec, 0, v);
        String[] exp = new String[] {"Map", "List", "Class", "?", "Foo"};
        int[] depths = new int[]  {0,1,2,3,1};
        int[] cur = new int[1];
        ClassBuilder.visitGenericTypes(listSpec, 0, (depth, str) -> {
            int cursor = cur[0]++;
            assertEquals(exp[cursor], str);
            assertEquals(depths[cursor], depth);
        });
        ClassBuilder<String> cb = ClassBuilder.forPackage("com.foo").named("Bar")
                .field("wunk").ofType(listSpec);
        assertTrue(cb.build().contains(listSpec), "List spec '" + listSpec + "' not found in class " + cb);
    }

    @Test
    public void testBug3() {
        String listSpec = "Consumer<? super SortPropertyBuilder<SingleTestCaseMaterializedViewBuilder<$Ret>>>";
        ClassBuilder<String> cb = ClassBuilder.forPackage("com.foo").named("Bar")
                .field("wunk").ofType(listSpec);
        assertTrue(cb.toString().contains(listSpec));
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
