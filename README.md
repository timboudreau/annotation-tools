Annotation Tools
================

A toolkit to make it easy to write annotation processors - particularly ones that generated
complex Java source code, or need to process annotations in a well-defined order, or generate
index files into `META-INF` - and to make it easy to **validate** annotations _before
generating any code_, using builders to create complex `Predicate` instances which drill
through an annotation, sub-annotations, types and members and allow you to validate annotated class
and method signatures, resolve types mentioned in annotations, etc., with a few lines of code
mentioning class names.

A particular goal is to make it easy to write annotation
processors which _do not directly reference the classes they process_ - as in, they don't
need to be loaded by a classloader when javac is running.  That is good for performance,
avoids IDEs throwing exceptions when they try to load a class you're working on, and 
avoids the annotation processor winding up bundled at runtime where it is not needed.

The `AnnotationUtils` class is the heart of this tooling, as it makes it trivial to do
the kind of lookups on `AnnotationMirror` instances that are otherwise painful and error-prone
and lead annotation processor authors to reach for `.class` files or refrain from writing
an annotation processor altogether.


Annotation Validation
---------------------

A picture being worth a thousand words, here is an example of validating an unusually
complex annotation from the NetBeans ANTLR Support suite.  It results in a simple
`Predicate<AnnotationMirror>` which can be applied to multiple annotation types, and
drills through a number of child annotations to fully validate the annotation before any
other processing occurs:

```
        mirrorTest = utils.multiAnnotations().whereAnnotationType(REGISTRATION_ANNO, mb -> {
            mb.testMember("name").stringValueMustNotBeEmpty()
                    .stringValueMustBeValidJavaIdentifier()
                    .build()
                    .testMember("mimeType")
                    .validateStringValueAsMimeType().build()
                    .testMember("lexer", lexer -> {
                        lexer.asTypeSpecifier()
                                .hasModifier(PUBLIC)
                                .isSubTypeOf("org.antlr.v4.runtime.Lexer")
                                .build();
                    })
                    .testMemberAsAnnotation("parser", parserMember -> {
                        parserMember.testMember("type").asTypeSpecifier(pType -> {
                            pType.isSubTypeOf("org.antlr.v4.runtime.Parser")
                                    .hasModifier(PUBLIC);
                        }).build()
                                .testMember("entryPointRule")
                                .intValueMustBeGreaterThanOrEqualTo(0)
                                .build()
                                .testMember("parserStreamChannel")
                                .intValueMustBeGreaterThanOrEqualTo(0)
                                .build()
                                .testMember("helper").asTypeSpecifier(helperType -> {
                            helperType.isSubTypeOf("org.nemesis.antlr.spi.language.NbParserHelper")
                                    .doesNotHaveModifier(PRIVATE)
                                    .mustHavePublicNoArgConstructor();
                        }).build();
                    })
                    .testMemberAsAnnotation("categories", cb -> {
                        cb.testMember("tokenIds")
                                .intValueMustBeGreaterThan(0).build();
                        cb.testMemberAsAnnotation("colors", colors -> {
                            Consumer<AnnotationMirrorMemberTestBuilder<?>> c = ammtb -> {
                                ammtb.arrayValueSizeMustEqualOneOf(
                                        "Color arrays must be empty, or be RGB "
                                        + "or RGBA values from 0-255", 0, 3, 4)
                                        .intValueMustBeLessThanOrEqualTo(255)
                                        .intValueMustBeGreaterThanOrEqualTo(0);
                            };
                            for (String s : new String[]{"fg", "bg", "underline", "waveUnderline"}) {
                                colors.testMember(s, c);
                            }
                            colors.testMember("themes")
                                    .stringValueMustNotContain('/').build();
                        });
                    })
                    .testMemberAsAnnotation("syntax", stb -> {
                        stb.testMember("hooks")
                                .asType(hooksTypes -> {
                                    hooksTypes.isAssignable(
                                            "org.nemesis.antlr.spi.language.DataObjectHooks")
                                            .nestingKindMustNotBe(NestingKind.LOCAL)
                                            .build();
                                })
                                .valueAsTypeElement()
                                .mustHavePublicNoArgConstructor()
                                .testContainingClasses(cc -> {
                                    cc.nestingKindMayNotBe(NestingKind.LOCAL)
                                            .doesNotHaveModifier(PRIVATE);
                                })
                                .build()
                                .build();
                    }).testMemberAsAnnotation("genericCodeCompletion", gcc -> {
                        gcc.testMember("ignoreTokens")
                                .intValueMustBeGreaterThan(0).build();
                    });
            ;
        }).build();
```

