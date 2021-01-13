Java Vogon
==========

Generated code that's intuitive to create, and looks like it was written by 
a human.

_Java Vogon_ is Java code generation library that deeply supports the 
structures commonly used in Java code, with an intuitive builder-based API,
which outputs neat, well-formatted code.

Code that generates code can be hard-to-maintain. With Java Vogon, it 
reads like a sentence:

```java
methodBody.declare("expected")
          .initializedByInvoking("now")
          .on("Instant")
          .as("Instant");

methodBody.declare("got")
          .initializedByInvoking("getStartTimeAsInstant")
          .on("someInstance")
          .as("Instant");

methodBody.invoke("assertEquals")
          .withArgumentFromInvoking("toEpochMilli")
          .on("expected")
          .withArgumentFromInvoking("toEpochMilli")
          .on("got")
          .inScope(); // statically imported
```


Lambda vs. Imperative Styles
----------------------------

Most code-shapes can be created either using an imperative or lambda style - 
for writing something small, the terse imperative form may be more readable;
for generating lengthy stretches of code, the lambda-based style means the
generation code will be structured in much the same way as its output. For
example:

```java
ClassBuilder<String> classBuilder = ClassBuilder.forPackage("com.foo")
        .named("OddChecker")
        .docComment("Tells if a number is even or odd")
        .withModifier(PUBLIC, FINAL)
        .importing(IntPredicate.class)
        .implementing(IntPredicate.class.getSimpleName());

// All three of these result exactly the same code

classBuilder.overridePublic("test").addArgument("int", "value")
    .bodyReturning("value % 2 != 0");

classBuilder.overridePublic("test2", method -> {
    method.addArgument("int", "value")
            .body(body -> {
                body.returning("value % 2 != 0");
            });
});
```

The above simply embeds the expression `value % 2 != 0` as text; you can also
do it expressing each element programmatically - which, while more verbose, is
less error-prone, and more suitable for writing complex meta-code-generators:

```java
classBuilder.overridePublic("test3", mb -> {
    mb.addArgument("int", "value")
        .body(bb -> {
            // This uses ConditionBuilder and ValueExpressionBuilder,
            // which are more powerful for automated, programmatic uses,
            // but more verbose
            bb.returningBoolean().value().numeric().expression("value")
                    .modulo().literal(2).endNumericExpression().notEquals().literal(0).endCondition();
        });
});

```

Philosopy
=========

#### Minimize Boilerplate 
Most things you generate in Java have an obvious
terminal action - a thing there can only be one of.  Wherever possible,
use that to avoid `close()` or `end()` methods.  For example, invoking
a method - there is one and only one thing you're invoking it on,
so let that be the terminus of defining an invocation, and simply return
the code-block you were adding it to:

```java
block.invoke("print").withStringLiteral("Hello ").on("System");
block.invoke("print").withArgumentFromInvoking("getProperty").withStringLiteral("user.name").on("System").on("System");
```

or

```java
block.declare("userName").initializedByInvoking("getProperty").withStringLiteral("user.name").on("System").as("String");
```

in the case of a declaration, there is only one type you can possibly be making the variable, so that is the terminus;
in the case of an invocation, the thing you're calling a method on is that (you can use `inScope()` instead of `on()` if
the method being called is within the scope of the current class or a static import).

#### Detect Basic Errors at Generation-Time

Detecting all errors is impossible, short of running the code - Java Vogon knows a lot, but it is not a JVM itself.

Where it's straightforward to detect errors at generation-time, it will - for example, adding a method with the 
same signature twice, or a duplicate case-label, or trying to switch on a floating-point number.


#### Don't Force a Style, But Don't Punt the User to Free-Form Text

