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
package com.mastfrog.code.generation.common.util;

import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;

/**
 * Contains a few convenience methods that avoid requireing external
 * dependencies.
 *
 * @author Tim Boudreau
 */
public final class Utils {

    public static <T> T notNull(String what, T obj) {
        if (obj == null) {
            throw new IllegalArgumentException(what + " may not be null");
        }
        return obj;
    }

    public static <T extends CharSequence> T notEmpty(String name, T what) {
        if (notNull(name, what).length() <= 0) {
            throw new IllegalArgumentException(what + " is zero length");
        }
        return what;
    }

    public static String notBlank(String name, String what) {
        if (notEmpty(name, what).trim().length() == 0) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return what;
    }

    private Utils() {
        throw new AssertionError();
    }

    /**
     * Iterate a collection, passing elements to an IterationConsumer - often
     * when generating code, the first and/or last elements are treated
     * specially with regard to prepending spaces, delimiters, etc.
     *
     * @param <T> A type
     * @param collection A collection of objects
     * @param consumer A consumer
     * @return the number of items the consumer was called for
     */
    public static <T> int iterate(Iterable<? extends T> collection, IterationConsumer<? super T> consumer) {
        boolean first = true;
        int result = 0;
        for (Iterator<? extends T> it = collection.iterator(); it.hasNext();) {
            T obj = it.next();
            boolean last = !it.hasNext();
            consumer.onItem(obj, first, last);
            result++;
            first = false;
        }
        return result;
    }

    public static <T> int iterate(T[] collection, IterationConsumer<? super T> consumer) {
        int result = 0;
        int last = collection.length - 1;
        for (int i = 0; i < collection.length; i++) {
            consumer.onItem(collection[i], i == 0, i == last);
        }
        return result;
    }

    public static String join(String delimiter, Collection<? extends Object> objs) {
        return join(delimiter, objs, new StringBuilder()).toString();
    }

    public static StringBuilder join(String delimiter, Collection<? extends Object> objs, StringBuilder into) {
        iterate(objs, (obj, first, last) -> {
            into.append(Objects.toString(obj));
            if (!last) {
                into.append(delimiter);
            }
        });
        return into;
    }

    public static StringBuilder join(String delimiter, StringBuilder into, Object... objs) {
        iterate(objs, (obj, first, last) -> {
            into.append(Objects.toString(obj));
            if (!last) {
                into.append(delimiter);
            }
        });
        return into;
    }

    /**
     * A Consumer-like interface for iterating a collection, which is told
     * whether the element it is being passed is the first and if it is the last
     * element.
     *
     * @param <T> The type
     */
    public interface IterationConsumer<T> {

        /**
         * Called with items from the collection.
         *
         * @param item The item
         * @param first Whether or not it is the first item
         * @param last Whether or not it is the last item
         */
        void onItem(T item, boolean first, boolean last);
    }
}