Java Code Generation
--------------------

`java-vogon` started as one of those _this is too small a project to bother with a big code generation
library and Java Poet's API isn't that nice_ things, and grew like a weed from there.  Use it, or use
whatever you like for code generation.  It does provide a straightforward, self-explanatory API for generating
code which takes advantage of lambdas for clarity and scoping:

```
    private String generateJavaSource(String pkg, String className, String method, AnnotationMirror anno, ExecutableElement on) {
        String mimeType = utils().annotationValue(anno, "mimeType", String.class);
        Integer order = utils().annotationValue(anno, "order", Integer.class, Integer.MAX_VALUE);
        String displayName = utils().annotationValue(anno, "displayName", String.class, "-");
        String generatedClassName = className + "__Factory";
        ClassBuilder<String> cb = ClassBuilder.forPackage(pkg).named(generatedClassName)
                .makeFinal().makePublic()
                .importing(NAV_PANEL_TYPE)
                .importing("javax.annotation.processing.Generated")
                .annotatedWith("Generated", ab -> {
                    ab.addStringArgument("value", getClass().getName()).addStringArgument("comments", versionString());
                })
                .method("create", mb -> {
                    mb.withModifier(Modifier.PUBLIC).withModifier(Modifier.STATIC)
                            .returning("NavigatorPanel")
                            .annotatedWith(NAV_PANEL_REGISTRATION_ANNO)
                            .addStringArgument("displayName", displayName)
                            .addStringArgument("mimeType", mimeType)
                            .addArgument("position", order + "")
                            .closeAnnotation()
                            .body()
                            .returningInvocationOf(method, invocation -> {
                                invocation.withStringLiteral(mimeType)
                                        .on(pkg + "." + className + "." + method);
                            });

                });
        return cb.build();
    }
```

The API consists of builders for various elements of a Java source, the entry point to
all of which is `ClassBuilder` (typically using `ClassBuilder.forPackage(somePackage).named(className)`).


### Consumer vs. Imperative Invocation

Code generation methods generally come in two forms:

 * _Imperative_ - returns a different type of builder that lets you build and close the element requested,
e.g. `classBuilder.method("getFoo").returning("String").body().returningStringLiteral("foo").endBlock()`
 * _Consumer-Based_ - the method takes a `Consumer` and perhaps some other argument;  the consumer must
complete the builder it is passed (immediately), e.g. 
```classBuilder.overridePublic("getFoo", mb -> {
    mb.returning("String").body (bb -> {
        bb.returningStringLiteral("foo");
    });
});```

Choosing which to use is usually a matter of preference and readability, but there are some differences:

 * With the imperative form, it is easier to leave class elements uncompleted and then wonder why some
code is never generated
 * With the consumer form, if there is a single natural exit point for a builder (such as `endBlock()` 
on a code block builder), it will be called for you if you don't call it, so less boilerplate is needed; 
in some cases where there are multiple exit points, but one is very common, that will be called for you
if you did not call one of the others (for example, an `InvocationBuilder` will get `inScope()` called on
it, making it a reference method call on the current class or a static import, if you did not specify 
what to use).  If there is no natural exit point, an `IllegalStateException` will be thrown if the
consumer left a builder unfinished so there is no valid code to add.
 * The imperative form is useful if you structured your generation code such that you want to be able
