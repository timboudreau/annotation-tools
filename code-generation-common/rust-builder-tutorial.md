Writing a Rust Code Generator using Code Generation Common
==========================================================

The `code-generation-common` project factors out general purpose types from Java Vogon to make it easier to follow the patterns used there to write code generators for any language.

What Problem Are We Trying To Solve
-----------------------------------

There are a lot of ways to generate code.  Perhaps the most common one is templating engines of varying degrees of sophistication, that plug values into a template.  That works for a lot of problems.  The trouble with it is that it puts some fundamental limits on the flexibility of your code generator, encourages lowest-common-denominator code that is often inefficient or unpleasant to work with.

Take a humble hash-code computation in Java.  Java's primitive types require - and for efficiency, deserve, very different treatment.  The works-for-anything approach would be to generate something like `return Arrays.deepHashCode(field1, field2, ... fieldN)`.  It's not wrong, it works for everything - but it's generating a **lot** of totally unnecessary work.  Similarly, with generating an `equals()` method on a class with *n* fields, what you want is to do your cheapest tests - equality tests on primitives, then enums - first, so your equals method exits with false before it ever tries to do equality comparisons on more expensive fields.

With Java Vogon - and hopefully with what you create, if you're reading this tutorial, *there is zero excuse for such code*.

But if you try to handle all the permutations of kinds of fields that might need to be handled in an equals method in a templating language, that template is going to get pretty unwieldy and hard to read - because templating languages are fundamentally *not programming languages*, and the more nuance you try to express in one, the more you bump up against how bad a tool they are for programming.

On the other hand, if you invented an interface like

```java
interface EqualityContributionContributor {
  void contribute(BlockBuilder<?> into, String cumulativeHashVariableName, String fieldName);
}
```

and created an `enum` of all the Java types that need special handling, you would have something clean, tidy, that generates efficient code and is eminently maintainable.


