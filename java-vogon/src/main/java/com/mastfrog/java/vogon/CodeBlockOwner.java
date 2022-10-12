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

import com.mastfrog.java.vogon.ClassBuilder.BlockBuilder;
import com.mastfrog.java.vogon.ClassBuilder.BlockBuilderBase;
import java.util.function.Consumer;

/**
 * Thing - such as a method or a constructor or a lambda - which can produce a
 * "body" code-block.
 *
 * @author Tim Boudreau
 */
public interface CodeBlockOwner<T, B1 extends BlockBuilder<T>, B2 extends BlockBuilder<?>> {

    /**
     * Pass a consumer which will write the code-block that is the body of the
     * element this instance represents.  The code block will be automatically
     * closed when the consumer exits.
     *
     * @param c A consumer
     * @return The result of closing the body
     */
    T body(Consumer<? super B2> c);

    /**
     * Begin creating the code-block that is the body of this source element.
     *
     * @return A block builder
     */
    B1 body();

    /**
     * Set the body to be a single line of code and close this source element.
     *
     * @param body The body
     * @return The result of closing this source element
     */
    default T body(String body) {
        return body().statement(body).endBlock();
    }

    /**
     * Give this element an empty body and close it.  By default, generates
     * a body with a single line-comment that says <code>// do nothing</code>
     * to make it clear that the body is empty intentionally, not due to
     * failure to close a block builder.
     *
     * @return The result of closing this source element
     */
    default T emptyBody() {
        return body().lineComment("do nothing").endBlock();
    }

    /**
     * Give this element no body at all - the exact semantics of what that means
     * depend on the implementation - a method with "no body" - at all - is
     * appropriate for an interface method or abstract method, but a constructor
     * must have <b>a</b> body
     *
     * @return The result of closing this source element - by default, calls
     * <code>emptyBody()</code>
     */
    default T noBody() {
        return emptyBody();
    }

}