to keep a builder around for a while while generating other code - for example, if there is a static
block that will initialize some fields, and multiple pieces of code will need to contribute to it
as the rest of the class is generated

As you can see from the two different styles of declaring annotations in the example above, with most things, 
you can either provide
a `Consumer` for an element, or add it imperatively (requiring the closeAnnotation()
call to close the builder, which is implicit in exiting the lambda), so you are free to choose which style you
prefer based on taste or readability.

Note that, in almost all methods that take a consumer, the generic type of the builder the consumer
is passed is <code>?</code> and the method that closes that builder will return null - this is so
that there are not two code paths by which the parent element could be altered, which would get
confusing quickly.  So, for example, the two <code>ClassBuilder.method</code> methods have
signatures like:

 * `MethodBuilder&lt;ClassBuilder&lt;T&gt;&gt; method(String methodName)` returns a `MethodBuilder` whose
`closeMethod()` method must be called to add it to the `ClassBuilder` it came from, and which returns
that `ClassBuilder`.
 * `ClassBuilder&lt;T&gt; method(String methodName, Consumer&lt;MethodBuilder&lt;?&gt;&gt;)` passes 
a `MethodBuilder` to the passed consumer, whose
`closeMethod()` method need not be called (but it is harmless) to add it to the `ClassBuilder` it came from, and which returns
null (you continue adding to the `ClassBuilder` using the return value of `method(String, Consumer)` while
you create the method's signature and body in the consumer.  When the Consumer exits, the method is
automatically added to the `ClassBuilder`

This pattern is repeated throughout the API.
 

NetBeans Module Layer Support
-----------------------------

Extensions to NetBeans' `LayerGeneratingProcessor` are included - if you're not writing NetBeans
plugins, just ignore them - they are harmless.


Delegates and Ordered Processing
--------------------------------

The annotation processing API makes no guarantees about the order in which annotations will be processed, but
in complex systems, frequently you do want all instances of one annotation to be processed before another one
is, in order to use the output from one annotation processor in the next.

Accordingly, there are `AbstractRegistrationProcessor` and (NetBeans module specific) `AbstractLayerGeneratingRegistrationProcessor`,
which solve this problem:  Subclass one of these, and override `installDelegates(Delegates)` - then factor your annotation processing
into annotation-specific subclasses of `Delegate` or `LayerGeneratingDelegate`, and override one or more of

 * `processConstructorAnnotation`
 * `processFieldAnnotation`
 * `processMethodAnnotation`
 * `processTypeAnnotation`

Here is an example - the annotation type constants are simply `String`s - fully qualified
class names:

```
    @Override
    protected void installDelegates(Delegates delegates) {
        LanguageRegistrationDelegate main = new LanguageRegistrationDelegate();
        KeybindingsAnnotationProcessor keys = new KeybindingsAnnotationProcessor();
        LanguageFontsColorsProcessor proc = new LanguageFontsColorsProcessor();
        HighlighterKeyRegistrationProcessor hkProc = new HighlighterKeyRegistrationProcessor();
        delegates
                .apply(keys).to(ElementKind.CLASS, ElementKind.METHOD)
                .whenAnnotationTypes(KEYBINDING_ANNO, GOTO_ANNOTATION)
                .apply(proc).to(ElementKind.CLASS, ElementKind.INTERFACE)
                .onAnnotationTypesAnd(REGISTRATION_ANNO)
                .to(ElementKind.FIELD).whenAnnotationTypes(SEMANTIC_HIGHLIGHTING_ANNO)
                .apply(proc).to(ElementKind.CLASS, ElementKind.INTERFACE)
                .onAnnotationTypesAnd(REGISTRATION_ANNO)
                .to(ElementKind.FIELD).whenAnnotationTypes(GROUP_SEMANTIC_HIGHLIGHTING_ANNO)
                .apply(hkProc)
                .to(ElementKind.FIELD)
                .whenAnnotationTypes(SEMANTIC_HIGHLIGHTING_ANNO)
                .apply(hkProc)
                .to(ElementKind.FIELD)
                .whenAnnotationTypes(GROUP_SEMANTIC_HIGHLIGHTING_ANNO)
                .apply(main).to(ElementKind.CLASS)
                .whenAnnotationTypes(REGISTRATION_ANNO);
    }
```

This factoring out of processing related annotations, or phases of processing one annotation, is very
useful for improving the maintainability of annotation processors, since the result is not a single
giant annotation processor that does everything.

Delegates may share data in a type safe way, using type-safe `Key<T>` instances which allow one delegate
to store some data under a key in the `Delegates` object, which one that runs subsequently can
retrieve.  Where possible, though, as with any annotation processor, if you are generating files,
it is best to simply read the generated files on a subsequent round - however, if what you need is
to modify an already generated Java source, that is not straightforward to do - in that case, delegates
should collaborate to share information about what was already generated using keys (and handle the
case or fail gracefully when those fail).


Logging
------

Logging from annotation processors is notoriously difficult - for example, Maven completely swallows
some output, and most tools completely ignore `NOTE` level javac logging at all times, leaving annotation
processor authors to either abuse `WARNING` level javac logging to get any information at all to the user.
However, good old `System.out.println` works as expected.

The processor classes defined here support logging via `AnnotationUtils` (the `utils()` method on
processors and delegates returns it) and have instance method that call that.  Simply pass an option
to javac `annoLog=NameOfDelegate1,NameOfDelegate2` or similar, or call the static `AnnotationUtils.forceLogging()`
to enable logging for everything when developing processors.

The predicates generated by the predicate builders use the
[`predicates` library from mastfrog-utils](https://github.com/timboudreau/utils) to produce predicates
which have human-readable `toString()` implementations, so these too can be logged if you need
to know what is happening.


Registration List Generating Processors
-----------------------------------

A common need is to find annotated classes and generate some sort of a registration file which
allows those classes to be found at runtime - the alternative is the slow and error-prone
classpath scanning that some frameworks use.

The classes `IndexGeneratingProcessor`, `AbstractRegistrationAnnotationProcessor` and
`AbstractLineOrientedRegistrationAnnotationProcessor` provide varying levels of support
for writing one or more registration files.


General Guidelines for Writing Annotation Processors
===============================================

Especially with a library like this, writing annotation processors is not hard.  Where things tend
to get tangled up is particularly when an annotation has a method with a value of `Class` or `Class[]` -
you _cannot_ actually load the referenced type in javac's JVM (it will fail badly), nor, for the
sake of compilation speed, should you want to.  Instead, you find the value inside a maze of javac-related
types which may vary, and you need to test and drill through.

`AnnotationUtils` takes care of this, not to mention looking up Java types by name and comparing them
with the values you find.

Things to remember in general:

 * Your annotation file will be run by IDEs _while the user is editing._  **The normal state of a source
file in an IDE is broken.**  In particular, this can manifest as getting an array where you were expecting
a single element, or getting surprising types.  Since you're looking things up by name, this is less of
an issue, but something to be ready for (in the form of aborting code generation with an error).
`AnnotationUtils.annotationValues()` and `AnnotationUtils.annotationValue()` will generate an error for
you and take care of this if you want (or you can use the overloads that take a `Consumer<String>` if you
prefer to deal with it yourself).
 * Validate before you generate.  See the validation section above.  All annotation processor subclasses
here have a method called from `init()` which you can override.  That's where you should create your
validators - a validator is just a very complex `java.util.function.Predicate<AnnotationMirror>`, and
the builders returned by `AnnotationUtils.testsBuilder()` allow you to simply test most any aspect
of an annotation or the thing it is annotating.