The operating philophy of Java Vogon and its breathren boils down to a few principles:

 * Generated code should be **better** than you'd write by hand*.  You write code generators once, get the logic right and they work forever.
 * Generated code should be as readable as what you'd write by hand - ideally it should be what you would write if you had the time or patience - explore those optimizations, because once you've got it right, they're right forever, for everything you apply them to
 * Code generators should be a pleasure to use - they should use terms users of that language think in, and the API should be intuitive and discoverable via code-completion in an IDE
 * The structure of the code that *generates* code should naturally look like the structure of the code it generates - code that generates code should be readable (at least once you're used to the patterns)
 * Writing code generators should be fun, rewarding and as non-painful as possible - if there are common patterns in a language, provide shortcut ways to express them in code - don't make people write boilerplate for the sake of consistency or completeness
 * Guard against easily-made mistakes - and never generate invalid code that didn't come from invalid input - it's okay to let the user reference an unknown variable name (they'll find out the first time they compile the output) - you're not writing a compiler - but it's *not* okay to let the user put invalid characters in a variable name.  Be a helper, not a dictator.

You don't have to support every possible corner case in a language to have something useful - just provide a way to add unstructured content for things you're not ready to support in a structured way - for example, Java Vogon's `BlockBuilder` has a `statement(String)` method that will let you do whatever you want.

The initial steps of writing a code generator are the hardest, because you are building up handlers for the set of structures your language uses from nothing.  However, the process rapidly accellerates - once you have, say, a type-parameters-builder that collects type parameters and formats them appropriately, you can reuse it on everything that needs one.


What Does A Code Generation Library Look Like
---------------------------------------------

Any language generator written thusly consists of:

- At least one top-level `SourceFileBuilder` subtype that generates a whole source file, and is the entry point - like ClassBuilder in Java Vogon
- Various builders that can be obtained from the `SourceFileBuilder` to create individual source code elements
  - Usually those individual source element builders can only be obtained from a `SourceFileBuilder` or a child of it
  - Each builder is parameterized on a type that it returns when it is completed
    - Depending on the complexity of the element, you might go through a chain of builders to get back to the top level source file builder - that way, you write small reusable builders focused on one thing, which can be reused in many situations

So that these builders can be reused, a builder does not know what type its going to return when it is completed - that uses a type parameter.  Here's an example that simply collects the name of something.


```java
    public static final class ParameterNameBuilder<T> implements CodeGeneratorBase {
        // CodeGeneratorBase simply implements toString() to call generateInto(), so
        // toString() on a builder prints out the code - you'll probably create your
        // own base class that returns a LinesBuilder configured for your language

        private final Function<String, T> converter;
        private String name;

        public ParameterNameBuilder(Function<ParameterNameBuilder<T>, T> converter) {
            // This function is what allows this builder not to care *what* it is 
            // producing the name *for*, so it's reusable
            this.converter = converter;
        }

        public T named(String name) {
            checkIdentifier(name);
            this.name = name;
            return converter.apply(this);
        }

        public void generateInto(LinesBuilder lines) {
            lines.word(name);
        }
    }
```

a builder for a source element does not *have* to implement `CodeGenerator` - it could also just
pass the name to the caller, and let the caller do something with that:

```java
    public static final class ParameterNameBuilder<T> {

        private final Function<String, T> converter;

        public ParameterNameBuilder(Function<String, T> converter) {
            this.converter = converter;
        }

        public T named(String name) {
            checkIdentifier(name);
            return converter.apply(name);
        }
    }
```


In general, builders for source elements come in two categories:

1. Things that have a natural conclusion that *must* be present - like invoking a method as part of a code block in Java `codeBlock.returningInvocationOf("foo").withArgument("bar").on("someField")` - a method in Java always belongs to something, so either the last method is `on(whatever)` or it is going to be `inScope()` - but there is always a referent.  That has two consequences:
  - We don't need any `iAmDoneWithThis()` type of method to 
  - Failing to call one of the referent methods is an error
2. Things that collect a group of source elements - like a bunch of statements inside a code block
  - These will need an explicit *I'm done adding stuff, get me out of here* - like `codeBlock.lineComment("returnSomething").returning("someVariable").endBlock()`

For category 2., you will want to implement two different ways of creating one of these - one that takes a `Consumer<YourBuilder<?>>` and one that simply returns `YourBuilder<TheTypeThatCreatedIt<T>>`.  An example of that is Java Vogon's BlockBuilder, which lets you add statements to code blocks, which has two signatures (on `MethodBuilder<T>` for this example)

1. `public BlockBuilder<T> body()` - returning a `BlockBuilder` the caller must explicitly call `endBlock()` on (which is annoying, but for some use cases like generating `equals()` and `hashCode()`, it is useful to be able to pass around open code blocks for several methods to things representing fields at the same time)
2. `public T body(Consumer<BlockBuilder<?>> consumer)` 

Note in both these cases, the `BlockBuilder` does not return the `MethodBuilder<T>` it came from - creating the body is the last step of declaring a method, so when the body is complete, that completes the outer builder and returns us to the thing the method was added to.


Using LinesBuilder
==================

`LinesBuilder` - and the `CodeGenerator` interface which has a `generateInto(LinesBuilder)` method is the heart of code-generation.  It makes few assumptions about *what* the text you're populating it with *is*, but handles indenting and other formatting tasks in a simple, flexible way.  It takes a `LinesSettings` which supplies things like line-wrap points, block delimiters, statement terminators and similar, which can be customized for the language (typically you create a base CodeGenerator class you'll use in most of your builders which overrides `newLinesBuilder()` to create one for your language, so that `toString()` on *all* of your builders shows the code they would generate appropriately).

While the API is quite discoverable via code-completion, here are some highlights to give you the flavor of it:

 * `onNewLine()` - guarantees you are at the start of a line (and indented appropriately)
 * `doubleNewline()` - guarantees you are at the start of a line (also indented appropriately) following a blank line
 * `word(String)` - appends a word, prepending a space if the trailing character on the current line is not whitespace
 * `appendStringLiteral(String)` - appends a string literal, escaping it appropriately (configurable by LinesSettings)
 * `appendCharLiteral(char)` - does the same thing for `char`s
 * `backup()` - removes any trailing whitespace - this is sometimes useful to avoid space-before-separator issues
 * `joining(String separator, CodeGenerator gen)` - applies all the passed code generators, using the passed delimiter to separate them if more than one
 * `backupIfLastNonWhitespaceCharNotIn(char...)` - this is sometimes useful when you have a symbol that *should* be separated by whitespace *except when it immediately follows certain delimiters*.
 * `appendRaw(char/String)` - just append some text.  If the settings says to, 

A number of methods take a `Consumer<LinesBuilder>` and apply some change in state to the builder only for calls made within the closure of that consumer:

 * `indent(Consumer<LinesBuilder>)` - indent all new lines added within the closure of the consumer by one step - this is the basic way you manage indenting; the indent amount is determined by the settings
 * `wrappable(Consumer<LinesBuilder>)` - if appending text would push the current line past the wrap point configured in the settings, continue the text on a new line
 * `hangingWrap(Consumer<LinesBuilder>) and `doubleHangingWrap(Consumer<LinesBuilder>)` - do that "hanging wrap" pattern common to formatting of many languages, where a statement that overflows the maximum line length is continued but indented to indicate the continuation
 * `delimit(char open, char close, Consumer<LinesBuilder>)` - ensure that whatever is emitted within the consumer is wrapped in the opening and closing delimiters;  some convenience methods are also provided: `squareBrackets`, `parens`, `braces`, `angleBrackets` and `backticks`
 * `block(Consumer<LinesBuilder>)` - open a code block using the block delimiters configured in settings, indent and move to a new line
 * `statement(Consumer<LinesBuilder>)` - terminate whatever is added within the passed consumer with the statement terminator configured in settings (for Java, `;` would be the statement terminator)

Note that calls to `appendRaw()` will *always append to the current line* - languages do have constructs that can *never* be separated by line breaks, and LinesBuilder must provide a way to honor those - use semantic methods like `word()` and similar to get things wrapped appropriately.


Developing a Code Generator
===========================

Developing a code generator is an iterative process - you can have useful results long before you support the full richness of the language.  An approach that has worked well is to

 * Start with a piece of code in the target language that has a bit of complexity
 * Think about, if you were going to describe in prose, human readable text, what that code does
 * Sketch out, if you were going to turn that description into code as faithfully as possible, what that ought to look like.  For example, take a Java method call

```java
Thread.currentThread().setUncaughtExceptionHandler(myHandler);
```

Turned into prose, if you were telling someone what to do to cause them to write that code, it would be something like *call setUncaughtExceptionHandler with myHandler on the result of the static method currentThread on Thread*.

So, we probably want an api that looks something like:

```java
invoke("setUncaughtExceptionHandler").withArgument("myHandler").onInvocationOf("currentThread").on("Thread");
```

(and in fact, in Java Vogon, that is exactly how you do it).

There *will* be false starts, and cul-de-sacs, and things that seem at first like they ought to be the right way of doing things, but don't result in an API that is pleasant to use, or need revision.  Having some code you're trying to become able to generate is a useful way of finding out if an approach is not going to work out as early as possible.


Creating a Rust Code Generator
==============================

We'll start by creating a base class for our builders - we are probably going to eventually have some settings for `LinesBuilder`, and we want `toString()` on our builders to return something that looks like the code they will generate (helpful for debugging).

For now this will be a placeholder - eventually we will want to override `newLinesBuilder()` to return a customized `LinesBuilder` that 
sets up indentation and other things in Rust-specific ways.  For now, we will just use the default (Java settings) which are close enough to get going.


```java
abstract class RustBuilderBase extends CodeGeneratorBase {
    
}
```

And we'll need a builder for Rust source files, that will be the entry point to generating one source file.  We'll include a way to add doc comments into the file, and a `List` to put other source file elements in, and implement a few required methods.

```java
public class RustBuilder extends RustBuilderBase implements SourceFileBuilder {

    private final List<FinishableUseBuilder<?>> uses = new ArrayList<>();

    private final List<String> docComments = new ArrayList<>();
    private final List<CodeGenerator> items = new ArrayList<>();
    private final String cratePath;
    private final String sourceName;

    RustBuilder(String cratePath, String sourceName) {
        this.cratePath = cratePath;
        this.sourceName = sourceName;
    }

    // Some validation code we'll reuse for all methods that take identifiers
    /**
     * Validate a rust identifier.
     *
     * @param <C> The type, so non-string types are usable
     * @param ident The identifier
     * @return the identifier, unless something is wrong with it
     * @throws InvalidIdentifierException if the identifier is illegal
     */
    static String validateIdentifier(String ident) {
        return validateIdentifier(ident, false);
    }

    /**
     * Validate a rust identifier.
     *
     * @param <C> The type, so non-string types are usable
     * @param crateKeywordOk - the <code>crate</code> keyword may be part of use
     * paths, so there are occasions where it is a valid identifier.
     * @param ident The identifier
     * @return the identifier, unless something is wrong with it
     * @throws InvalidIdentifierException if the identifier is illegal
     */
    static String validateIdentifier(String ident, boolean crateKeywordOk) {
        // Placeholder for doing detailed validation of rust identifiers.
        // The rules for Java are similar enough to use here:
        for (int i = 0; i < notBlank("ident", ident).length(); i++) {
            char c = ident.charAt(i);
            switch (i) {
                case 0:
                    if (!Character.isJavaIdentifierStart(c)) {
                        throw new InvalidIdentifierException("Bad character '"
                                + c + "'", ident);
                    }
                    break;
                default:
                    if (!Character.isJavaIdentifierStart(c)) {
                        throw new InvalidIdentifierException("Bad character '"
                                + c + "'", ident);
                    }
            }
        }
        // I will include the source to RustKeywords in an addendum to this
        // tutorial - just leave this commented for now
        /**
        RustKeywords.find(ident).ifPresent(keyword -> {
            switch (keyword) {
                case CRATE:
                    if (crateKeywordOk) {
                        break;
                    }
                // Fallthrough
                default:
                    throw new InvalidIdentifierException("'" + ident + "' is a keyword");
            }
        });
        */
        return ident;
    }

    /**
     * Create a new RustBuilder with a path within the crate.
     *
     * @param cratePath A :: delimited crate path
     * @return A function that will create the source file builder
     */
    public static Namer<RustBuilder> forCratePath(String cratePath) {

        Arrays.asList(cratePath.split("::")).forEach(RustBuilder::validateIdentifier);
        return name -> new RustBuilder(cratePath, validateIdentifier(name));
    }

    /**
     * Create a rust builder in the source root of the crate.
     *
     * @param name A name
     * @return
     */
    public static RustBuilder named(String name) {
        return new RustBuilder(null, validateIdentifier(name));
    }

    @Override
    public String name() {
        return sourceName;
    }

    @Override
    public String fileExtension() {
        // Used by SourceFileBuilder.save() to construct a file name
        return ".rs";
    }

    @Override
    public String namespaceDelimiter() {
        // The superclass's sourceFilePath() method needs to know how to split
        // the namespace
        return "::";
    }

    @Override
    public Optional<String> namespace() {
        // This will be used by SourceFileBuilder.save() to construct a file path
        return Optional.ofNullable(cratePath);
    }

    public RustBuilder docComment(String comment) {
        // Ensure newlines in doc comments don't get their tail
        // appended without the doc comment prefix into the source file
        docComments.addAll(Arrays.asList(comment.split("\n")));
        return this;
    }

    @Override
    public void generateInto(LinesBuilder lines) {
        // Write top level documentation comments at the head of the file
        for (String docCommentLine : docComments) {
            lines.onNewLine().appendRaw("//! ").appendRaw(docCommentLine);
        }
        // Write use statements next
        Set<FinishableUseBuilder<?>> uses = FinishableUseBuilder.coalesce(this.uses);
        uses.forEach(u -> u.generateInto(lines));

        // And then the items defined in this file
        items.forEach(item -> item.generateInto(lines));
    }

    public static void main(String[] args) {
        // And a main method we can add to, so we can review code as we go:
        RustBuilder rb = new RustBuilder(null, "Stuff");

        rb.docComment("This is a demo of our rust builder.\nIsn't it cool?")
                .docComment("I think it is!");

        rb.use("std").withPathElement("fmt")
                .of("Display", "Debug");

        System.out.println(rb.toString());
    }
}
```

Run the `main()` method and you will see some properly formatted documentation
comments printed out.


Adding Use Statements
---------------------

Another element found at the head of a Rust file is `use` statements, similar to Java `import` statements.

They use `::` delimited paths, but have a syntax for importing multiple items from a single path with a
syntax like `use std::fmt::{Debug, Display};` - and it is good style to use that approach for `use` statements
that can be coalesced this way, so we will implement it to do that.

There is also a special import syntax for using items defined in the current Rust `crate` (library), in which
the first path element is `crate`, so we will add some handling for that.

We are going to write two builder classes:

 - `UseBuilder`, which lets the user construct the base path
 - `FinishableUseBuilder` which contains one or more things to import from that path

`FinishableUseBuilder` will simply *be* the thing that gets stored in the list of use statements for our
`RustBuilder` and will directly implement `CodeGenerator` to render `use` statements.

So, this will be our first internal-to-a-file source element builder.  A very useful pattern is for the
builder to directly implement `CodeGenerator`, and take a function that is passed `this` (for the thing
that created it to add it to its internal set of things that generate code), and then return whatever
the caller wants to return.  That way we create a generic builder that can be reused as part of many
different elements that can contain it.

Let's create a base class for that now:

```java
/**
 * Base class for builders which follow the pattern taking a function converts
 * some generated object (possibly this) and returning whatever the thing that
 * created returns.
 *
 * @author Tim Boudreau
 * @param <B> The type of this
 * @param <T> The type this builder returns when completed, whatever that means
 */
public abstract class RustSourceElementBuilder<B, T> {

    protected final Function<? super B, ? extends T> converter;

    protected RustSourceElementBuilder(Function<? super B, ? extends T> converter) {
        this.converter = notNull("converter", converter);
    }

    // These methods are package private - we don't want consumers of our builders
    // seeing or potentially calling them - they are internal use only.
    void validate() {
        // Override to ensure we are in a valid state - no missing elements.
        // In general, that should be made impossible by always returning a
        // new builder type that cannot be completed without *supplying* the
        // missing element, but there are a few situations where it may be 
        // unavoidable
    }

    T _finish(B with) {
        // Execute the converter - it is up to the subclass to ensure that
        // the builder is in a usable state and call this from some method with
        // an appropriate name for the task the caller is doing
        validate();
        return converter.apply(with);
    }
}
```

So, our first builder is the step of constructing the path-prefix for the `use` statement - the head of it looks like this:

```java
    public static class UseBuilder<T> extends RustSourceElementBuilder<FinishableUseBuilder<T>, T> {
        private final List<String> pathElements;

        UseBuilder(String[] pathElements, Function<? super FinishableUseBuilder<T>, ? extends T> converter) {
            super(converter);
            // Path elements have already been validated
            this.pathElements = new ArrayList<>(asList(pathElements));
        }

        /**
         * Add sequential path elements. Any elements containing the string "::"
         * will be split.
         *
         * @param pathElement A path element or a :: delimited crate path
         * @return
         */
        public UseBuilder<T> withPathElement(String pathElement) {
            Arrays.asList(notEmpty("pathElement", pathElement).split("::"))
                    .forEach(element -> pathElements.add(validateIdentifier(element, true)));
            return this;
        }

        /**
         * The path should start with <code>crate::</code> - this method may be
         * called before or after other path elements are added, and if called
         * multiple times, will only add the element once, to the head.
         *
         * @return this
         */
        public UseBuilder<T> inCrate() {
            // create MUST be the first element and must not be repeated
            if (pathElements.isEmpty() || !"crate".equals(pathElements.get(0))) {
                pathElements.add(0, "crate");
            }
            return this;
        }
```

Our general "I'm done with the path, let me add the things on that path I want to import" method will create an instance of our not yet created `FinishableUseBuilder`, and looks like:

```java
        /**
         * Add a first type name to this set of use statements.
         *
         * @param firstType A name
         * @return
         */
        public FinishableUseBuilder<T> withType(String firstType) {
            // At this point, we discard this builder, only passing our collected path
            // elements into the finishable builder
            return new FinishableUseBuilder<>(validateIdentifier(firstType),
                    pathElements, converter);
        }
```


Now, the point of using builders for code generation is that they should be *nice and intuitive to use* - a lot of times,
the user may be adding just a single `use` statement or a known list of types, so let's add some methods that make that
trivial:

```java
        /**
         * Convenience method to add multiple types and close this builder.
         *
         * @param type The first type
         * @param moreTypes Some more types
         * @return the result of this builder
         */
        public T of(String type, String... moreTypes) {
            FinishableUseBuilder<T> result = withType(type);
            for (String t : moreTypes) {
                result.withType(t);
            }
            return result.build();
        }

        /**
         * Convenience method for when you are only adding a single use.
         *
         * @param type The type
         * @return The return type of this builder
         */
        public T withSingleType(String type) {
            return withType(type).build();
        }
```

Now we need to create our `FinishableUseBuilder`, which will collect the list of types to be 
used that go at the end of the path, and will be retained represent one use statement in our rust source
that can render them into a `LinesBuilder`.

Note that this is a builder that **must** have a "we're done now" method to close it and exit to the
thing that created the original `UseBuilder`.  We will simply call that method `build()` (the right
name may depend on how users typically conceptualize the thing they are doing - there is no one intuitive
answer for everything).

The head of it is straightforward - we'll put the collected (and validated) path elements in a list,
and create a `Set` for the types being imported, and add a method for adding one type to the set, and
a build method:

```java
    public static class FinishableUseBuilder<T>
            extends RustSourceElementBuilder<FinishableUseBuilder<?>, T>
            implements CodeGenerator,
            Comparable<FinishableUseBuilder<?>> {

        // Path elements already collected in the UseBuilder that created us
        final List<String> pathElements = new ArrayList<>();

        // A collection of type names to import - use a set to prune duplicates,
        // and a TreeSet so they will be pre-sorted
        final Set<String> types;

        public FinishableUseBuilder(String firstType, List<String> pathElements,
                Function<FinishableUseBuilder<?>, T> f) {
            super(f);
            this.types = new TreeSet<>();
            this.types.add(firstType);
            this.pathElements.addAll(pathElements);
        }

        /**
         * Add a type to the list of those in our use statement.
         *
         * @param type A type
         * @return this
         */
        public FinishableUseBuilder<T> withType(String type) {
            types.add(validateIdentifier(type));
            return this;
        }

        /**
         * Conclude this builder, adding the resulting use statement to its
         * owner.
         *
         * @return The result of this builder
         */
        public T build() {
            // conceivable, so guard against it
            // we don't EVER want to generate garbage if we can avoid it:
            if (pathElements.isEmpty()) {
                throw new IncompleteSourceElementException(FinishableUseBuilder.class);
            }
            return converter.apply(this);
        }
```

Now, "we're done now" methods like our `build()` are a necessary evil.  A nicer user experience is
to give the user a way to say "Here's a type name, and it's the last one, so get me out of here.  So we'll
add:


```java
        /**
         * Add a final type and close this builder.
         *
         * @param type A type name
         * @return the result of this builder
         */
        public T finishingWithType(String type) {
            return withType(type).build();
        }
```

Finally, we need to implement code-generation:

```java
        @Override
        public void generateInto(LinesBuilder lines) {
            // statement() will append a ; (or whatever the LineBuilderSettings
            // say is the statement terminator) on exit:
            lines.statement(lst -> {
                // Make sure we're on a new line
                lines.onNewLine().word("use")
                        // delimit() does not assume a preceding space, and
                        // word() only inserts a space preceding the text it is
                        // passed, so force a space here:
                        .space()
                        // And render all of our path elements, with :: between
                        // them
                        .delimit("::", pathElements.toArray(String[]::new));
                // There is a last :: before the first type
                lines.appendRaw("::");
                if (types.size() > 1) {
                    // If we have a set of types, use { type1, type2 } syntax
                    lines.delimit('{', '}', lb -> {
                        // And comma-delimit the list
                        lines.commaDelimit(types.toArray(String[]::new));
                    });
                } else {
                    // else, we have one type to include, immediately after the
                    // last ::
                    lines.appendRaw(types.iterator().next());
                }
            });
        }
```

At the top of this class, we implemented `Comparable` (so generated `use` statements will be nicely alpha-sorted), so
we need to implement that now - it can simply compare on `toString()` and it will get the right result:

```java
        @Override
        public int compareTo(FinishableUseBuilder<?> o) {
            return toString().compareTo(o.toString());
        }
```

Lastly, we need to implement *coalescing* - so if we have instances for `use std::fmt::Display` and
`use std::fmt::Debug`, those get coalesced into a single `FinishableUseBuilder` containing all of the
elements on the same path.

To do this safely, we will want a way to make a *copy* of a `FinishableUseBuilder` so that we don't
alter the state of the elements the rust source has by changing their contents after the fact.  So
we'll add an additional constructor and a `copy()` method, so that the coalesced instance isn't one
of the originals:

```java
        private FinishableUseBuilder(List<String> pathElements, Set<String> types) {
            // copy constructor - should never get add methods called
            super(b -> {
                throw new IllegalStateException("Attempt to add to a synthetic builder.");
            });
            this.types = types;
        }

        private FinishableUseBuilder<T> copy() {
            return new FinishableUseBuilder<>(pathElements, types);
        }
```

and now we can implement our coalescing:


```java
        static Collection<? extends FinishableUseBuilder<?>> coalesce(Collection<? extends FinishableUseBuilder<?>> c) {
            // The list of strings will be equal for any use statement with the
            // same starting path, so that is our list of keys
            Map<List<String>, FinishableUseBuilder<?>> m = new HashMap<>();
            for (FinishableUseBuilder<?> b : c) {
                m.compute(b.pathElements, (path, old) -> {
                    if (old != null) {
                        // The map already had a match for this path, so just
                        // merge this ones types into that one
                        old.types.addAll(b.types);
                        return old;
                    }
                    // Make a copy so we don't alter the state of the original
                    return b.copy();
                });
            }
            // Return a sorted set of the results so they are alpha sorted
            // when they are asked to render themselves
            return new TreeSet<>(m.values());
        }
```

Now we need to add to our source builder a list to collect use statements in - at the head of that file:

```java
    private final List<FinishableUseBuilder<?>> uses = new ArrayList<>();
```

and a way to add to them - for user convenience, allow some additional path elements to be passed in as varags, so the user can go straight to entering the types if they want:


```java
    public UseBuilder<RustBuilder> using(String firstPathElement, String... more) {
        UseBuilder<RustBuilder> result = new UseBuilder<>(new String[]{firstPathElement}, fub -> {
            uses.add(fub);
            return this;
        });
        for (String m : more) {
            result = result.withPathElement(m);
        }
        return result;
    }
```

and we need to add code to actually *render* the use statements into the generated source - after
the line that prints line comments, in `RustBuilder.generateInto()`, insert

```java
        // Write use statements next
        FinishableUseBuilder.coalesce(this.uses)
                .forEach(useStatement -> useStatement.generateInto(lines));

```

Lastly, sometimes a path is just a path - we should provide a way to just pass a full path to a 
type in a single string - but apply all of our validation and coalescing goodness to it:


```java
    public RustBuilder use(String fullUsePath) {
        String[] parts = fullUsePath.split("::");
        UseBuilder<RustBuilder> ub = this.using(parts[0]);
        for (int i = 1; i < parts.length - 1; i++) {
            ub = ub.withPathElement(parts[i]);
        }
        return ub.withSingleType(parts[parts.length - 1]);
    }

```


### Eliminating "I'm done here" Methods with Closure-Based Builder Methods

As mentioned - "I'm done here" methods are a necessary evil - they annoy the user with boilerplate,
and can result in unpleasant missing-code surprises if the user forgets to call them.

Fortunately, there is a pattern that makes them vanish:  A method that takes a `Consumer` of the
builder type that would otherwise be returned, and automatically closes the builder after the
consumer has exited.

Consumer-based builder methods have another nice side-effect:  *The indentation and structure of
the code that generates the code **mirrors the structure of the code it generates**.* That can
make reasoning about code-that-generates code much easier, and discourages it from devolving into
a sea of spaghetti code.

For this task, we will use the `Holder<T>` utility class from `code-generation-common`, which makes
implementing this pattern easy.  Since you can't alter the value of a variable declared outside a Java
lambda from within a lambda, we need a mutable place to keep the value we want to return.  *And* we
want to handle the case that the `build()` method was never called by the consumer, and do it for the
caller - that is how we make boilerplate disappear!

For cases where there isn't a `build()` method, but there are required things that need to be set,
`Holder` also has a `get()` method that takes an error message, and throws an exception if the
closure did not complete the builder.  A call to that is included below, even though it is not
strictly needed here (since we will have already closed it for the user in the line above).

In `UseBuilder`, add


```java
        /**
         * Add types to a use path within the closure of a consumer, which takes
         * care of closing this use builder.
         * 
         * @param type The first type name
         * @param c A consumer - the builder passed to it will return null from
         * its build method; if it is not built prior to exiting the consumer,
         * it will be at the exit point of this method.
         * @return The return type of this builder
         */
        public T withType(String type, Consumer<? super FinishableUseBuilder<?>> c) {
            // This will hold our return value, to ensure it is closed within the
            // closure of the passed consumer
            Holder<T> holder = new Holder<>();
            // Our actual builder will be parameterized on Void - we very intentionally
            // do NOT want to create two paths back to the object that created this
            // instance, or it will be incredibly non-obvious what added code where
            FinishableUseBuilder<Void> result = new FinishableUseBuilder<>(type,
                    pathElements, (FinishableUseBuilder<?> bldr) -> {
                        // This is the call that actually adds the builder to
                        // the set of use statements
                        T obj = super.converter.apply(bldr);
                        // We set the output of that, whatever it is, on the holder,
                        // to provide the return value of the outer method here
                        holder.set(obj);
                        // Return null, so code can't keep going adding things to
                        // the parent element here AND ALSO in the code that called us
                        return null;
                    });
            // Now give the 
            c.accept(result);
            holder.ifUnset(result::build);
            return holder.get("FinishableUseBuilder was not completed");
        }
```


### Try it out:

Add a line to our `main` method to make use of all the permutations of adding use statements that we have just
created:

```java
        rb.using("std").withPathElement("fmt").of("Display", "Debug")
                .using("foo", "sets").withType("Clearable",
                types -> {
                    types.withType("Clearing")
                            .withType("Completentable")
                            .withType("Intersecting");
                })
                .use("std::hash::Hash");
```


Patterns
--------

At this point you can see there are some patterns that will be common to writing builders for source elements.  Let's run through a few of them.

1. Sub-builder constructors should be package private - the only way to construct one should be via a method on a builder for whatever element can legitimately contain that element - you don't want to have to support builders instantiated out-of-context in an ad-hoc way, and that will severely limit your flexibility later.

2. When writing a builder method that takes a `Consumer`, the closure's signature should be, e.g. `Consumer<? super YourSubBuilder<?>>` and the actual instance of the sub-builder you instantiate to pass to the consumer should be `Void` and return null.  This prevents there being two paths to the same ancestor object that could allow appending elements to it.  Say you could, in generating a code block, do something like this:

```java
codeBlock.statement("before").someBuilder(bldr -> {
  bldr.finish("blah")
    .statement("inner statement") // if bldr returns codeBlock
    .statement("another inner statement");
})
  .statement("after");
```

Should the "inner statement" come before "after", or after it?  There is no right answer; there is not even an intuitive answer; and ANY answer destroys **your** ability to make any choices about when you call the consumer in the future.  So the rule is, if you're calling a `Consumer`, don't leak any objects above the thing you pass into it - it's at best confusing, at worst, dangerous.

3. Keep builders small and focused - that makes them more reusable, and keeps the complexity manageable

4. There are two basic patterns for any method that returns a sub-builder:

5. Imperative operation:  Return a builder instance that takes a function that adds the element it builds to its creator, so your state gets updated when the builder is completed (and the builder need make no assumptions about what type of source element contains it - you want them reusable):

```java
public FooBuilder<OriginalBuilder<T>> withFoo(String foo) {
    return new FooBuilder(bldr -> { // bldr IS the builder we're instantiating here
                                    // although sometimes it might return something else - consider
                                    // starting a conditional expression for an if statement - you probably want
                                    // sub builders for the condition, if body, else clauses, else bodies, and
                                    // have FooBuilder here return the end product of a whole chain of builders,
                                    // each focused on one of those things.
      this.foo = bldr;
    });

```

6. As a general rule, try to avoid methods that take no arguments - sort of, *create me a builder for X* methods - a method without arguments is a wasted opportunity to provide convenience for the caller.  If you need to create a builder for some complex sub-element of code - say, a `BarBuilder<FooBuilder>`, well, `Bar` takes some arguments to methods of its own?  Say `BarBuilder` has a `Baz` sub-element.  Instead of making the caller write `whatever.newFooBuilder().withBaz(...)`, make it `whatever.withBaz(...)` and have that return the `FooBuilder` - so, that call might return a `BazBuilder<BarBuilder<FooBuilder>>` and completing `Baz` drops you into the `BarBuilder` and completing *that* drops the caller back where they started.  Contrived example:

```java
public BazBuilder<BarBuilder<FooBuilder<OriginalBuilder<T>>>> withBaz(String bazStart) {
    return new FooBuilder<>(bldr -> {
        this.foo = bldr;
        return bldr;
    }).withBar().withBaz(bazStart);

```

7. Expect eventually to write a small type-expression parser - being able to validate that a type-expression is valid (e.g. generics are closed, the type name is valid and similar, and possibly extract enough information to determine generally what kind of thing a type name is [for languages with "primitives" vs "objects" distinctions]) - it is useful to be able to fail early if you're generating code that no compiler on the planet will accept.  In the case of Rust, we will definitely want one, because type expressions involve paths, lifetimes and two different flavors of type parameters.  To start out with, you can just write something that accepts a string - but expect that eventually you'll want to improve on it.

### Inheritance

In general, the rule with inheritance and code element builders is **just don't** - the point is to create an API
that is human readable, such that you can tell what the generated code looks like by looking at the code that
generates it.  That means you will use a lot of different verbs and prepositions as method names, even though what they generate is
the same - a assign a variable *to* something, you invoke a method *on* something, you call something *with* an
arguemnt and so forth.  While it might be tempting to have the *one builder to rule them all*, the result will either
have spurious methods that aren't relevant in all contexts, or at best be non-intuitive to use.  The goal is to
create convenience for users of the API.  It's okay if that causes a little pain.

There is one (thus far) category of exception to that rule - code block builders for if/else clauses.  Here, you've
got a thing that has all the methods of any code block builder (which you want to be able to reuse in lambdas and the
like), but in addition to those, it *really should have `elseIf()` and `orElse()` methods* - that is the most intuitive
pattern for doing it - so a user can write something like:

```java
iff("x == y").invoke("foo").withArgument("bar").inScope().orElse().invoke("doSomethingElse").on("thing").endBlock();
``

or even more prettily

```java
iff("x == y", iff -> {
    iff.invoke("foo").withArgument("bar").inScope();
    iff.orElse(elseBlock -> {
        elseBlock.invoke("doSomethingElse").on("thing");
    });
});
```

Here, we run into a problem, particularly when chaining method calls:  The return value of chainable methods 
on our `BlockBuilder`, if it was already written just for creating vanilla code blocks, is `BlockBuilder`.

We could subclass it for an `IfBlockBuilder`, but all of its methods are going to return the supertype,
which doesn't have any `orElse()` methods, even though the concrete type we're returning does.

This is where generics come to the rescue:  We're going to move all of the base class methods to a superclass,
which - like `java.lang.Enum`, is parameterized on its own type, and the type parameter is what we will return.
It involves one cast we'll need a `@SuppressWarnings("unchecked")` for, but which is harmless as long as we
take care that the type parameter and the type really do always match, and don't make it possible to subclass
the builder outside of its package, so there cannot be subclasses that do screwey things with it.

The pattern looks like:

```java
public abstract class BlockBuilderBase<T, B extends BlockBuilderBase<T>> implements CodeGenerator {
   // or more likely, extending a convenience subclass that already takes a Function in its constructor

   private final List<CodeGenerator> contents = new ArrayList<>();

   // ...
   
   @SuppressWarnings("unchecked")
   B cast() {
       return (B) this;
   }

   // All of our instance methods to add code will return B and call cast() to get it:
   public B statement(String statement) {
       contents.add(new Statement(statement));
       return cast();
   }

   // ... and so forth
}

public final class BlockBuilder<T> extends BlockBuilderBase<T, BlockBuilder<T>> {
   // no instance methods needed - they're all in the superclass
}

public final class IfBlockBuilder<T> extends BlockBuilderBase<T, IfBlockBuilder<T>> {

   // Each child builder will be parameterized on T - capable of closing the if statement,
   // At which point the chain of child ifs/blocks get added to the top if builder (not shown)
   public IfBlockBuilder<T> elseIf(String condition) { ... }

   public BlockBuilder<T> orElse() { ... }

```

So, while in general, source element builders are orthagonal enough that subclassing is genuinely
harmfull, in the particular case where you want *all* of the functionality exactly as is - the thing
you're creating really just builds an enhanced version of the thing its superclass creates, then
this is the cleanest approach.


Creating a Rust Trait Builder
-----------------------------

Rust *traits* are considerably more complex than a use-statement - so this will be our baptism of fire, dealing with
more deeply nested structures.  Here is an example of a trait representing the look-up-by-index and find-index-of
aspects of a collection of some things (that happen to implement the trait `UnsignedInt`) in an array or list or
similar:


```rust
pub trait Indexed<'a, S: UnsignedInt + ?Sized, T: 'a> {
    /// Look up by index
    fn index_of(&self, item: &T) -> Option<S>;
    /// Look up index by value
    fn for_index(&'a self, index: S) -> Option<&'a T>;
}

Traits can have super-traits in a `+` delimited list following the name / generics declaration, and can have implementation methods:

```rust
pub trait Bits<S: UnsignedInt>: Unpin + Display + Debug {
    /// The total capacity, used or not, of this set
    fn len(&self) -> S;
    /// The count of set bits in this set
    fn cardinality(&self) -> S;
    /// Get whether or not one bit is set
    fn get(&self, bit: S) -> bool;
    /// Determine if the set is empty
    fn is_empty(&self) -> bool {
        let card = self.cardinality();
        let zero: S = UnsignedInt::zero();
        zero == card
    }
    fn min(&self) -> Option<S>;
    fn max(&self) -> Option<S>;
    fn next_set_bit(&self, index: S) -> Option<S>;
    fn prev_set_bit(&self, index: S) -> Option<S>;
}
```

So let's get started with our `TraitBuilder`, using the same pass-a-function pattern.  It will take the name of the trait in its constructor, and then we'll need to create builders for all of the things we see here:

*PENDING: While I have draft code for this, I'd like to get it tightened down better - the rust type system - with its lifetimes and multiple different flavors of generics - is complex enough that to do this well, it is a good idea to back up here and do at least a minimal type-parser before we get here.  So, I'm ironing that out and then will come back to this.*