If you want to embed free-form text in a code-block, you can always just do `block.statement("blah blah this won't compile")`
(but you won't get correct string escaping for free!);  any method named `expression()` can take free-form Java
code that can do anything you want.


Lambda vs. Imperative
=====================

If you are generating a method with many statements, loops, if/else statements,
etc., you _can_ generate using a single giant chain of calls - but the result
quickly becomes unreadable.

The lambda style has a few advantages:

 * Some structures, particularly code blocks and switch statements have _no natural terminus_,
   so you must call an `endBlock()` or `endSwitch()` method to add them to the code.  In a
   lambda, however, the terminus *becomes* well-defined - it is the exit point of the lambda
   itself, so the framework will call it for you.

 * It is an easy mistake to make, to forget to terminate a call - to define a method but
   never give it a body, or invoke something but never tell the builder what it's being invoked *on*.
   or declare a variable but forget the final call that gives it a type and adds it.

   In the imperative style, the result of this is simply that some code never gets generated and you
   wonder what's wrong.  In a lambda, in the case that the framework can't guess what you wanted to
   do, an exception will be thrown if the thing you're building is never completed.
   So, this code `bb.invoke("foo").withArgument("bar");` results in _nothing at all_ being
   generated.  This code, on the other hand, let's you know something is missing immediately:

```java
block.invoke("foo", ib -> {
    ib.withArgument("bar");
    // exception will be thrown on exiting this Consumer
});
```

   In a few cases, the framework actually can make a resonable guess as to what you were
   trying to do - for example:

```java
block.declare("sb").initializedWithNew(nue -> {
    nue.withArgument("data:").ofType("StringBuilder");
});
```

   _should_ and with `.as("StringBuilder")` - the framework doesn't _know_ that you don't
   intend to declare it as `CharSequence` or even `Object` - but it does know that it
   is _not wrong_ to assume you wanted to declare a `StringBuilder` as the type of the
   variable, so the lambda form will handle that case.

Terminology
===========

Wherever possible, the terms from the world of compilers are used:

 * _condition_ - a condition is something whose output is a boolean - for example, the test
in a ternary expression (e.g. `test() ? a : b` ) or in an `if` statement - things that return
a `ConditionBuilder` let you define a boolean test.  Most condition definitions consist of a
_left side_, a _comparison operator_ and a _right side_, and `ConditionBuilder` is for creating
those - e.g. `condition.invoke("numberOfThings").inScope().isGreaterThan().literal(1)`.

 * _expression_ - an expression is any Java statement that outputs a value of some sort - as
opposed to a _statement_ that might be a declare something or continue a loop or whatever -
the result of an expression is something that can be assigned to a variable or field.


Debugging Code Generation
-------------------------

Call `ClassBuilder.generateDebugOutput()` and significant operations (opening
blocks, adding various kinds of class elements or statements) will be 
preceded by a line comment showing the class, method and line number in _your_
code that added that element.  You can also embed debug logging using
`BlockBuilder.debugLog(String)`, which will be generated only when debug
mode is enabled.  These comments look like

```java
// generateOneBuilderTest(TestGenerator.java:860)
```

Logging
-------

Support for `java.util.Logger` works out of the box - in any code block, simply call
one of the `log` methods (the argument order may differ slightly from JUL) and
a static `Logger` field and the necessary imports will be automatically added - e.g.
`block.log("Received {0} by {1}", Level.INFO, "arg", "this")` - variable names
are simply quoted strings for templated logging statements.

Imports
-------

At present Java Vogon does nothing special regarding imports - types are strings, and
if you want to reference a type, you need to import it or use it by fully-qualified
name.  Support for doing fancier things may be added in the future (but can be done
externally - for example, 
[here](https://github.com/timboudreau/ANTLR4-Plugins-for-NetBeans/blob/master/registration-annotation-processors/src/main/java/org/nemesis/registration/typenames/KnownTypes.java)
is an example of generating an enum of known types in a project, which is generated
(again using Java Vogon) by analyzing source and class files using some
[slightly terrifying code](https://github.com/timboudreau/ANTLR4-Plugins-for-NetBeans/blob/master/type-code-generation/src/main/java/com/mastfrog/type/code/generation/GenerateDependenciesClass.java)
so that annotation processors from one subproject don't have to be rewritten if a
class they generate code using is refactored into a different project or package - 
the trick here is 

An Example is Worth A Thousand Words
====================================

[Here is an annotation processor](https://github.com/timboudreau/ANTLR4-Plugins-for-NetBeans/blob/master/registration-annotation-processors/src/main/java/org/nemesis/registration/LanguageRegistrationDelegate.java#L1617)
that uses Java Vogon to generate an entire IDE
plugin to support an Antlr-based language, generating vast amounts of glue-code to
implement a NetBeans parser over an Antlr parser, and touches just about
every feature of Java Vogon.

