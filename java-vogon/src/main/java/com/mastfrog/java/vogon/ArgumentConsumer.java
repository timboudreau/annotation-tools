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

import com.mastfrog.java.vogon.ClassBuilder.ArrayValueBuilder;
import com.mastfrog.java.vogon.ClassBuilder.ConditionBuilder;
import com.mastfrog.java.vogon.ClassBuilder.FieldReferenceBuilder;
import com.mastfrog.java.vogon.ClassBuilder.InvocationBuilder;
import com.mastfrog.java.vogon.ClassBuilder.LambdaBuilder;
import com.mastfrog.java.vogon.ClassBuilder.NewBuilder;
import com.mastfrog.java.vogon.ClassBuilder.NumericExpressionBuilder;
import com.mastfrog.java.vogon.ClassBuilder.NumericOrBitwiseExpressionBuilder;
import com.mastfrog.java.vogon.ClassBuilder.StringConcatenationBuilder;
import com.mastfrog.java.vogon.ClassBuilder.ValueExpressionBuilder;
import java.util.function.Consumer;

/**
 * Common interface for things that take arguments.
 *
 * @author Tim Boudreau
 */
public interface ArgumentConsumer<B> {

    B withArgument(int arg);

    B withArgument(long arg);

    B withArgument(short arg);

    B withArgument(byte arg);

    B withArgument(double arg);

    B withArgument(float arg);

    B withArgument(char arg);

    B withArgument(String arg);

    B withArgument(boolean arg);

    B withArgument(Consumer<ValueExpressionBuilder<?>> consumer);

    ValueExpressionBuilder<B> withArgument();

    FieldReferenceBuilder<B> withArgumentFromField(String name);

    B withArgumentFromField(String name, Consumer<FieldReferenceBuilder<?>> c);

    B withArgumentFromInvoking(String name, Consumer<? super InvocationBuilder<?>> c);

    InvocationBuilder<B> withArgumentFromInvoking(String name);

    NewBuilder<B> withArgumentFromNew(String what);

    B withArgumentFromNew(Consumer<NewBuilder<?>> c);

    B withArguments(String... args);

    B withArrayArgument(Consumer<? super ArrayValueBuilder<?>> c);

    ArrayValueBuilder<B> withArrayArgument();

    B withClassArgument(String arg);

    B withLambdaArgument(Consumer<? super LambdaBuilder<?>> c);

    LambdaBuilder<B> withLambdaArgument();

    B withNewArrayArgument(String arrayType, Consumer<? super ArrayValueBuilder<?>> c);

    ArrayValueBuilder<B> withNewArrayArgument(String arrayType);

    NewBuilder<B> withNewInstanceArgument();

    B withNewInstanceArgument(Consumer<? super NewBuilder<?>> c);

    NumericOrBitwiseExpressionBuilder<B> withNumericExpressionArgument(int literal);

    NumericOrBitwiseExpressionBuilder<B> withNumericExpressionArgument(long literal);

    NumericOrBitwiseExpressionBuilder<B> withNumericExpressionArgument(short literal);

    NumericOrBitwiseExpressionBuilder<B> withNumericExpressionArgument(byte literal);

    NumericExpressionBuilder<B> withNumericExpressionArgument(double literal);

    NumericExpressionBuilder<B> withNumericExpressionArgument(float literal);

    ValueExpressionBuilder<NumericOrBitwiseExpressionBuilder<B>> withNumericExpressionArgument();

    StringConcatenationBuilder<B> withStringConcatentationArgument(String initialLiteral);

    B withStringConcatentationArgument(String initialLiteral, Consumer<StringConcatenationBuilder<?>> c);

    B withStringLiteral(String arg);

    ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<B>>> withTernaryArgument();

    B withTernaryArgument(Consumer<? super ConditionBuilder<? extends ValueExpressionBuilder<? extends ValueExpressionBuilder<?>>>> c);
}
