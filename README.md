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

As you can see from the two different styles of declaring annotations, with most things, you can either provide
a `Consumer` for an element, or add it imperatively (sometimes this requires a closeSomething() or endSomething()
call to close the builder, which is implicit in exiting the lambda), so you are free to choose which style you
prefer based on taste or readability.


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
=======

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

