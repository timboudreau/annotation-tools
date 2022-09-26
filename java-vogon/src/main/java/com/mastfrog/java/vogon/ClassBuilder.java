package com.mastfrog.java.vogon;

import com.mastfrog.code.generation.common.general.Statement;
import com.mastfrog.code.generation.common.general.CodeGeneratorBase;
import com.mastfrog.code.generation.common.general.DoubleNewline;
import com.mastfrog.code.generation.common.general.Composite;
import com.mastfrog.code.generation.common.general.Adhoc;
import com.mastfrog.code.generation.common.CodeGenerator;
import com.mastfrog.code.generation.common.LinesBuilder;
import com.mastfrog.code.generation.common.SourceFileBuilder;
import com.mastfrog.code.generation.common.util.Holder;
import static com.mastfrog.code.generation.common.util.Utils.notNull;
import static com.mastfrog.java.vogon.BitwiseOperators.COMPLEMENT;
import com.mastfrog.java.vogon.ClassBuilder.FinishableConditionBuilder;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Modifier;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PROTECTED;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.lang.model.element.Modifier.VOLATILE;

/**
 * Quick'n'dirty java code generation that takes advantage of lambdas.
 *
 * @author Tim Boudreau
 */
public final class ClassBuilder<T> implements CodeGenerator, NamedMember, SourceFileBuilder {

    private final String name;
    private final String pkg;
    private final List<ConstructorBuilder<?>> constructors = new LinkedList<>();
    private EnumConstantBuilder<ClassBuilder<T>> constants;
    private final List<CodeGenerator> members = new LinkedList<>();
    private final Set<String> imports = new TreeSet<>();
    private final Set<Modifier> modifiers = new TreeSet<>();
    private final Function<ClassBuilder<T>, T> converter;
    private String extendsType;
    private final Set<String> implementsTypes = new LinkedHashSet<>();
    private final Set<CodeGenerator> annotations = new LinkedHashSet<>();
    private String docComment;
    private String classType = "class";
    private boolean loggerField;
    private static ThreadLocal<ClassBuilder<?>> CONTEXT = new ThreadLocal<>();
    private ClassBuilder<?> prev;
    private boolean generateDebugCode;
    private final Set<String> typeParams = new LinkedHashSet<>();
    private Consumer<String> importConsumer;
    private ClassBuilder<?> parent;

    @SuppressWarnings("LeakingThisInConstructor")
    ClassBuilder(Object pkg, Object name, Function<ClassBuilder<T>, T> converter) {
        this.pkg = pkg == null ? null : pkg.toString();
        this.name = name.toString();
        checkIdentifier(this.name);
        if (this.pkg != null) {
            for (String component : this.pkg.split("\\.")) {
                checkIdentifier(component);
            }
        }
        this.converter = converter;
        if (pkg != null) {
            prev = CONTEXT.get();
            CONTEXT.set(this);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String fileExtension() {
        return ".java";
    }

    @Override
    public Optional<String> namespace() {
        String pk = packageName();
        if (pk == null || pk.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(pk);
    }

    public ClassBuilder<T> sortMembers() {
        members.sort(NamedMember::compare);
        for (CodeGenerator bb : members) {
            if (bb instanceof ClassBuilder<?>) {
                ((ClassBuilder<?>) bb).sortMembers();
            }
        }
        return this;
    }

    public String unusedFieldName(String field) {
        Set<String> all = new HashSet<>();
        for (CodeGenerator b : members) {
            if (b instanceof FieldBuilder<?>) {
                FieldBuilder<?> fb = (FieldBuilder<?>) b;
                all.add(fb.name);
            }
        }
        while (all.contains(field)) {
            field = "_" + field;
        }
        return field;
    }

    public String unusedMethodName(String field) {
        Set<String> all = new HashSet<>();
        for (CodeGenerator b : members) {
            if (b instanceof MethodBuilder<?>) {
                MethodBuilder<?> mb = (MethodBuilder<?>) b;
                all.add(mb.name);
            }
        }
        while (all.contains(field)) {
            field = "_" + field;
        }
        return field;
    }

    public Path sourceFileRelativePath() {
        String pkg = packageName();
        if (!pkg.isEmpty()) {
            return Paths.get(className() + ".java");
        }
        return Paths.get(pkg.replace('.', File.separatorChar))
                .resolve(className() + ".java");
    }

    ClassBuilder<T> withImportConsumer(Consumer<String> c) {
        this.importConsumer = c;
        return this;
    }

    Consumer<String> importConsumer() {
        if (importConsumer != null) {
            return importConsumer;
        }
        return this::addImport;
    }

    private void addImport(String imp) {
        if (imp.indexOf('<') >= 0) {
            throw new IllegalArgumentException("Imports cannot contain "
                    + "generics.  Attempting to import '" + imp + "'");
        }
        imports.add(imp);
    }

    /**
     * Static-import a variable defined in this type (usually for use in an
     * annotation).
     *
     * @param importName An unqualified name of a field that exist or will be
     * defined for this class
     * @return this
     */
    public ClassBuilder<T> importStaticFromSelf(String importName) {
        String fqn = fqn();
        staticImport(fqn + "." + importName);
        return this;
    }

    /**
     * Static-import a variable defined in this type (usually for use in an
     * annotation, or which reference inner enum types).
     *
     * @param firstImport An unqualified name of a field that exist or will be
     * defined for this class
     * @param next Additional names to import
     * @return this
     */
    public ClassBuilder<T> importStaticFromSelf(String firstImport, String... next) {
        String fqn = fqn();
        staticImport(fqn + "." + firstImport);
        for (String name : next) {
            staticImport(fqn + "." + name);
        }
        return this;
    }

    String methodSource(String name) { // for tests
        for (CodeGenerator bb : members) {
            if (bb instanceof MethodBuilder<?>) {
                MethodBuilder<?> mb = (MethodBuilder<?>) bb;
                if (name.equals(mb.name)) {
                    LinesBuilder lb = new LinesBuilder();
                    mb.generateInto(lb);
                    return lb.toString();
                }
            }
        }
        return "<did not find method '" + name + "'>";
    }

    /**
     * Add an annotation method (no body, default defined at end).
     *
     * @param method The method name
     * @return a builder
     */
    public AnnotationMethodBuilder<ClassBuilder<T>> annotationMethod(String method) {
        return new AnnotationMethodBuilder<>(amb -> {
            members.add(amb);
            return this;
        }, method);
    }

    /**
     * Add type parameters to this type.
     *
     * @param first The first type parameter
     * @param more Additional type parameters
     * @return this
     */
    public ClassBuilder<T> withTypeParameters(String first, String... more) {
        typeParams.add(first);
        typeParams.addAll(Arrays.asList(more));
        return this;
    }

    public ClassBuilder<T> withTypeParameters(Collection<? extends String> all) {
        typeParams.addAll(all);
        return this;
    }

    public ClassBuilder<T> lineComment(String what) {
        return lineComment(what, false);
    }

    public ClassBuilder<T> lineComment(String what, boolean trailing) {
        members.add(new LineComment(what, trailing));
        return this;
    }

    public ClassBuilder<T> logger() {
        if (pkg == null && CONTEXT.get() != null && CONTEXT.get() != this) {
            CONTEXT.get().logger();
        } else {
            loggerField = true;
            importing("java.util.logging.Logger", "java.util.logging.Level");
        }
        return this;
    }

    public ClassBuilder<T> generateDebugLogCode() {
        this.generateDebugCode = true;
        return this;
    }

    public ClassBuilder<T> insertText(String text) {
        members.add(new Adhoc(text));
        return this;
    }

    public String packageName() {
        return pkg;
    }

    public String className() {
        return name;
    }

    /**
     * Get the (unqualified) parameterized type name of the class this builder
     * will create.
     *
     * @param fullSignature If true, include any qualifiers in the returned
     * string - i.e. <code>MyClass&lt;F super Foo, B extends Bar&gt;`, while if false, include
     * only the type parameter names, e.g. <code>MyClass&lt;F, B&gt;</code>
     * @return
     */
    public String parameterizedClassName(boolean fullSignature) {
        if (typeParams.isEmpty()) {
            return className();
        }
        String cn = className();
        StringBuilder sb = new StringBuilder(cn)
                .append('<');
        for (String p : typeParams) {
            if (sb.length() != cn.length() + 1) {
                sb.append(", ");
            }
            String param = fullSignature ? p : implicitTypeParameter(p);
            sb.append(param);
        }
        return sb.append('>').toString();
    }

    private String implicitTypeParameter(String fullParameter) {
        if (fullParameter.contains(" extends ") || (fullParameter.contains(" super "))) {
            String[] parts = fullParameter.split("\\s+");
            return parts[0];
        }
        return fullParameter;
    }

    public String fqn() {
        return pkg + "." + name;
    }

    public ClassBuilder<T> extending(String type) {
        if ("interface".equals(classType)) {
            implementsTypes.add(type);
            return this;
        }
        if (extendsType != null) {
            throw new IllegalStateException("Already extending " + extendsType + " - cannot extend " + type);
        }
        this.extendsType = type;
        return this;
    }

    public ClassBuilder<T> annotatedWith(String anno, Consumer<? super AnnotationBuilder<?>> c) {
        boolean[] built = new boolean[1];
        AnnotationBuilder<?> bldr = annotatedWith(anno, built);
        c.accept(bldr);
        if (!built[0]) {
            bldr.closeAnnotation();
        }
        return this;
    }

    public AnnotationBuilder<ClassBuilder<T>> annotatedWith(String anno) {
        return annotatedWith(anno, new boolean[1]);
    }

    private AnnotationBuilder<ClassBuilder<T>> annotatedWith(String anno, boolean[] built) {
        checkIdentifier(anno);
        return new AnnotationBuilder<>(ab -> {
            annotations.add(ab);
            built[0] = true;
            return ClassBuilder.this;
        }, anno);
    }

    public ClassBuilder<T> implementing(String type, String... more) {
        implementing(type);
        for (String m : more) {
            implementing(m);
        }
        return this;
    }

    public ClassBuilder<T> implementing(String type) {
        implementsTypes.add(checkIdentifier(notNull("type", type)));
        return this;
    }

    public static ClassBuilder<String> create(Object pkg, Object name) {
        return new ClassBuilder<>(pkg, name, new ClassBuilderStringFunction());
    }

    public static PackageBuilder forPackage(Object pkg) {
        return new PackageBuilder(pkg);
    }

    public boolean containsMethodNamed(String name) {
        for (CodeGenerator bb : this.members) {
            if (bb instanceof MethodBuilder) {
                if (name.equals(((MethodBuilder) bb).name)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean containsFieldNamed(String name) {
        for (CodeGenerator bb : this.members) {
            if (bb instanceof FieldBuilder) {
                if (name.equals(((FieldBuilder) bb).name)) {
                    return true;
                }
            }
        }
        return false;
    }

    public ClassBuilder<?> topLevel() {
        ClassBuilder<?> curr = this;
        if (parent == null) {
            return this;
        }
        while (curr.parent != null) {
            curr = curr.parent;
        }
        return curr;
    }

    public ClassBuilder<ClassBuilder<T>> innerClass(String name) {
        ClassBuilder<ClassBuilder<T>> result = new ClassBuilder<ClassBuilder<T>>(null, name, cb -> {
            members.add(cb);
            return ClassBuilder.this;
        }).withImportConsumer(importConsumer());
        result.parent = this;
        return result;
    }

    public ClassBuilder<T> innerClass(String name, Consumer<? super ClassBuilder<?>> c) {
        boolean[] built = new boolean[1];
        ClassBuilder<Void> bldr = new ClassBuilder<Void>(null, name, cb -> {
            members.add(cb);
            built[0] = true;
            return null;
        }).withImportConsumer(importConsumer());
        bldr.parent = this;
        c.accept(bldr);
        if (!built[0]) {
            bldr.build();
        }
        return this;
    }

    public boolean isInterface() {
        return "interface".equals(classType);
    }

    public boolean isEnum() {
        return "enum".equals(classType);
    }

    public boolean isAnnotationType() {
        return "@interface".equals(classType);
    }

    public ClassBuilder<T> makePublic() {
        return withModifier(PUBLIC);
    }

    public ClassBuilder<T> makeStatic() {
        return withModifier(STATIC);
    }

    public ClassBuilder<T> makeFinal() {
        return withModifier(FINAL);
    }

    public ClassBuilder<T> publicStatic() {
        return makePublic().makeStatic();
    }

    public ClassBuilder<T> publicStaticFinal() {
        return publicStatic().makeFinal();
    }

    public ClassBuilder<T> toEnum() {
        classType = "enum";
        return this;
    }

    public ClassBuilder<T> toInterface() {
        classType = "interface";
        return this;
    }

    public ClassBuilder<T> toAnnotationType() {
        classType = "@interface";
        return this;
    }

    public ClassBuilder<T> docComment(Object... cmts) {
        StringBuilder sb = new StringBuilder();
        for (Object o : cmts) {
            sb.append(o);
        }
        return docComment(sb.toString());
    }

    public ClassBuilder<T> docComment(String cmt) {
        if (docComment != null) {
            throw new IllegalStateException("Doc comment already set to '" + docComment + "'");
        }
        this.docComment = cmt;
        return this;
    }

    /**
     * Creates a private constructor which simply throws an assertion error.
     *
     * @return
     */
    public ClassBuilder<T> utilityClassConstructor() {
        if (this.isInterface()) {
            throw new IllegalStateException("Interfaces cannot have constructors");
        }
        return constructor().setModifier(PRIVATE).body()
                .statement("throw new AssertionError()").endBlock();
    }

    public ClassBuilder<T> constructor(Consumer<? super ConstructorBuilder<?>> c) {
        if (this.isInterface()) {
            throw new IllegalStateException("Interfaces cannot have constructors");
        }
        boolean[] built = new boolean[1];
        ConstructorBuilder<?> result = constructor(built);
        c.accept(result);
        if (!built[0]) {
            throw new IllegalStateException("Constructor not closed");
        }
        return this;
    }

    public ConstructorBuilder<ClassBuilder<T>> constructor() {
        return constructor(new boolean[1]);
    }

    private ConstructorBuilder<ClassBuilder<T>> constructor(boolean[] built) {
        if (this.isInterface()) {
            throw new IllegalStateException("Interfaces cannot have constructors");
        }
        return new ConstructorBuilder<>(cb -> {
            if (contains(cb)) {
                throw new IllegalStateException("Already have a constructor with arguments (" + cb.sig() + ")");
            }
            constructors.add(cb);
            built[0] = true;
            return ClassBuilder.this;
        });
    }

    public EnumConstantBuilder<ClassBuilder<T>> enumConstants(String first) {
        if (!isEnum()) {
            toEnum();
        }
        return enumConstants().add(first);
    }

    public EnumConstantBuilder<ClassBuilder<T>> enumConstants() {
        if (constants != null) {
            return constants;
        }
        return new EnumConstantBuilder<>(ecb -> {
            constants = ecb;
            return ClassBuilder.this;
        });
    }

    public ClassBuilder<T> enumConstants(Consumer<EnumConstantBuilder<?>> c) {
        Holder<ClassBuilder<T>> holder = new Holder<>();
        EnumConstantBuilder<ClassBuilder<T>> result = new EnumConstantBuilder<>(ecb -> {
            if (constants != null) {
                constants.constants.addAll(ecb.constants);
            } else {
                constants = ecb;
            }
            holder.set(this);
            return this;
        });
        c.accept(result);
        if (!holder.isSet()) {
            toEnum();
            result.endEnumConstants();
        }
        return holder.get();
    }

    public BlockBuilder<ClassBuilder<T>> staticBlock() {
        return staticBlock(new boolean[1]);
    }

    public ClassBuilder<T> staticBlock(Consumer<? super BlockBuilder<?>> c) {
        boolean[] built = new boolean[1];
        BlockBuilder<ClassBuilder<T>> block = staticBlock(built);
        c.accept(block);
        if (!built[0]) {
            block.endBlock();
        }
        return this;
    }

    private BlockBuilder<ClassBuilder<T>> staticBlock(boolean[] built) {
        if (isInterface()) {
            throw new IllegalStateException(className() + " is an interface.  "
                    + "It cannot have a static block.");
        }
        return new BlockBuilder<>(bb -> {
            members.add(new Composite(new Adhoc("static"), bb, new DoubleNewline()));
            built[0] = true;
            return ClassBuilder.this;
        }, true);
    }

    public BlockBuilder<ClassBuilder<T>> block() {
        if (isInterface()) {
            throw new IllegalStateException(className() + " is an interface.  "
                    + "It cannot have an init block.");
        }
        return block(new boolean[1]);
    }

    public ClassBuilder<T> block(Consumer<? super BlockBuilder<?>> c) {
        boolean[] built = new boolean[1];
        BlockBuilder<?> bldr = block(built);
        c.accept(bldr);
        if (!built[0]) {
            bldr.endBlock();
        }
        return this;
    }

    private BlockBuilder<ClassBuilder<T>> block(boolean[] built) {
        if (isInterface()) {
            throw new IllegalStateException(className() + " is an interface.  "
                    + "It cannot have an init block.");
        }
        return new BlockBuilder<>(bb -> {
            members.add(bb);
            built[0] = true;
            return this;
        }, true);
    }

    public static NewBuilder<String> constructionFragment() {
        return new NewBuilder<>(nb -> {
            LinesBuilder lb = new LinesBuilder();
            nb.generateInto(lb);
            return lb.toString();
        });
    }

    public static InvocationBuilder<String> invocationFragment(String method) {
        return new InvocationBuilder<>(ib -> {
            LinesBuilder lb = new LinesBuilder();
            ib.generateInto(lb);
            return lb.toString();
        }, method);
    }

    public static ArrayValueBuilder<String> arrayFragment() {
        return new ArrayValueBuilder<>(nb -> {
            LinesBuilder lb = new LinesBuilder();
            nb.generateInto(lb);
            return lb.toString();
        });
    }

    public static final class AnnotatedArgumentBuilder<T> implements CodeGenerator {

        private final List<CodeGenerator> annotations = new ArrayList<>();
        private final Function<CodeGenerator, T> converter;

        AnnotatedArgumentBuilder(Function<CodeGenerator, T> converter) {
            this.converter = converter;
        }

        public AnnotatedArgumentBuilder<T> annotatedWith(String type, Consumer<AnnotationBuilder<?>> c) {
            Holder<AnnotatedArgumentBuilder<T>> hold = new Holder<>();
            AnnotationBuilder<Void> bldr = new AnnotationBuilder<>(ab -> {
                annotations.add(ab);
                hold.set(this);
                return null;
            }, type);
            c.accept(bldr);
            return hold.get("Annotated arg builder for " + type + " was not closed");
        }

        public AnnotationBuilder<AnnotatedArgumentBuilder<T>> annotatedWith(String type) {
            return new AnnotationBuilder<>(ab -> {
                annotations.add(ab);
                return this;
            }, type);
        }

        public T ofType(String type) {
            checkIdentifier(type);
//            List<BodyBuilder> all = new ArrayList<>(annotations);
//            all.add(new Adhoc(type));
//            return converter.apply(new Composite(all.toArray(new BodyBuilder[all.size()])));
            return converter.apply(new AnnotationsAndType(type, annotations));
        }

        @Override
        public void generateInto(LinesBuilder lines) {

        }
    }

    static class AnnotationsAndType extends CodeGeneratorBase {

        private final String type;
        private final List<CodeGenerator> all;

        AnnotationsAndType(String type, List<CodeGenerator> all) {
            this.type = type;
            this.all = new ArrayList<>(all);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.hangingWrap(lb -> {
                for (CodeGenerator bb : all) {
                    bb.generateInto(lb);
                }
                lb.word(type);
            });
        }
    }

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

    public static final class EnumConstantBuilder<T> extends CodeGeneratorBase {

        private final Function<EnumConstantBuilder<T>, T> converter;
        private final Set<CodeGenerator> constants = new LinkedHashSet<>();
        private String docComment;

        EnumConstantBuilder(Function<EnumConstantBuilder<T>, T> converter) {
            this.converter = converter;
        }

        public EnumConstantBuilder<T> add(String name) {
            constants.add(new Adhoc(checkIdentifier(name)));
            return this;
        }

        public EnumConstantBuilder<T> docComment(String cmt) {
            if (this.docComment != null) {
                this.docComment += "\n" + cmt;
            } else {
                this.docComment = cmt;
            }
            return this;
        }

        public EnumConstantBuilder<T> addWithArgs(String name, Consumer<InvocationBuilder<?>> c) {
            Holder<EnumConstantBuilder<T>> h = new Holder<>();
            InvocationBuilder<Void> iv = new InvocationBuilder<>(ib -> {
                h.set(this);
                constants.add(ib);
                return null;
            }, name);
            c.accept(iv);
            if (!h.isSet()) {
                iv.inScope();
            }
            return h.get();
        }

        public InvocationBuilder<EnumConstantBuilder<T>> addWithArgs(String name) {
            return new InvocationBuilder<>(ib -> {
                constants.add(ib);
                return EnumConstantBuilder.this;
            }, name);
        }

        public T endEnumConstants() {
            return converter.apply(this);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            if (docComment != null) {
                writeDocComment(docComment, lines);
            }
            Iterator<CodeGenerator> it = constants.iterator();
            while (it.hasNext()) {
                CodeGenerator bb = it.next();
                bb.generateInto(lines);
                if (it.hasNext()) {
                    lines.appendRaw(",");
                }
                if (!(bb instanceof EnumConstantBuilder<?>)) {
                    lines.onNewLine();
                }
            }
            lines.appendRaw(";");
        }
    }

    public static final class ConstructorBuilder<T> extends CodeGeneratorBase {

        private final Function<ConstructorBuilder<T>, T> converter;
        private BlockBuilder<?> body;
        private final Set<AnnotationBuilder<?>> annotations = new LinkedHashSet<>();
        private final Map<CodeGenerator, CodeGenerator> arguments = new LinkedHashMap<>();
        private final Set<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
        private final Set<String> throwing = new TreeSet<>();
        private StringBuilder docComment;

        ConstructorBuilder(Function<ConstructorBuilder<T>, T> converter) {
            this.converter = converter;
        }

        public ConstructorBuilder<T> docComment(String txt) {
            if (docComment == null) {
                docComment = new StringBuilder(txt);
            } else {
                docComment.append('\n').append(txt);
            }
            return this;
        }

        public T emptyBody() {
            return body().lineComment("do nothing").endBlock();
        }

        public ConstructorBuilder<T> throwing(String thrown) {
            throwing.add(thrown);
            return this;
        }

        public AnnotationBuilder<ConstructorBuilder<T>> annotatedWith(String what) {
            return new AnnotationBuilder<>(ab -> {
                annotations.add(ab);
                return this;
            }, what);
        }

        public ConstructorBuilder<T> annotatedWith(String what, Consumer<? super AnnotationBuilder<?>> c) {
            boolean[] built = new boolean[1];
            AnnotationBuilder<Void> bldr = new AnnotationBuilder<>(ab -> {
                annotations.add(ab);
                built[0] = true;
                return null;
            }, what);
            c.accept(bldr);
            if (!built[0]) {
                bldr.closeAnnotation();
            }
            return this;
        }

        public ConstructorBuilder<T> addArgument(String type, String name) {
            arguments.put(new Adhoc(checkIdentifier(notNull("name", name))), parseTypeName(type));
            return this;
        }

        public AnnotationBuilder<AnnotatedArgumentBuilder<ParameterNameBuilder<ConstructorBuilder<T>>>> addAnnotatedArgument(String annotationType) {
            return new AnnotatedArgumentBuilder<>(
                    annotationsAndType -> {
                        return new ParameterNameBuilder<>(name -> {
                            arguments.put(new Adhoc(name), annotationsAndType);
                            return this;
                        });
                    }).annotatedWith(annotationType);
        }

        public ConstructorBuilder<T> addAnnotatedArgument(String annotationType, Consumer<AnnotationBuilder<AnnotatedArgumentBuilder<ParameterNameBuilder<Void>>>> c) {
            Holder<ConstructorBuilder<T>> hold = new Holder<>();
            AnnotationBuilder<AnnotatedArgumentBuilder<ParameterNameBuilder<Void>>> bldr
                    = new AnnotatedArgumentBuilder<ParameterNameBuilder<Void>>(
                            annotationsAndType -> {
                                return new ParameterNameBuilder<>(name -> {
                                    arguments.put(new Adhoc(name), annotationsAndType);
                                    hold.set(this);
                                    return null;
                                });
                            }).annotatedWith(annotationType);
            c.accept(bldr);
            return hold.get("Annotated argument builder not completed");
        }

        public AnnotationBuilder<MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<ConstructorBuilder<T>>>>>
                addMultiAnnotatedArgument(String annotationType) {
            return new MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<ConstructorBuilder<T>>>>(bldr -> {
                return new ParameterNameBuilder<>(name -> {
                    return new TypeNameBuilder<>(typeName -> {
                        arguments.put(new Adhoc(name), bldr.appendingType(typeName.type));
                        return this;
                    });
                });
            }).annotatedWith(annotationType);
        }

        public MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<ConstructorBuilder<T>>>>
                addMultiAnnotatedArgument() {
            return new MultiAnnotatedArgumentBuilder<>(bldr -> {
                return new ParameterNameBuilder<>(name -> {
                    return new TypeNameBuilder<>(typeName -> {
                        arguments.put(new Adhoc(name), bldr.appendingType(typeName.type));
                        return this;
                    });
                });
            });
        }

        public ConstructorBuilder<T> addMultiAnnotatedArgument(String annotationType,
                Consumer<AnnotationBuilder<MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<Void>>>>> c) {
            Holder<MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<Void>>>> hold
                    = new Holder<>();
            MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<Void>>> result
                    = new MultiAnnotatedArgumentBuilder<>(bldr -> {
                        return new ParameterNameBuilder<>(name -> {
                            return new TypeNameBuilder<>(type -> {
                                arguments.put(new Adhoc(name), bldr.appendingType(type.type));
                                hold.set(bldr);
                                return null;
                            });

                        });
                    });
            c.accept(result.annotatedWith(annotationType));
            hold.ifUnset(result::closeAnnotations);
            return this;
        }

        public T body(Consumer<? super BlockBuilder<?>> c) {
            Holder<T> hold = new Holder<>();
            BlockBuilder<Void> block = new BlockBuilder<>(bb -> {
                body = bb;
                hold.set(build());
                return null;
            }, true);
            c.accept(block);
            if (!hold.isSet()) {
                body = block;
                hold.set(build());
            }
            return hold.get();
        }

        public BlockBuilder<T> body() {
            return body(new boolean[1]);
        }

        private T build() {
            if (this.built != null) {
                throw new IllegalStateException("Adding constructor twice", this.built);
            }
            this.built = new Exception("end body block");
            return converter.apply(this);
        }

        private Exception built;

        private BlockBuilder<T> body(boolean[] built) {
            return new BlockBuilder<>(bb -> {
                body = bb;
                built[0] = true;
                return build();
            }, true);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.parens(lb -> {
                for (Iterator<Map.Entry<CodeGenerator, CodeGenerator>> it = arguments.entrySet().iterator(); it.hasNext();) {
                    Map.Entry<CodeGenerator, CodeGenerator> e = it.next();
                    e.getValue().generateInto(lb);
                    e.getKey().generateInto(lb);
                    if (it.hasNext()) {
                        lb.appendRaw(",");
                    }
                }
            });
            if (!throwing.isEmpty()) {
                lines.word("throws", true);
                for (Iterator<String> it = throwing.iterator(); it.hasNext();) {
                    String th = it.next();
                    lines.word(th, true);
                    if (it.hasNext()) {
                        lines.appendRaw(",");
                    }
                }
            }
            if (body != null) {
                body.generateInto(lines);
            } else {
                lines.appendRaw(";");
            }
        }

        public ConstructorBuilder<T> setModifier(Modifier mod) {
            _addModifier(mod);
            return this;
        }

        private ConstructorBuilder<T> _addModifier(Modifier mod) {
            switch (mod) {
                case PUBLIC:
                case PRIVATE:
                case PROTECTED:
                    if (!modifiers.isEmpty() && !modifiers.contains(mod)) {
                        throw new IllegalArgumentException("Contradictory "
                                + "modifier in constructor: "
                                + modifiers.iterator().next() + " and " + mod);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Inappropriate "
                            + "modifier for constructor: " + mod);
            }
            modifiers.add(mod);
            return this;
        }

        public void buildInto(LinesBuilder lb, String name) {
            lb.onNewLine();
            if (docComment != null) {
                writeDocComment(docComment.toString(), lb);
                lb.backup().onNewLine();
            }
            for (AnnotationBuilder<?> ab : annotations) {
                ab.generateInto(lb);
                lb.onNewLine();
            }
            for (Modifier m : modifiers) {
                lb.word(m.toString());
            }
            lb.word(name);
            generateInto(lb);
        }

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 37 * hash + Objects.hashCode(this.arguments);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final ConstructorBuilder<?> other = (ConstructorBuilder<?>) obj;
            return Objects.equals(this.arguments, other.arguments);
        }

        private String sig() {
            LinesBuilder lb = new LinesBuilder();
            for (Iterator<Map.Entry<CodeGenerator, CodeGenerator>> it = arguments.entrySet().iterator(); it.hasNext();) {
                Map.Entry<CodeGenerator, CodeGenerator> e = it.next();
                e.getValue().generateInto(lb);
                e.getKey().generateInto(lb);
                if (it.hasNext()) {
                    lb.appendRaw(',');
                }
            }
            return lb.toString();
        }
    }

    public static final class TypeNameBuilder<T> {

        private final Function<TypeNameBuilder<T>, T> converter;
        CodeGenerator type;

        TypeNameBuilder(Function<TypeNameBuilder<T>, T> converter) {
            this.converter = converter;
        }

        public T ofType(String type) {
            this.type = parseTypeName(checkIdentifier(notNull("type", type)));
            return converter.apply(this);
        }
    }

    private static final class Multi implements CodeGenerator {

        private final List<CodeGenerator> items = new ArrayList<>();

        Multi(List<? extends CodeGenerator> all) {
            this.items.addAll(all);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
//            lines.hangingWrap(lb -> {
            for (CodeGenerator bb : items) {
                bb.generateInto(lines);
                lines.appendRaw(' ');
            }
//            });
        }
    }

    public static final class MultiAnnotatedArgumentBuilder<T> {

        private final Function<MultiAnnotatedArgumentBuilder<T>, T> converter;
        private final List<AnnotationBuilder<?>> annotations = new ArrayList<>();

        public MultiAnnotatedArgumentBuilder(Function<MultiAnnotatedArgumentBuilder<T>, T> converter) {
            this.converter = converter;
        }

        CodeGenerator appendingType(CodeGenerator type) {
            List<CodeGenerator> nue = new ArrayList<>(annotations);
            nue.add(type);
            return new Multi(nue);
        }

        public AnnotationBuilder<MultiAnnotatedArgumentBuilder<T>> annotatedWith(String annotationType) {
            return new AnnotationBuilder<>(
                    annotationsAndType -> {
                        annotations.add(annotationsAndType);
                        return this;
                    }, annotationType);
        }

        public MultiAnnotatedArgumentBuilder<T> annotatedWith(String annotationType, Consumer<AnnotationBuilder<?>> c) {
            Holder<MultiAnnotatedArgumentBuilder<T>> holder = new Holder<>();
            AnnotationBuilder<Void> anno = new AnnotationBuilder<>(
                    annotationsAndType -> {
                        annotations.add(annotationsAndType);
                        holder.set(this);
                        return null;
                    }, annotationType);

            holder.ifUnset(anno::closeAnnotation);
            c.accept(anno);
            return holder.get("Annotation builder was not completed");
        }

        public T closeAnnotations() {
            return converter.apply(this);
        }

    }

    public static final class PackageBuilder {

        private final Object pkg;

        PackageBuilder(Object pkg) {
            this.pkg = pkg;
        }

        public ClassBuilder<String> named(String name) {
            return ClassBuilder.create(pkg, name);
        }
    }

    public T build() {
        T result = converter.apply(this);
        if (CONTEXT.get() == this) {
            CONTEXT.set(prev);
        }
        return result;
    }

    public ClassBuilder<T> importing(Iterable<? extends String> types) {
        for (String type : types) {
            importing(type);
        }
        return this;
    }

    public ClassBuilder<T> importing(Class<?> className, Class<?>... more) {
        Consumer<String> ic = importConsumer();
        ic.accept(className.getName());
        for (Class<?> c : more) {
            ic.accept(c.getName());
        }
        return this;
    }

    public ClassBuilder<T> importing(String className, String... more) {
        Consumer<String> ic = importConsumer();
        ic.accept(className);
        for (String s : more) {
            ic.accept(s);
        }
        return this;
    }

    public ClassBuilder<T> staticImport(String... more) {
        Consumer<String> ic = importConsumer();
        for (String s : more) {
            ic.accept("static " + s);
        }
        return this;
    }

    public ClassBuilder<T> withModifier(Modifier m) {
        _addModifier(m);
        return this;
    }

    public ClassBuilder<T> withModifier(Modifier m, Modifier... more) {
        _addModifier(m);
        for (Modifier mm : more) {
            _addModifier(mm);
        }
        return this;
    }

    private ClassBuilder<T> _addModifier(Modifier m) {
        switch (m) {
            case DEFAULT:
            case NATIVE:
            case STRICTFP:
            case SYNCHRONIZED:
            case TRANSIENT:
            case VOLATILE:
                throw new IllegalArgumentException(m + "");
            case PRIVATE:
                if (modifiers.contains(PROTECTED) || modifiers.contains(PUBLIC)) {
                    throw new IllegalStateException("Cannot be private and also protected or public");
                }
                break;
            case PROTECTED:
                if (modifiers.contains(PRIVATE) || modifiers.contains(PUBLIC)) {
                    throw new IllegalStateException("Cannot be private and also protected or public");
                }
                break;
            case PUBLIC:
                if (modifiers.contains(PRIVATE) || modifiers.contains(PROTECTED)) {
                    throw new IllegalStateException("Cannot be private and also protected or public");
                }
                break;
            case STATIC:
                if (parent == null) {
//                if (converter.getClass() == ClassBuilderStringFunction.class) {
                    throw new IllegalStateException(name + " is a top-level class, and may not be declared static.");
                }
        }
        modifiers.add(m);
        return this;
    }

    private void writeDocComment(LinesBuilder lb) {
        writeDocComment(docComment, lb);
        lb.onNewLine();
    }

    @Override
    public void generateInto(LinesBuilder lines) {
        lines.onNewLine();
        if (constants != null && !"enum".equals(classType)) {
            throw new IllegalStateException(name + " is a " + classType
                    + " but has enum constants");
        }
        if (pkg != null) {
            lines.statement(sb -> {
                sb.word("package").word(pkg);
            });
        }
        lines.doubleNewline();
        if (!imports.isEmpty()) {
            for (String imp : imports) {
                lines.statement("import " + imp);
            }
            lines.doubleNewline();
        }
        writeDocComment(lines);
        if (!annotations.isEmpty()) {
            for (CodeGenerator anno : annotations) {
                lines.onNewLine();
                anno.generateInto(lines);
            }
            lines.onNewLine();
        }
        for (Modifier m : modifiers) {
            lines.word(m.toString());
        }
        lines.word(classType);
        lines.word(name);
        if (typeParams.size() > 0) {
            lines.appendRaw("<");
            for (Iterator<String> it = typeParams.iterator(); it.hasNext();) {
                String param = it.next();
                if (param.indexOf(' ') > 0) {
                    param = param.trim();
                    String[] parts = param.split("\\s+");
                    for (String p : parts) {
                        lines.word(p);
                    }
                } else {
                    lines.word(param);
                }
                if (it.hasNext()) {
                    lines.appendRaw(", ");
                }
            }
            lines.appendRaw('>');
        }
        if (extendsType != null) {
            lines.word("extends");
            lines.word(extendsType);
        }
        if (!implementsTypes.isEmpty()) {
            if (isInterface()) {
                if (extendsType != null) {
                    lines.appendRaw(",");
                } else {
                    lines.word("extends");
                }
            } else {
                lines.word("implements");
            }
            for (Iterator<String> it = implementsTypes.iterator(); it.hasNext();) {
                String type = it.next();
                boolean last = !it.hasNext();
                if (!last) {
                    lines.word(type + ",");
                } else {
                    lines.word(type);
                }
            }
        }
        lines.block(true, (lb) -> {
            if (constants != null) {
                constants.generateInto(lines);
            }
            if (loggerField && converter.getClass() == ClassBuilderStringFunction.class) {
                FieldBuilder<Void> fb = new FieldBuilder<>(f -> {
                    return null;
                }, "LOGGER");
                fb.withModifier(PRIVATE).withModifier(STATIC).withModifier(FINAL)
                        .initializedTo("Logger.getLogger(" + LinesBuilder.stringLiteral(fqn()) + ")")
                        .ofType("Logger");
                fb.generateInto(lines);
                if (generateDebugCode) {
                    lines.onNewLine();
                    lines.word("static");
                    lines.block(lg -> {
                        lg.statement(sb -> {
                            sb.word("LOGGER").appendRaw(".").word("setLevel(").word("Level").appendRaw(".").word("ALL").appendRaw(")");
                        });
                    });
                }
            }
            boolean foundFields = false;
            for (CodeGenerator bb : this.members) {
                List<FieldBuilder<?>> staticFields = new ArrayList<>();
                List<FieldBuilder<?>> instanceFields = new ArrayList<>();

                if (bb instanceof FieldBuilder<?>) {
                    FieldBuilder<?> fb = (FieldBuilder<?>) bb;
                    if (fb.isStatic()) {
                        staticFields.add(fb);
                    } else {
                        instanceFields.add(fb);
                    }
                }
                if (!staticFields.isEmpty()) {
                    for (FieldBuilder<?> st : staticFields) {
                        st.generateInto(lb);
                    }
                    lb.doubleNewline();
                }
                for (FieldBuilder<?> i : instanceFields) {
                    i.generateInto(lb);
                }
                foundFields = !staticFields.isEmpty() || !instanceFields.isEmpty();
            }
            if (foundFields) {
                lb.doubleNewline();
            }
            boolean foundConstructors = !this.constructors.isEmpty();
            for (ConstructorBuilder<?> c : this.constructors) {
                lb.doubleNewline();
                c.buildInto(lb, name);
            }
            if (foundConstructors) {
                lb.doubleNewline();
            }
            for (CodeGenerator bb : this.members) {
                if (!(bb instanceof FieldBuilder<?>)) {
                    bb.generateInto(lines);
                }
            }
        });
    }

    public String text() {
        LinesBuilder lb = new LinesBuilder();
        generateInto(lb);
        return lb.toString();
    }

    @Override
    public String toString() {
        return text();
    }

    public FieldBuilder<ClassBuilder<T>> field(String name) {
        return field(name, new boolean[1]);
    }

    public FieldBuilder<ClassBuilder<T>> privateFinalField(String name) {
        return field(name, new boolean[1]).withModifier(FINAL, PRIVATE);
    }

    public FieldBuilder<ClassBuilder<T>> finalField(String name) {
        return field(name, new boolean[1]).withModifier(FINAL);
    }

    public ClassBuilder<T> field(String name, Consumer<? super FieldBuilder<?>> c) {
        boolean[] built = new boolean[1];
        FieldBuilder<?> fb = field(name, built);
        c.accept(fb);
        if (!built[0]) {
            throw new IllegalStateException("Field builder not completed - call ofType()");
        }
        return this;
    }

    private FieldBuilder<ClassBuilder<T>> field(String name, boolean[] built) {
        return new FieldBuilder<>(fb -> {
            if (contains(fb)) {
                throw new IllegalStateException("Already have a field " + fb.name + " in " + this.fields());
            }
            members.add(fb);
            built[0] = true;
            return ClassBuilder.this;
        }, name);
    }

    public ClassBuilder<T> method(String name, Consumer<? super MethodBuilder<?>> c) {
        boolean[] built = new boolean[1];
        addDebugStackTraceElementComment();
        MethodBuilder<ClassBuilder<T>> mb = method(name, built);
        c.accept(mb);
        if (!built[0]) {
            mb.closeMethod();
        }
        return this;
    }

    public ClassBuilder<T> protectedMethod(String name, Consumer<? super MethodBuilder<?>> c) {
        return method(name, mb -> {
            c.accept(mb.withModifier(PROTECTED));
        });
    }

    public ClassBuilder<T> publicMethod(String name, Consumer<? super MethodBuilder<?>> c) {
        return method(name, mb -> {
            c.accept(mb.withModifier(PUBLIC));
        });
    }

    public ClassBuilder<T> privateMethod(String name, Consumer<? super MethodBuilder<?>> c) {
        return method(name, mb -> {
            c.accept(mb.withModifier(PRIVATE));
        });
    }

    public MethodBuilder<ClassBuilder<T>> protectedMethod(String name) {
        return method(name, new boolean[1]).withModifier(PROTECTED);
    }

    public MethodBuilder<ClassBuilder<T>> privateMethod(String name) {
        return method(name, new boolean[1]).withModifier(PRIVATE);
    }

    public MethodBuilder<ClassBuilder<T>> method(String name) {
        return method(name, new boolean[1]);
    }

    public MethodBuilder<ClassBuilder<T>> publicMethod(String name) {
        return method(name, new boolean[1]).withModifier(PUBLIC);
    }

    public ClassBuilder<T> override(String name, Consumer<? super MethodBuilder<?>> c) {
        return override(name, c, new Modifier[0]);
    }

    public ClassBuilder<T> overridePublic(String name, Consumer<? super MethodBuilder<?>> c) {
        return override(name, c, new Modifier[]{PUBLIC});
    }

    public ClassBuilder<T> overrideProtected(String name, Consumer<? super MethodBuilder<?>> c) {
        return override(name, c, new Modifier[]{PROTECTED});
    }

    private ClassBuilder<T> override(String name, Consumer<? super MethodBuilder<?>> c, Modifier[] modifiers) {
        boolean[] built = new boolean[1];
        MethodBuilder<?> m = method(name, built, modifiers);
        c.accept(m);
        m.override();
        if (!built[0]) {
            m.closeMethod();
        }
        return this;
    }

    public MethodBuilder<ClassBuilder<T>> override(String name) {
        return method(name, new boolean[1]).override();
    }

    public MethodBuilder<ClassBuilder<T>> overridePublic(String name) {
        return method(name).withModifier(PUBLIC).annotatedWith("Override").closeAnnotation();
    }

    public MethodBuilder<ClassBuilder<T>> overrideProtected(String name) {
        return method(name).withModifier(PROTECTED).annotatedWith("Override").closeAnnotation();
    }

    private boolean contains(CodeGenerator bb) {
        // Usualy, repeated add bugs will be on the most recent element
        // so faster toExpression start from the last
        int max = members.size();
        for (int i = max - 1; i >= 0; i--) {
            CodeGenerator b = members.get(i);
            if (b == bb || b.equals(bb)) {
                return true;
            }
        }
        return false;
    }

    private MethodBuilder<ClassBuilder<T>> method(String name, boolean[] built, Modifier... modifiers) {
        Exception[] prev = new Exception[1];
        addDebugStackTraceElementComment();
        MethodBuilder<ClassBuilder<T>> result = new MethodBuilder<>(mb -> {
            if (prev[0] != null) {
                throw new IllegalStateException("Method built twice", prev[0]);
            }
            prev[0] = new Exception("Build '" + name + "'");

            if (contains(mb)) {
                throw new IllegalStateException("A method named " + mb.name + "(" + mb.sig() + ") already added to " + name);
            }
            members.add(mb);
            built[0] = true;
            return ClassBuilder.this;
        }, name);
        for (Modifier m : modifiers) {
            result.withModifier(m);
        }
        return result;
    }

    /**
     * Perform some action (perhaps adding fields or methods) iteratively for
     * each item in the passed iterable).
     *
     * @param <R> The item type
     * @param items The collection of items
     * @param c A consumer
     * @return this
     */
    public <R> ClassBuilder<T> iteratively(Iterable<R> items, BiConsumer<? super ClassBuilder<?>, ? super R> c) {
        notNull("c", c);
        if (items != null) {
            for (R item : items) {
                c.accept(this, item);
            }
        }
        return this;
    }

    /**
     * Perform some operation on this class builder conditionally, if the passed
     * value is true. Also provides a way to get a reference to the current
     * clcass builder in a chained set of method calls.
     *
     * @param test The test
     * @param c A consumer that is passed this class builder
     * @return A class builder
     */
    public ClassBuilder<T> conditionally(boolean test, Consumer<? super ClassBuilder<?>> c) {
        notNull("c", c);
        if (test) {
            c.accept(this);
        }
        return this;
    }

    /**
     * A builder for a usage of a field.
     *
     * @param <T> The return type when built
     */
    public static final class FieldReferenceBuilder<T> extends CodeGeneratorBase {

        private final String name;
        private CodeGenerator referent;
        private final Function<FieldReferenceBuilder<T>, T> converter;
        private final List<CodeGenerator> arrayElements = new ArrayList<>();

        FieldReferenceBuilder(String name, Function<FieldReferenceBuilder<T>, T> converter) {
            this.name = name;
            this.converter = converter;
        }

        T setFrom(FieldReferenceBuilder<?> other) {
            referent = other.referent;
            arrayElements.clear();
            arrayElements.addAll(other.arrayElements);
            return converter.apply(this);
        }

        private T setReferent(CodeGenerator bb) {
            this.referent = bb;
            return converter.apply(this);
        }

        @Override
        public String toString() {
            return referent + "." + name;
        }

        public T of(String what) {
            return setReferent(new Adhoc(what));
        }

        public T ofThis() {
            return of("this");
        }

        public FieldReferenceBuilder<T> ofField(String name) {
            return new FieldReferenceBuilder<>(name, frb -> {
                return setReferent(frb);
            });
        }

        public InvocationBuilder<T> ofInvocationOf(String name) {
            return new InvocationBuilder<>(this::setReferent, name);
        }

        public T ofInvocationOf(String name, Consumer<InvocationBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            InvocationBuilder<Void> result = new InvocationBuilder<>(ib -> {
                h.set(setReferent(ib));
                return null;
            }, name);
            c.accept(result);
            if (!h.isSet()) {
                result.inScope();
            }
            return h.get("Invocation not completed");
        }

        public NewBuilder<T> ofNew() {
            return new NewBuilder<>(this::setReferent);
        }

        public T ofNew(Consumer<NewBuilder> b) {
            Holder<T> h = new Holder<>();
            NewBuilder<Void> result = new NewBuilder<>(nb -> {
                h.set(setReferent(nb));
                return null;
            });
            b.accept(result);
            return h.get("NewBuilder not completed");
        }

        public FieldReferenceBuilder<T> arrayElement(int index) {
            arrayElements.add(friendlyNumber(index));
            return this;
        }

        public FieldReferenceBuilder<T> arrayElement(Value index) {
            arrayElements.add(index);
            return this;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            referent.generateInto(lines);
            lines.backup().appendRaw('.').backup();
            lines.appendRaw(name);
            for (CodeGenerator index : arrayElements) {
                lines.backup().appendRaw("[" + index + "]");
            }
        }

    }

    /**
     * Builder for annotation methods - similar to the regular method builder,
     * without bodies.
     *
     * @param <T>
     */
    public static final class AnnotationMethodBuilder<T> extends CodeGeneratorBase {

        private final Function<? super AnnotationMethodBuilder<T>, T> converter;
        private final String name;
        private CodeGenerator defaultValue;
        private CodeGenerator type;

        AnnotationMethodBuilder(Function<? super AnnotationMethodBuilder<T>, T> converter, String name) {
            this.converter = converter;
            this.name = checkIdentifier(notNull("name", name));
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.onNewLine();
            type.generateInto(lines);
            lines.word(name);
            lines.appendRaw("()");
            if (defaultValue != null) {
                lines.word("default");
                defaultValue.generateInto(lines);
            }
            lines.statementTerminator();
        }

        public T ofType(String type) {
            this.type = parseTypeName(checkIdentifier(notNull("type", type)));
            return converter.apply(this);
        }

        private T setType(String type) {
            return setType(type, false);
        }

        private T setType(String type, boolean array) {
            return ofType(array ? type + "[]" : type);
        }

        public T withDefault(boolean defaultValue) {
            this.defaultValue = new Adhoc(Boolean.toString(defaultValue));
            return setType("boolean");
        }

        public T withDefault(String defaultValue) {
            this.defaultValue = new Adhoc(LinesBuilder.stringLiteral(defaultValue));
            return setType("String");
        }

        public T withDefault(char defaultValue) {
            this.defaultValue = new Adhoc(LinesBuilder.escapeCharLiteral(defaultValue));
            return setType("char");
        }

        public T withDefault(String name, int[] defaultValue) {
            ArrayLiteralBuilder<Void> bldr = new ArrayLiteralBuilder<>(alb -> {
                this.defaultValue = alb;
                return null;
            }, "int");
            for (int i = 0; i < defaultValue.length; i++) {
                bldr.literal(defaultValue[i]);
            }
            bldr.closeArrayLiteral();
            return setType("int", true);
        }

        public T withDefault(String name, byte[] defaultValue) {
            ArrayLiteralBuilder<Void> bldr = new ArrayLiteralBuilder<>(alb -> {
                this.defaultValue = alb;
                return null;
            }, "int");
            for (int i = 0; i < defaultValue.length; i++) {
                bldr.literal(defaultValue[i]);
            }
            bldr.closeArrayLiteral();
            return setType("byte", true);
        }

        public T withDefault(String name, long[] defaultValue) {
            ArrayLiteralBuilder<Void> bldr = new ArrayLiteralBuilder<>(alb -> {
                this.defaultValue = alb;
                return null;
            }, "int");
            for (int i = 0; i < defaultValue.length; i++) {
                bldr.literal(defaultValue[i]);
            }
            bldr.closeArrayLiteral();
            return setType("byte", true);
        }

        public T withDefault(String name, short[] defaultValue) {
            ArrayLiteralBuilder<Void> bldr = new ArrayLiteralBuilder<>(alb -> {
                this.defaultValue = alb;
                return null;
            }, "int");
            for (int i = 0; i < defaultValue.length; i++) {
                bldr.literal(defaultValue[i]);
            }
            bldr.closeArrayLiteral();
            return setType("short", true);
        }

        public T withDefault(String name, double[] defaultValue) {
            ArrayLiteralBuilder<Void> bldr = new ArrayLiteralBuilder<>(alb -> {
                this.defaultValue = alb;
                return null;
            }, "int");
            for (int i = 0; i < defaultValue.length; i++) {
                bldr.literal(defaultValue[i]);
            }
            bldr.closeArrayLiteral();
            return setType("double", true);
        }

        public T withDefault(String name, float[] defaultValue) {
            ArrayLiteralBuilder<Void> bldr = new ArrayLiteralBuilder<>(alb -> {
                this.defaultValue = alb;
                return null;
            }, "int");
            for (int i = 0; i < defaultValue.length; i++) {
                bldr.literal(defaultValue[i]);
            }
            bldr.closeArrayLiteral();
            return setType("float", true);
        }

        public T withDefault(String name, boolean[] defaultValue) {
            ArrayLiteralBuilder<Void> bldr = new ArrayLiteralBuilder<>(alb -> {
                this.defaultValue = alb;
                return null;
            }, "int");
            for (int i = 0; i < defaultValue.length; i++) {
                bldr.literal(defaultValue[i]);
            }
            bldr.closeArrayLiteral();
            return setType("boolean", true);
        }

        public T withDefault(String name, String[] defaultValue) {
            ArrayLiteralBuilder<Void> bldr = new ArrayLiteralBuilder<>(alb -> {
                this.defaultValue = alb;
                return null;
            }, "int");
            for (int i = 0; i < defaultValue.length; i++) {
                bldr.literal(defaultValue[i]);
            }
            bldr.closeArrayLiteral();
            return setType("String", true);
        }

        public T withDefault(Number defaultValue) {
            this.defaultValue = friendlyNumber(defaultValue);
            return setType(defaultValue.getClass().getName().toLowerCase(), false);
        }

        public T ofInt() {
            return setType("int");
        }

        public T ofByte() {
            return setType("byte");
        }

        public T ofShort() {
            return setType("short");
        }

        public T ofLong() {
            return setType("long");
        }

        public T ofDouble() {
            return setType("double");
        }

        public T ofFloat() {
            return setType("float");
        }

        public T ofChar() {
            return setType("char");
        }

        public T ofBoolean() {
            return setType("boolean");
        }

        public T ofString() {
            return setType("String");
        }

        public T ofLongArray() {
            return setType("long", true);
        }

        public T ofDoubleArray() {
            return setType("double", true);
        }

        public T ofFloatArray() {
            return setType("float", true);
        }

        public T ofCharArray() {
            return setType("char", true);
        }

        public T ofBooleanArray() {
            return setType("boolean", true);
        }

        public T ofStringArray() {
            return setType("String", true);
        }

        public T ofByteArray() {
            return setType("byte", true);
        }

        public T ofIntArray() {
            return setType("int", true);
        }

        public T ofAnnotation(String annotationType) {
            return setType(annotationType);
        }

        public T ofAnnotationArray(String annotationType) {
            return setType(annotationType, true);
        }

        public ArrayLiteralBuilder<T> ofAnnotationArrayWithDefault(String annotationType) {
            return new ArrayLiteralBuilder<>(ab -> {
                this.defaultValue = ab;
                return setType(annotationType, true);
            }, checkIdentifier(notNull("annotationType", annotationType)));
        }

        public AnnotationBuilder<T> ofAnnotationWithDefault(String annotationType) {
            return new AnnotationBuilder<>(ab -> {
                this.defaultValue = ab;
                return setType(annotationType);
            }, checkIdentifier(notNull("annotationType", annotationType)));
        }

        public T withAnnotationArgument(String annotationType, Consumer<AnnotationBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            AnnotationBuilder<Void> result = new AnnotationBuilder<>(ab -> {
                this.defaultValue = ab;
                h.set(setType(annotationType));
                return null;
            }, annotationType);
            c.accept(result);
            return h.get("Annotation builder not completed");
        }
    }

    private static String leadingToken(String typeParam) {
        String[] result = typeParam.split("\\s+");
        return result[0];
    }

    /**
     * Builder for a method - conclude it by calling its body() method, and
     * building that.
     *
     * @param <T> The type it builds
     */
    public static final class MethodBuilder<T> extends CodeGeneratorBase implements NamedMember {

        private final Function<MethodBuilder<T>, T> converter;
        private final Set<Modifier> modifiers = new TreeSet<>();
        private final Set<String> typeParams = new LinkedHashSet<>();
        private final Set<CodeGenerator> annotations = new LinkedHashSet<>();
        private final Set<CodeGenerator> throwing = new LinkedHashSet<>();
        private BlockBuilderBase block;
        private String type = "void";
        private final String name;
        private String docComment;

        MethodBuilder(Function<MethodBuilder<T>, T> converter, String name, Modifier... modifiers) {
            this.converter = converter;
            this.name = name;
            for (Modifier m : modifiers) {
                withModifier(m);
            }
        }

        @Override
        public String name() {
            return name;
        }

        /**
         * Run some code that alters this method, conditionally if the passed
         * condition is true - useful in the case that this builder is not in a
         * local variable, and it would be messy to put it in one - e.g.
         * <pre>
         * return ClassBuilder.forPackage("foo.bar").named("Baz")
         *   .method("whatever")
         *   .withModifier(PUBLIC)
         *    .conditionally(needSomeAnnnotation, mb -&gt; {
         *          mb.annotatedWith("Quux").closeAnnotation();
         *     })
         *     ...
         * </pre>
         *
         * @param condition A boolean
         * @param c A consumer
         * @return this
         */
        public MethodBuilder<T> conditionally(boolean condition, Consumer<MethodBuilder> c) {
            if (condition) {
                c.accept(this);
            }
            return this;
        }

        /**
         * Add or append to the javadoc comment.
         *
         * @param cmt A comment string
         * @return this
         */
        public MethodBuilder<T> docComment(String cmt) {
            if (this.docComment != null) {
                this.docComment += "\n" + cmt;
            } else {
                this.docComment = cmt;
            }
            return this;
        }

        public MethodBuilder<T> withTypeParams(Collection<? extends String> params) {
            for (String p : params) {
                withTypeParam(p);
            }
            return this;
        }

        public MethodBuilder<T> withTypeParam(String param) {
            return withTypeParam(param, new String[0]);
        }

        public MethodBuilder<T> withTypeParam(String first, String... more) {
            addTypeParam(first);
            for (String m : more) {
                addTypeParam(m);
            }
            return this;
        }

        private void addTypeParam(String tp) {
            if (tp == null) {
                throw new IllegalArgumentException("Null type param");
            }
            tp = tp.trim();
            if (tp.length() == 0) {
                throw new IllegalArgumentException("Empty type param");
            }
            String name = checkIdentifier(leadingToken(tp));
            if (typeParams.contains(tp)) {
                throw new IllegalArgumentException("Already have a type parameter '" + tp + " - cannot be added twice");
            }
            if (!typeParams.isEmpty()) {
                Set<String> names = new HashSet<>();
                for (String t : typeParams) {
                    if (leadingToken(t).equals(name)) {
                        throw new IllegalArgumentException("Already have a type parameter named "
                                + name + ": " + t + " - cannot add " + tp);
                    }
                }

            }
            typeParams.add(tp);
        }

        public MethodBuilder<T> makeFinal() {
            return withModifier(FINAL);
        }

        public MethodBuilder<T> throwing(String throwable) {
//            throwing.add(new Adhoc(throwable, true));
            throwing.add(parseTypeName(throwable));
            return this;
        }

        public T closeMethod() {
            return converter.apply(this);
        }

        public MethodBuilder<T> returning(String type) {
            this.type = type;
            return this;
        }

        public MethodBuilder<T> withModifier(Modifier mod, Modifier... more) {
            MethodBuilder<T> result = withModifier(mod);
            for (Modifier m : more) {
                result = withModifier(m);
            }
            return result;
        }

        public MethodBuilder<T> withModifier(Modifier mod) {
            switch (mod) {
                case NATIVE:
                case STRICTFP:
                case TRANSIENT:
                case VOLATILE:
                    throw new IllegalArgumentException("Inappropriate modifier for method: " + mod);
                case PRIVATE:
                    if (modifiers.contains(PROTECTED) || modifiers.contains(PUBLIC)) {
                        throw new IllegalStateException("Cannot be private and also protected or public");
                    }
                    break;
                case PROTECTED:
                    if (modifiers.contains(PRIVATE) || modifiers.contains(PUBLIC)) {
                        throw new IllegalStateException("Cannot be private and also protected or public");
                    }
                    break;
                case PUBLIC:
                    if (modifiers.contains(PRIVATE) || modifiers.contains(PROTECTED)) {
                        throw new IllegalStateException("Cannot be private and also protected or public");
                    }
                    break;
            }
            modifiers.add(mod);
            return this;
        }

        private List<ArgPair> args = new LinkedList<>();

        public MethodBuilder<T> addArgument(String type, String name) {
            args.add(new ArgPair(parseTypeName(notNull("type", type)), name));
            return this;
        }

        /**
         * Add a final varargs argument and open the body of this method for
         * writing.
         *
         * @param type The type, which will get "..." appended to it in code
         * @param name The name
         * @return A block
         */
        public BlockBuilder<T> addVarArgArgument(String type, String name) {
            CodeGenerator typeBody = new VarArgType(type);
//            BodyBuilder var = new Adhoc(checkIdentifier(notNull("name", name)));
            args.add(new ArgPair(typeBody, name));
            return body();
        }

        /**
         * Add a final varargs argument and open the body of this method for
         * writing.
         *
         * @param type The type, which will get "..." appended to it in code
         * @param name The name
         * @return A block
         */
        public T addVarArgArgument(String type, String name, Consumer<BlockBuilder<?>> c) {
            CodeGenerator typeBody = new VarArgType(type);
            args.add(new ArgPair(typeBody, name));
            return body(c);
        }

        public AnnotationBuilder<MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<MethodBuilder<T>>>>>
                addMultiAnnotatedArgument(String annotationType) {
            return new MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<MethodBuilder<T>>>>(bldr -> {
                return new ParameterNameBuilder<>(name -> {
                    return new TypeNameBuilder<>(typeName -> {
                        args.add(new ArgPair(bldr.appendingType(typeName.type), new Adhoc(name)));
                        return this;
                    });
                });
            }).annotatedWith(annotationType);
        }

        public MethodBuilder<T> addMultiAnnotatedArgument(String annotationType,
                Consumer<AnnotationBuilder<MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<Void>>>>> c) {
            Holder<MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<Void>>>> hold
                    = new Holder<>();
            MultiAnnotatedArgumentBuilder<ParameterNameBuilder<TypeNameBuilder<Void>>> result
                    = new MultiAnnotatedArgumentBuilder<>(bldr -> {
                        return new ParameterNameBuilder<>(name -> {
                            return new TypeNameBuilder<>(type -> {
                                args.add(new ArgPair(bldr.appendingType(type.type), new Adhoc(name)));
                                hold.set(bldr);
                                return null;
                            });
                        });
                    });
            c.accept(result.annotatedWith(annotationType));
            hold.ifUnset(result::closeAnnotations);
            return this;
        }

        private static final class ArgPair extends CodeGeneratorBase {

            private final CodeGenerator type;
            private final CodeGenerator name;

            ArgPair(CodeGenerator type, CodeGenerator name) {
                this.type = type;
                this.name = name;
            }

            ArgPair(CodeGenerator type, String name) {
                this.type = type;
                this.name = new Adhoc(checkIdentifier(name));
            }

            @Override
            public void generateInto(LinesBuilder lines) {
                type.generateInto(lines);
                name.generateInto(lines);
            }
        }

        private static final class VarArgType extends CodeGeneratorBase {

            private final CodeGenerator type;

            VarArgType(CodeGenerator type) {
                this.type = type;
            }

            public VarArgType(String type) {
                this(parseTypeName(notNull("type", type)));
            }

            @Override
            public void generateInto(LinesBuilder lines) {
                type.generateInto(lines);
                lines.backup().appendRaw("...");
            }
        }

        public T emptyBody() {
            return body().lineComment("do nothing").endBlock();
        }

        public MethodBuilder<T> annotatedWith(String annotationType, Consumer<? super AnnotationBuilder<?>> c) {
            boolean[] built = new boolean[1];
            AnnotationBuilder<MethodBuilder<T>> bldr = annotatedWith(annotationType, built);
            c.accept(bldr);
            if (!built[0]) {
                bldr.closeAnnotation();
            }
            return this;
        }

        public AnnotationBuilder<MethodBuilder<T>> annotatedWith(String annotationType) {
            return annotatedWith(annotationType, new boolean[1]);
        }

        private AnnotationBuilder<MethodBuilder<T>> annotatedWith(String annotationType, boolean[] built) {
            return new AnnotationBuilder<>(ab -> {
                annotations.add(ab);
                built[0] = true;
                return MethodBuilder.this;
            }, annotationType);
        }

        public MethodBuilder<T> override() {
            return annotatedWith("Override").closeAnnotation();
        }

        public T body(Consumer<? super BlockBuilder<?>> c) {
            Holder<T> hold = new Holder<>();
            BlockBuilder<Void> bldr = new BlockBuilder<>(bb -> {
                MethodBuilder.this.block = bb;
                hold.set(converter.apply(this));
                return null;
            }, false);
            c.accept(bldr);
            if (!hold.isSet()) {
                this.block = bldr;
                hold.set(converter.apply(this));
            }
            return hold.get();
        }

        /**
         * Use this <i>only</i> when you need toExpression toExpression pass the
         * body builder around toExpression foreign code toExpression contribute
         * toExpression, and be sure toExpression close it.
         *
         * @return A block builder
         */
        public BlockBuilder<MethodBuilder<T>> openBody() {
            if (this.block != null) {
                throw new IllegalStateException("Body already set to " + block);
            }
            return new BlockBuilder<>(bb -> {
                this.block = bb;
                return MethodBuilder.this;
            }, false);
        }

        public BlockBuilder<T> body() {
            return body(new boolean[1]);
        }

        public T body(String body) {
            return body(new boolean[1]).statement(body).endBlock();
        }

        public T bodyReturning(String body) {
            return body().returning(body).endBlock();
        }

        private BlockBuilder<T> body(boolean[] built) {
            if (this.block != null) {
                throw new IllegalStateException("Body already set to " + block);
            }
            return new BlockBuilder<>(bb -> {
                MethodBuilder.this.block = bb;
                built[0] = true;
                return converter.apply(this);
            }, false);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.doubleNewline();
            if (docComment != null) {
                writeDocComment(docComment, lines);
                lines.onNewLine();
            }
            if (!annotations.isEmpty()) {
                for (CodeGenerator bb : annotations) {
                    bb.generateInto(lines);
                }
            }
            for (Modifier m : modifiers) {
                lines.word(m.toString());
            }
            if (!typeParams.isEmpty()) {
                lines.appendRaw(' ');
//                    lb.commaDelimit(typeParams.toArray(new String[typeParams.size()]));
                lines.delimit('<', '>', lb -> {
                    for (Iterator<String> it = typeParams.iterator(); it.hasNext();) {
                        String param = it.next();
                        if (param.indexOf(' ') > 0) {
                            param = param.trim();
                            String[] parts = param.split("\\s+");
                            for (String p : parts) {
                                lb.word(p);
                            }
                        } else {
                            lb.word(param);
                        }
                        if (it.hasNext()) {
                            lines.appendRaw(',');
                        }
                    }
                });
            }
            lines.word(type, true);
            lines.word(name, true);
            lines.doubleHangingWrap(ll -> {
//                lines.wrappable(lb1 -> {
                lines.parens(lb -> {
                    for (Iterator<ArgPair> it = args.iterator(); it.hasNext();) {
                        ArgPair curr = it.next();
                        curr.generateInto(lb);
                        if (it.hasNext()) {
                            lb.appendRaw(',');
                        }
                    }
                });
                if (!throwing.isEmpty()) {
                    ll.word("throws");
                    for (Iterator<CodeGenerator> it = throwing.iterator(); it.hasNext();) {
                        CodeGenerator th = it.next();
                        th.generateInto(ll);
                        if (it.hasNext()) {
                            ll.appendRaw(",");
                        }
                    }
                }
//                });
            });
            if (block != null) {
                lines.block(lb -> {
                    if (block.statements.isEmpty()) {
                        lines.onNewLine().appendRaw("// do nothing");
                    } else {
                        block.generateInto(lb);
                    }
                });
            } else {
                // interface method
                lines.appendRaw(";");
            }
            lines.doubleNewline();
        }

        public String sig() {
            LinesBuilder lb = new LinesBuilder();
            for (Iterator<ArgPair> it = args.iterator(); it.hasNext();) {
                ArgPair curr = it.next();
                curr.generateInto(lb);
                if (it.hasNext()) {
                    lb.appendRaw(',');
                }
            }
            return lb.toString();
        }

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 67 * hash + Objects.hashCode(this.type);
            hash = 67 * hash + Objects.hashCode(this.name);
            hash = 67 * hash + Objects.hashCode(this.args);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final MethodBuilder<?> other = (MethodBuilder<?>) obj;
            if (!Objects.equals(this.type, other.type)) {
                return false;
            }
            if (!Objects.equals(this.name, other.name)) {
                return false;
            }
            return Objects.equals(this.args, other.args);
        }

    }

    private static final Pattern ARRAY = Pattern.compile("\\s*?(.*?)\\s*?(\\[.*\\])\\s*?");
    private static final Pattern VARARG = Pattern.compile("\\s*?(.*?)\\s*?\\.\\.\\.");

    static CodeGenerator parseTypeName(String typeName) {
        Matcher arrM = ARRAY.matcher(notNull("typeName", typeName));
        if (arrM.find()) {
            String tn = arrM.group(1);
            String arrDecl = arrM.group(2);
            CodeGenerator result = parseTypeName(tn);
            return new Composite(result, new BackupAndAppendRaw(arrDecl));
        }
        arrM = VARARG.matcher(typeName);
        if (arrM.find()) {
            String tn = arrM.group(1);
            CodeGenerator result = parseTypeName(tn);
            return new Composite(result, new BackupAndAppendRaw("..."));
        }
        if (checkIdentifier(typeName).indexOf('<') >= 0) {
            GenericTypeVisitor gtv = new GenericTypeVisitor();
            visitGenericTypes(typeName, 0, gtv);
            return gtv.result();
        } else {
            return new TypeNameItem(typeName);
        }
    }

//    public static void main(String[] args) {
//        String nm = "ThrowingTriFunction<MessageId, ? super T, ThrowingBiConsumer<? super Throwable, Acknowledgement>, Acknowledgement>";
//        visitGenericTypes(nm, 0, (depth, part) -> {
//            System.out.println(depth + ". " + part);
//        });
//
//        GenericTypeVisitor gen = new GenericTypeVisitor();
//        visitGenericTypes(nm, 0, gen);
//        System.out.println("\nresult:\n" + gen);
//        LinesBuilder lb = new LinesBuilder();
//        gen.result().buildInto(lb);
//        System.out.println("GOT\n" + lb.toString());
//        System.out.println(nm);
//    }
//
    static class TypeNameItem extends CodeGeneratorBase {

        private final String name;

        TypeNameItem(String name) {
            this.name = name;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.word(name, '<', true);
        }

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 97 * hash + Objects.hashCode(this.name);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final TypeNameItem other = (TypeNameItem) obj;
            return Objects.equals(this.name, other.name);
        }

    }

    static class Punctuation extends CodeGeneratorBase {

        private final char txt;

        Punctuation(char txt) {
            this.txt = txt;
        }

        @Override
        public String toString() {
            return Character.toString(txt);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.backup();
            lines.appendRaw(txt);
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 19 * hash + this.txt;
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final Punctuation other = (Punctuation) obj;
            return this.txt == other.txt;
        }
    }

    static class GenericTypeVisitor implements BiConsumer<Integer, String> {

        private int lastDepth;
        private final StringBuilder sb = new StringBuilder();
        private final Map<Integer, Integer> depthCounts = new HashMap<>();
        private static final Punctuation OPEN_ANGLE = new Punctuation('<');
        private static final Punctuation CLOSE_ANGLE = new Punctuation('>');
        private static final Punctuation COMMA = new Punctuation(',');

        private final List<CodeGenerator> items = new ArrayList<>();

        private int countForDepth(int depth) {
            return depthCounts.getOrDefault(depth, 0);
        }

        public CodeGenerator result() {
            done();
            return new Composite(items.toArray(new CodeGenerator[items.size()]));
        }

        private void add(int depth, String type) {
            int currCount = countForDepth(depth) + 1;
            depthCounts.put(depth, currCount);
            if (currCount - 1 > 0) {
                items.add(COMMA);
                sb.append(", ");
            }
            items.add(new TypeNameItem(type));
            sb.append(type);
        }

        void closeDepth(int depth) {
            depthCounts.put(depth, 0);
        }

        void done() {
            while (lastDepth > 0) {
                sb.append('>');
                items.add(CLOSE_ANGLE);
                lastDepth--;
            }
        }

        @Override
        public String toString() {
            done();
            return sb.toString();
        }

        @Override
        public void accept(Integer t, String u) {
            if (t > lastDepth) {
                sb.append('<');
                items.add(OPEN_ANGLE);
            } else if (t < lastDepth) {
                for (int i = lastDepth; i > t; i--) {
                    sb.append('>');
                    items.add(CLOSE_ANGLE);
                    closeDepth(i);
                }
            }
            lastDepth = t;
            add(t, u);
        }
    }

    private static int closingBracketOf(int ix, String in) {
        int openCount = 0;
        for (int i = ix + 1; i < in.length(); i++) {
            if (in.charAt(i) == '<') {
                openCount++;
            }
            if (in.charAt(i) == '>' && openCount == 0) {
                return i;
            } else if (in.charAt(i) == '>') {
                openCount--;
            }
        }
        return -1;
    }

    static void visitGenericTypes(String typeName, int depth, BiConsumer<Integer, String> c) {
        typeName = typeName.trim();
        int start = typeName.indexOf('<');
        if (start < 0 && typeName.indexOf('>') >= 0) {
            throw new IllegalArgumentException("Unbalanced <>'s in " + typeName);
        }
        int end = closingBracketOf(start, typeName);
        if (start < 0 != end < 0) {
            throw new IllegalArgumentException("Unbalanced <>'s in " + typeName);
        }
        if (end < start) {
            throw new IllegalArgumentException("Not a generic "
                    + "signature - first > comes before first < in '"
                    + typeName + "'");
        }
        if (start < 0 && end < 0) {
            for (String s : typeName.split(",")) {
                s = s.trim();
                c.accept(depth, s);
            }
        } else {
            String sub = typeName.substring(start + 1, end);
            String outer = typeName.substring(0, start);
            for (String s : outer.split(",")) {
                s = s.trim();
                c.accept(depth, s);
            }
            visitGenericTypes(sub, depth + 1, c);
            if (end < typeName.length() - 1) {
                String tail = typeName.substring(end + 1, typeName.length());
                if (tail.startsWith(",")) {
                    tail = tail.substring(1).trim();
                }
                if (!tail.isEmpty()) {
                    visitGenericTypes(tail, depth, c);
                }
            }
        }
    }

    static void visitTypeNames(String typeName, Consumer<? super String> c) {
        visitGenericTypes(typeName, 0, (depth, str) -> {
            for (String item : str.trim().split("\\s+")) {
                switch (item) {
                    case "?":
                    case "extends":
                    case "super":
                    case "&":
                        continue;
                    default:
                        c.accept(item);
                }
            }
        });

    }
    private static CodeGenerator RAW_BRACKETS = new BackupAndAppendRaw("[]");
    private static CodeGenerator WRAPPABLE_BRACKETS = new Adhoc("[]");

    /**
     * Gen an array literal or dimensions builder, generate the front half of a
     * declaration, for example, the
     * <code>Color[matrixWidth][matrixHeight]</code> portion of an array
     * declaration - both sides need to know the dimension count.
     *
     * @param <T> The builder's return type
     * @param alb The builder
     * @return A composite
     */
    private static <T> CodeGenerator typeAndDimensions(ArrayDeclarationBuilder<T> alb) {
        List<CodeGenerator> declarationWithDimensions = new ArrayList<>();
        declarationWithDimensions.add(alb.type());
        for (int i = 0; i < alb.dimensionCount(); i++) {
            if (i == 0) {
                declarationWithDimensions.add(RAW_BRACKETS);
            } else {
                declarationWithDimensions.add(WRAPPABLE_BRACKETS);
            }
        }
        return Composite.of(declarationWithDimensions);
    }

    public static class ArrayElementsBuilder<T> extends AbstractArrayElementsBuilder<T, ArrayElementsBuilder<T>> {

        ArrayElementsBuilder(Function<CodeGenerator, T> converter, Object... initial) {
            super(converter, initial);
        }

        public T of(String of) {
            return super.of(new Adhoc(checkIdentifier(of)));
        }

        public FieldReferenceBuilder<T> ofField(String field) {
            return new FieldReferenceBuilder<>(field, frb -> {
                return super.of(frb);
            });
        }

        public InvocationBuilder<T> ofInvocationOf(String method) {
            return new InvocationBuilder<>(ivb -> {
                return super.of(ivb);
            }, method);
        }

        public ValueExpressionBuilder<T> of() {
            return new ValueExpressionBuilder<>(veb -> {
                return super.of(veb);
            });
        }
    }

    public static class ArrayElementsDereferenceBuilder<T> extends AbstractArrayElementsBuilder<T, ArrayElementsDereferenceBuilder<T>> {

        ArrayElementsDereferenceBuilder(CodeGenerator of, Function<CodeGenerator, T> converter, Object... initial) {
            super(converter, initial);
            this.of = of;
        }

        @Override
        public T closeArrayElements() {
            return super.closeArrayElements();
        }
    }

    public static abstract class AbstractArrayElementsBuilder<T, A extends AbstractArrayElementsBuilder<T, A>> extends CodeGeneratorBase {

        CodeGenerator of;
        private final Function<CodeGenerator, T> converter;
        private List<CodeGenerator> elements = new ArrayList<>();

        AbstractArrayElementsBuilder(Function<CodeGenerator, T> converter, Object... initial) {
            this.converter = converter;
            for (Object s : initial) {
                elements.add(new Adhoc(s.toString()));
            }
        }

        @SuppressWarnings("unchecked")
        A cast() {
            return (A) this;
        }

        T of(CodeGenerator of) {
            this.of = of;
            return closeArrayElements();
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.wrappable(lb -> {
                of.generateInto(lines);
                for (CodeGenerator bb : elements) {
                    lb.squareBrackets(sq -> {
                        bb.generateInto(sq);
                    });
                }
            });
        }

        public FieldReferenceBuilder<A> elementFromField(String fieldName) {
            return new FieldReferenceBuilder<>(fieldName, frb -> {
                elements.add(frb);
                return cast();
            });
        }

        public InvocationBuilder<A> elementFromInvocationOf(String name) {
            return new InvocationBuilder<>(ib -> {
                elements.add(ib);
                return cast();
            }, name);
        }

        public A element(int index) {
            if (index < 0) {
                throw new IllegalArgumentException("Attempting negative array index");
            }
            elements.add(new Adhoc(Integer.toString(index), true));
            return cast();
        }

        public A element(String expression) {
            elements.add(new Adhoc(expression, true));
            return cast();
        }

        public ValueExpressionBuilder<A> element() {
            return new ValueExpressionBuilder<>(veb -> {
                elements.add(veb);
                return cast();
            });
        }

        public A element(Consumer<ValueExpressionBuilder<?>> c) {
            Holder<A> hold = new Holder<>();
            ValueExpressionBuilder<Void> result = new ValueExpressionBuilder<>(veb -> {
                elements.add(veb);
                hold.set(cast());
                return null;
            });
            c.accept(result);
            return hold.get("Array element value expression not completed: " + result);
        }

        T closeArrayElements() {
            if (elements.isEmpty()) {
                throw new IllegalStateException("Elements not set");
            }
            if (of == null) {
                throw new IllegalStateException("Target of array dereference not set");
            }
            return converter.apply(this);
        }
    }

    static final class Cast extends CodeGeneratorBase {

        private final CodeGenerator what;

        Cast(String what) {
            this(new Adhoc(what));
        }

        Cast(CodeGenerator what) {
            this.what = what;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.parens(what::generateInto);
        }
    }

    public static final class AssignmentBuilder<T> extends CodeGeneratorBase {

        private final Function<AssignmentBuilder<T>, T> converter;
        private CodeGenerator type;
        private CodeGenerator assignment;
        private final CodeGenerator varName;
        private CodeGenerator cast;

        AssignmentBuilder(Function<AssignmentBuilder<T>, T> converter, CodeGenerator varName) {
            this.converter = converter;
            this.varName = varName;
        }

        public AssignmentBuilder<T> withType(String type) {
            if (this.type != null) {
                throw new IllegalStateException("A type was already set: " + type);
            }
            this.type = parseTypeName(type);
            return this;
        }

        public T toExpression(String what) {
            assignment = new Adhoc(what);
            return converter.apply(this);
        }

        public FieldReferenceBuilder<T> toField(String name) {
            return new FieldReferenceBuilder<>(name, frb -> {
                assignment = frb;
                return converter.apply(this);
            });
        }

        public AssignmentBuilder<T> castTo(String type) {
            if (cast != null) {
                throw new IllegalStateException("Cast already set to "
                        + cast + " - cannot be set twice");
            }
            cast = new Cast(checkIdentifier(type));
            return this;
        }

        public T toField(String field, Consumer<FieldReferenceBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            FieldReferenceBuilder<Void> result = new FieldReferenceBuilder<>(notNull("field", field), frb -> {
                assignment = frb;
                holder.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return holder.get("Field reference not completed");
        }

        public ValueExpressionBuilder<ValueExpressionBuilder<T>> toTernary(String booleanExpression) {
            return toTernary().booleanExpression(booleanExpression);
        }

        public ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<T>>> toTernary() {
            ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<T>>> b
                    = new ConditionBuilder<>(cb -> {
                        TernaryBuilder<T> tb = new TernaryBuilder<>(tbb -> {
                            assignment = tbb;
                            return converter.apply(this);
                        }, cb);
                        return tb.ifTrue();
                    });
            return b;
        }

        public T toTernary(Consumer<ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<T>>>> c) {
            Holder<T> holder = new Holder<>();
            ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<Void>>> b
                    = new ConditionBuilder<>(cb -> {
                        TernaryBuilder<Void> tb = new TernaryBuilder<>(tbb -> {
                            assignment = tbb;
                            holder.set(converter.apply(this));
                            return null;
                        }, (FinishableConditionBuilder) cb);
                        return tb.ifTrue();
                    });
            return holder.get("Ternary condition not completed");
        }

        public InvocationBuilder<T> toInvocation(String of) {
            return new InvocationBuilder<>(ib -> {
                this.assignment = ib;
                return converter.apply(this);
            }, of);
        }

        public NewBuilder<T> toNewInstance() {
            return new NewBuilder<>(nb -> {
                this.assignment = nb;
                return converter.apply(this);
            });
        }

        public T toNewInstance(Consumer<? super NewBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            NewBuilder<Void> result = new NewBuilder<>(nb -> {
                this.assignment = nb;
                holder.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return holder.get("New instance builder not completed - call ofType()");
        }

        public T toLiteral(String what) {
            this.type = new TypeNameItem("String");
            return toExpression(LinesBuilder.stringLiteral(what));
        }

        public T toLiteral(char c) {
            this.type = new TypeNameItem("char");
            return toExpression(friendlyChar(c));
        }

        public T toLiteral(Number num) {
            this.type = new TypeNameItem(num.getClass().getSimpleName().toLowerCase());
            this.assignment = friendlyNumber(num);
            return converter.apply(this);
        }

        public T toLiteral(boolean val) {
            this.type = new TypeNameItem("boolean");
            this.assignment = new Adhoc(Boolean.toString(val));
            return converter.apply(this);
        }

        public AssignmentBuilder<T> to(Value assignment) {
            if (this.assignment != null) {
                throw new IllegalStateException("Attempt to assign twice: "
                        + assignment + " and " + this.assignment);
            }
            this.assignment = assignment;
            return this;
        }

        public T to(Consumer<ValueExpressionBuilder<?>> c) {
            Holder<T> hold = new Holder<>();
            ValueExpressionBuilder<Void> veb = new ValueExpressionBuilder<>(v -> {
                this.assignment = assignment;
                hold.accept(converter.apply(this));
                return null;
            });
            c.accept(veb);
            return hold.get("Value expression was not closed");
        }

        public ValueExpressionBuilder<T> to() {
            if (this.assignment != null) {
                throw new IllegalStateException("Attempt to assign twice: "
                        + assignment + " and " + this.assignment);
            }
            return new ValueExpressionBuilder<>(veb -> {
                this.assignment = veb;
                return converter.apply(this);
            });
        }

        public ArrayLiteralBuilder<T> toArrayLiteral(String type) {
            return new ArrayLiteralBuilder<>(alb -> {
                this.assignment = alb;
                this.type = typeAndDimensions(alb);
                return converter.apply(this);
            }, type);
        }

        public ArrayElementsBuilder<T> toArrayElement(Object first) {
            if (first instanceof Number) {
                if (((Number) first).doubleValue() < 0) {
                    throw new IllegalArgumentException("Attempting negative array index");
                }
            }
            return new ArrayElementsBuilder<>(aeb -> {
                this.assignment = aeb;
//                this type = typeAndDimensions(aeb);
                return converter.apply(this);
            }, first);
        }

        public ArrayElementsBuilder<T> toArrayElement() {
            return new ArrayElementsBuilder<>(aeb -> {
                this.assignment = aeb;
//                this type = typeAndDimensions(aeb);
                return converter.apply(this);
            });
        }

        public T toArrayLiteral(String type, Consumer<ArrayLiteralBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            ArrayLiteralBuilder<Void> res = new ArrayLiteralBuilder<>(alb -> {
                this.assignment = alb;
                holder.set(converter.apply(this));
                this.type = typeAndDimensions(alb);
                return null;
            }, type);
            c.accept(res);
            holder.ifUnset(res::closeArrayLiteral);
            return holder.get("Array literal not completed");
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.onNewLine();
            lines.wrappable(lb -> {
                if (type != null && !(this.assignment instanceof ArrayLiteralBuilder)) {
                    type.generateInto(lb);
                }
                if (varName != null) {
                    varName.generateInto(lb);
                }
                lb.word("=").appendRaw(' ');
                if (cast != null) {
                    cast.generateInto(lb);
                }
                assignment.generateInto(lb);
            });
        }
    }

    public static abstract class ArrayDeclarationBuilder<T> extends CodeGeneratorBase {

        public abstract T closeArrayLiteral();

        abstract CodeGenerator type();

        abstract int dimensionCount();
    }

    public static final class ArrayDimensionsBuilder<T> extends ArrayDeclarationBuilder<T> {

        private final Function<ArrayDeclarationBuilder<T>, T> converter;
        private final CodeGenerator type;
        private List<CodeGenerator> dimensions = new ArrayList<>();

        ArrayDimensionsBuilder(Function<ArrayDeclarationBuilder<T>, T> converter, CodeGenerator type, Object firstDimension) {
            this.converter = converter;
            this.type = type;
            dimensions.add(new Adhoc(firstDimension.toString()));
        }

        @Override
        CodeGenerator type() {
            return type;
        }

        public ArrayDeclarationBuilder<T> withDimension(Value v) {
            dimensions.add(v);
            return this;
        }

        public ArrayDimensionsBuilder<T> withDimension(int dim) {
            dimensions.add(new Adhoc(Integer.toString(dim)));
            return this;
        }

        public ArrayDimensionsBuilder<T> withDimension(String exp) {
            dimensions.add(new Adhoc(exp));
            return this;
        }

        public FieldReferenceBuilder<ArrayDimensionsBuilder<T>> withDimensionFromField(String what) {
            return new FieldReferenceBuilder<>(what, frb -> {
                dimensions.add(frb);
                return this;
            });
        }

        public InvocationBuilder<ArrayDimensionsBuilder<T>> withDimensionFromInvocationOf(String what) {
            return new InvocationBuilder<>(ib -> {
                dimensions.add(ib);
                return this;
            }, what);
        }

        @Override
        int dimensionCount() {
            return dimensions.size();
        }

        @Override
        public T closeArrayLiteral() {
            return converter.apply(this);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.word("new");
            type.generateInto(lines);
            lines.backup();
            for (CodeGenerator dim : dimensions) {
                lines.squareBrackets(dim::generateInto);
            }
        }
    }

    public static final class ArrayLiteralBuilder<T> extends ArrayDeclarationBuilder<T> {

        private final List<CodeGenerator> all = new LinkedList<>();
        private final Function<ArrayDeclarationBuilder<T>, T> converter;
        private final CodeGenerator type;

        ArrayLiteralBuilder(Function<ArrayDeclarationBuilder<T>, T> converter, String type) {
            this.converter = converter;
            this.type = type == null ? null : parseTypeName(type);
        }

        @Override
        int dimensionCount() {
            return 1;
        }

        @Override
        CodeGenerator type() {
            return type;
        }

        public ArrayDimensionsBuilder<T> withDimension(int dim) {
            if (!all.isEmpty()) {
                throw new IllegalStateException("Can either specify array contents, "
                        + "or array size when declaring an array, not both.");
            }
            return new ArrayDimensionsBuilder<>(converter, type, dim);
        }

        @Override
        public T closeArrayLiteral() {
            return converter.apply(this);
        }

        public FieldReferenceBuilder<ArrayLiteralBuilder<T>> field(String name) {
            return veb().field(name);
        }

        public ArrayLiteralBuilder<T> field(String field, Consumer<FieldReferenceBuilder<?>> c) {
            return veb().field(field, c);
        }

        public LambdaBuilder<ArrayLiteralBuilder<T>> lambda() {
            return veb().lambda();
        }

        public ArrayLiteralBuilder<T> invoke(String method, Consumer<InvocationBuilder<?>> c) {
            return veb().invoke(method, c);
        }

        public InvocationBuilder<ArrayLiteralBuilder<T>> invoke(String method) {
            return veb().invoke(method);
        }

        public ArrayLiteralBuilder<T> expression(String expression) {
            return veb().expression(expression);
        }

        public ArrayLiteralBuilder<T> literal(boolean val) {
            return veb().literal(val);
        }

        public ArrayLiteralBuilder<T> literal(Number num) {
            return veb().literal(num);
        }

        public ArrayLiteralBuilder<T> literal(char ch) {
            return veb().literal(ch);
        }

        public ArrayLiteralBuilder<T> literal(String s) {
            return veb().literal(s);
        }

        public ArrayLiteralBuilder<T> add(Value v) {
            all.add(v);
            return this;
        }

        public ValueExpressionBuilder<ArrayLiteralBuilder<T>> value() {
            return veb();
        }

        public NewBuilder<ArrayLiteralBuilder<T>> newInstance() {
            return new NewBuilder<>(nb -> {
                all.add(nb);
                return this;
            });
        }

        public ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<ArrayLiteralBuilder<T>>>> ternary() {
            return veb().ternary();
        }

        public ArrayLiteralBuilder<T> ternary(Consumer<ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<Void>>>> c) {
            return veb().toTernary(c);
        }

        public NewBuilder<ArrayLiteralBuilder<T>> newInstance(String firstArg) {
            return veb().toNewInstanceWithArgument(firstArg);
        }

        public ArrayLiteralBuilder<T> newInstance(Consumer<? super NewBuilder<?>> c) {
            return veb().toNewInstance(c);
        }

        public ArrayLiteralBuilder<ArrayLiteralBuilder<T>> arrayLiteral(String type) {
            return veb().toArrayLiteral(type);
        }

        public ArrayLiteralBuilder<T> arrayLiteral(String type, Consumer<ArrayLiteralBuilder<?>> c) {
            return veb().toArrayLiteral(type, c);
        }

        public AnnotationBuilder<ArrayLiteralBuilder<T>> annotation(String type) {
            return veb().annotation(type);
        }

        public ArrayLiteralBuilder<T> annotation(String type, Consumer<? super AnnotationBuilder<?>> c) {
            return veb().annotation(type, c);
        }

        public NumericOrBitwiseExpressionBuilder<ArrayLiteralBuilder<T>> numeric(int num) {
            return veb().numeric(num);
        }

        public NumericOrBitwiseExpressionBuilder<ArrayLiteralBuilder<T>> numeric(long num) {
            return veb().numeric(num);
        }

        public NumericOrBitwiseExpressionBuilder<ArrayLiteralBuilder<T>> numeric(short num) {
            return veb().numeric(num);
        }

        public NumericExpressionBuilder<ArrayLiteralBuilder<T>> numeric(double num) {
            return veb().numeric(num);
        }

        public NumericExpressionBuilder<ArrayLiteralBuilder<T>> numeric(float num) {
            return veb().numeric(num);
        }

        public ValueExpressionBuilder<NumericOrBitwiseExpressionBuilder<ArrayLiteralBuilder<T>>> numeric() {
            return veb().numeric();
        }

        public ArrayLiteralBuilder<T> add(String expression) {
            all.add(new Adhoc(expression));
            return this;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.word("new");
            type.generateInto(lines);
            lines.backup();
            lines.appendRaw("[]");
            lines.word("{");
            lines.wrappable(lb -> {
                for (Iterator<CodeGenerator> it = all.iterator(); it.hasNext();) {
                    CodeGenerator bb = it.next();
                    bb.generateInto(lb);
                    if (it.hasNext()) {
                        lb.appendRaw(",");
                    }
                }
            });
            lines.appendRaw("}");
        }

        private ValueExpressionBuilder<ArrayLiteralBuilder<T>> veb() {
            return new ValueExpressionBuilder<>(veb -> {
                all.add(veb);
                return this;
            });
        }
    }

    public static final class InvocationBuilder<T> extends InvocationBuilderBase<T, InvocationBuilder<T>> {

        InvocationBuilder(Function<InvocationBuilder<T>, T> converter, String name) {
            super(converter, name, false);
        }

        @Override
        public String toString() {
            LinesBuilder lb = new LinesBuilder(4);
            generateInto(lb);
            return lb.toString();
        }

        public T on(String what) {
            this.on = new Adhoc(what);
            return converter.apply(cast());
        }

        public FieldReferenceBuilder<T> onField(String field) {
            FieldReferenceBuilder<T> result = new FieldReferenceBuilder<>(field, frb -> {
                this.on = frb;
                return converter.apply(cast());
            });
            return result;
        }

        public T onField(String field, Consumer<FieldReferenceBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            FieldReferenceBuilder<Void> result = new FieldReferenceBuilder<>(field, frb -> {
                this.on = frb;
                holder.set(converter.apply(cast()));
                return null;
            });
            c.accept(result);
            return holder.get(result::ofThis);
        }

        public InvocationBuilder<T> onInvocationOf(String methodName) {
            return new InvocationBuilder<>(ib2 -> {
                on = ib2;
                return converter.apply(cast());
            }, methodName);
        }

        public T onInvocationOf(String methodName, Consumer<? super InvocationBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            InvocationBuilder<Void> inv = new InvocationBuilder<>(ib -> {
                on = ib;
                holder.set(converter.apply(cast()));
                return null;
            }, methodName);
            c.accept(inv);
            return holder.get("Invocation not completed");
        }

        /**
         * New target.
         *
         * @param type Unused
         * @return A builder
         * @deprecated The type parameter was never used and was in error
         */
        @Deprecated
        public NewBuilder<T> onNew(String type) {
            return new NewBuilder<>(nb -> {
                this.on = nb;
                return converter.apply(cast());
            });
        }

        public NewBuilder<T> onNew() {
            return new NewBuilder<>(nb -> {
                this.on = nb;
                return converter.apply(cast());
            });
        }

        public T onNew(Consumer<NewBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            NewBuilder<Void> result = new NewBuilder<>(nb -> {
                this.on = nb;
                holder.set(converter.apply(cast()));
                return null;
            });
            c.accept(result);
            return holder.get("NewBuilder not completed");
        }

        public ArrayElementsBuilder<T> onArrayElement(int el) {
            return new ArrayElementsBuilder<>(aeb -> {
                this.on = aeb;
                return converter.apply(cast());
            }, el);
        }

        public T onThis() {
            return on("this");
        }

        public T inScope() {
            on = null;
            return converter.apply(cast());
        }
    }

    public static final class NewBuilder<T> extends InvocationBuilderBase<T, NewBuilder<T>> {

        NewBuilder(Function<NewBuilder<T>, T> converter) {
            super(converter, null, true);
        }

        public T ofType(String type) {
            checkIdentifier(notNull("type", type));
            name = type;
            return converter.apply(cast());
        }
    }

    public static abstract class InvocationBuilderBase<T, B extends InvocationBuilderBase<T, B>> extends CodeGeneratorBase implements ArgumentConsumer<B> {

        final Function<B, T> converter;
        String name;
        CodeGenerator on;
        List<CodeGenerator> arguments = new LinkedList<>();
        boolean isNew;

        InvocationBuilderBase(Function<B, T> converter, String name) {
            this(converter, name, false);
        }

        InvocationBuilderBase(Function<B, T> converter, String name, boolean isNew) {
            this.converter = converter;
            this.name = name;
            this.isNew = isNew;
        }

        T applyFrom(InvocationBuilderBase<?, ?> other) {
            name = other.name;
            on = other.on;
            arguments = other.arguments;
            isNew = other.isNew;
            return converter.apply(cast());
        }

        @SuppressWarnings("unchecked")
        B cast() {
            return (B) this;
        }

        @Override
        public ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<B>>> withTernaryArgument() {
            ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<B>>> b
                    = new ConditionBuilder<>(cb -> {
                        TernaryBuilder<B> tb = new TernaryBuilder<>(tbb -> {
                            arguments.add(tbb);
                            return cast();
                        }, (FinishableConditionBuilder) cb);
                        return tb.ifTrue();
                    });
            return b;
        }

        @Override
        public B withTernaryArgument(Consumer<? super ConditionBuilder<? extends ValueExpressionBuilder<? extends ValueExpressionBuilder<?>>>> c) {
            Holder<B> holder = new Holder<>();
            ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<Void>>> b
                    = new ConditionBuilder<>(cb -> {
                        TernaryBuilder<Void> tb = new TernaryBuilder<>(tbb -> {
                            arguments.add(tbb);
                            holder.set(cast());
                            return null;
                        }, cb);
                        return tb.ifTrue();
                    });
            c.accept(b);
            return holder.get("Ternary expression not completed");
        }

        @Override
        public B withArrayArgument(Consumer<? super ArrayValueBuilder<?>> c) {
            boolean[] built = new boolean[1];
            ArrayValueBuilder<?> av = withArrayArgument(built);
            c.accept(av);
            if (!built[0]) {
                av.closeArray();
            }
            return cast();
        }

        @Override
        public ArrayValueBuilder<B> withArrayArgument() {
            return withArrayArgument(new boolean[1]);
        }

        private ArrayValueBuilder<B> withArrayArgument(boolean[] built) {
            return new ArrayValueBuilder<>('[', ']', av -> {
                arguments.add(av);
                built[0] = true;
                return cast();
            });
        }

        @Override
        public NewBuilder<B> withNewInstanceArgument() {
            return new NewBuilder<>(nb -> {
                arguments.add(nb);
                return cast();
            });
        }

        @Override
        public B withNewInstanceArgument(Consumer<? super NewBuilder<?>> c) {
            Holder<B> holder = new Holder<>();
            NewBuilder<Void> result = new NewBuilder<>(nb -> {
                holder.set(cast());
                arguments.add(nb);
                return null;
            });
            c.accept(result);
            return holder.get("New instance creation not finished - onType() "
                    + "not called");
        }

        @Override
        public B withNewArrayArgument(String arrayType, Consumer<? super ArrayValueBuilder<?>> c) {
            boolean[] built = new boolean[1];
            ArrayValueBuilder<?> av = withNewArrayArgument(arrayType, built);
            c.accept(av);
            if (!built[0]) {
                av.closeArray();
            }
            return cast();
        }

        @Override
        public ArrayValueBuilder<B> withNewArrayArgument(String arrayType) {
            return withNewArrayArgument(arrayType, new boolean[1]);
        }

        private ArrayValueBuilder<B> withNewArrayArgument(String arrayType, boolean[] built) {
            return new ArrayValueBuilder<>('{', '}', av -> {
                arguments.add(new Composite(new Adhoc("new"), new Adhoc(arrayType + "[]"), av));
                built[0] = true;
                return cast();
            });
        }

        @Override
        public B withArgument(int arg) {
            arguments.add(new Adhoc(Integer.toString(arg)));
            return cast();
        }

        @Override
        public B withArgument(char arg) {
            arguments.add(new Adhoc(LinesBuilder.escapeCharLiteral(arg)));
            return cast();
        }

        @Override
        public B withArgument(String arg) {
            arguments.add(new Adhoc(arg));
            return cast();
        }

        @Override
        public B withClassArgument(String arg) {
            arguments.add(new Adhoc(arg + ".class"));
            return cast();
        }

        @Override
        public B withArgument(boolean arg) {
            arguments.add(new Adhoc(Boolean.toString(arg)));
            return cast();
        }

        @Override
        public B withStringLiteral(String arg) {
            return withArgument(LinesBuilder.stringLiteral(arg));
        }

        @Override
        public B withArguments(String... args) {
            Set<String> dups = new HashSet<>();
            for (String arg : args) {
                if (dups.contains(arg)) {
                    throw new IllegalArgumentException("Duplicate argument name: " + arg);
                }
                withArgument(arg);
                dups.add(arg);
            }
            return cast();
        }

        @Override
        public B withArgumentFromInvoking(String name, Consumer<? super InvocationBuilder<?>> c) {
            Holder<B> holder = new Holder<>();
            InvocationBuilder<Void> result = new InvocationBuilder<>(ib -> {
                arguments.add(ib);
                holder.set(cast());
                return null;
            }, name);
            c.accept(result);
            holder.ifUnset(result::inScope);
            return holder.get("Invocation builder not completed");
        }

        @Override
        public B withArgument(Consumer<ValueExpressionBuilder<?>> consumer) {
            Holder<B> holder = new Holder<>();
            ValueExpressionBuilder<Void> result = new ValueExpressionBuilder<>(veb -> {
                arguments.add(veb);
                holder.set(cast());
                return null;
            });
            consumer.accept(result);
            return holder.get("Value expression not closed");
        }

        @Override
        public ValueExpressionBuilder<B> withArgument() {
            ValueExpressionBuilder<B> result = new ValueExpressionBuilder<>(veb -> {
                arguments.add(veb);
                return cast();
            });
            return result;
        }

        public B withArgument(Value v) {
            arguments.add(v);
            return cast();
        }

        @Override
        public StringConcatenationBuilder<B> withStringConcatentationArgument(String initialLiteral) {
            StringConcatenationBuilder<B> sb = new StringConcatenationBuilder<>(new Adhoc(LinesBuilder.stringLiteral(initialLiteral)), scb -> {
                arguments.add(scb);
                return cast();
            });
            return sb;
        }

        @Override
        public B withStringConcatentationArgument(String initialLiteral, Consumer<StringConcatenationBuilder<?>> c) {
            Holder<B> holder = new Holder<>();
            StringConcatenationBuilder<Void> sb = new StringConcatenationBuilder<>(new Adhoc(LinesBuilder.stringLiteral(initialLiteral)), scb -> {
                arguments.add(scb);
                holder.set(cast());
                return null;
            });
            c.accept(sb);
            holder.ifUnset(sb::endConcatenation);
            return holder.get(sb::endConcatenation);
        }

        @Override
        public NumericOrBitwiseExpressionBuilder<B> withNumericExpressionArgument(int literal) {
            return new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(literal), fnobeb -> {
                arguments.add(fnobeb);
                return cast();
            });
        }

        @Override
        public NumericOrBitwiseExpressionBuilder<B> withNumericExpressionArgument(long literal) {
            return new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(literal), fnobeb -> {
                arguments.add(fnobeb);
                return cast();
            });
        }

        @Override
        public NumericOrBitwiseExpressionBuilder<B> withNumericExpressionArgument(short literal) {
            return new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(literal), fnobeb -> {
                arguments.add(fnobeb);
                return cast();
            });
        }

        @Override
        public NumericOrBitwiseExpressionBuilder<B> withNumericExpressionArgument(byte literal) {
            return new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(literal), fnobeb -> {
                arguments.add(fnobeb);
                return cast();
            });
        }

        @Override
        public NumericExpressionBuilder<B> withNumericExpressionArgument(double literal) {
            return new NumericExpressionBuilder<>(friendlyNumber(literal), fnobeb -> {
                arguments.add(fnobeb);
                return cast();
            });
        }

        @Override
        public NumericExpressionBuilder<B> withNumericExpressionArgument(float literal) {
            return new NumericExpressionBuilder<>(friendlyNumber(literal), fnobeb -> {
                arguments.add(fnobeb);
                return cast();
            });
        }

        @Override
        public ValueExpressionBuilder<NumericOrBitwiseExpressionBuilder<B>> withNumericExpressionArgument() {
            return new ValueExpressionBuilder<>(veb -> {
                return new NumericOrBitwiseExpressionBuilder<>(veb, fnobeb -> {
                    arguments.add(fnobeb);
                    return cast();
                });
            });
        }

        @Override
        public InvocationBuilder<B> withArgumentFromInvoking(String name) {
            return withArgumentFromInvoking(new boolean[1], name);
        }

        @Override
        public NewBuilder<B> withArgumentFromNew(String what) {
            return new NewBuilder<>(nb -> {
                arguments.add(nb);
                return cast();
            });
        }

        @Override
        public B withArgumentFromNew(Consumer<NewBuilder<?>> c) {
            Holder<B> h = new Holder<>();
            NewBuilder<Void> nb = new NewBuilder<>(b -> {
                arguments.add(b);
                h.set(cast());
                return null;
            });
            c.accept(nb);
            return h.get("New builder not completed.");
        }

        @Override
        public FieldReferenceBuilder<B> withArgumentFromField(String name) {
            return new FieldReferenceBuilder<>(name, frb -> {
                arguments.add(frb);
                return cast();
            });
        }

        @Override
        public B withArgumentFromField(String name, Consumer<FieldReferenceBuilder<?>> c) {
            Holder<B> h = new Holder<>();
            FieldReferenceBuilder<Void> result = new FieldReferenceBuilder<>(name, frb -> {
                arguments.add(frb);
                h.set(cast());
                return null;
            });
            c.accept(result);
            return h.get("Field reference builder not completed");
        }

        @Override
        public B withLambdaArgument(Consumer<? super LambdaBuilder<?>> c) {
            boolean[] built = new boolean[1];
            LambdaBuilder<?> builder = new LambdaBuilder<Void>(lb -> {
                if (!arguments.contains(lb)) {
                    arguments.add(lb);
                }
                built[0] = true;
                return null;
            });
            c.accept(builder);
            if (!built[0]) {
                throw new IllegalStateException("Lambda builder not finished");
            }
            return cast();
        }

        @Override
        public LambdaBuilder<B> withLambdaArgument() {
            return withLambdaArgument(new boolean[1]);
        }

        private LambdaBuilder<B> withLambdaArgument(boolean[] built) {
            return new LambdaBuilder<>(lb -> {
                arguments.add(lb);
                built[0] = true;
                return cast();
            });
        }

        private InvocationBuilder<B> withArgumentFromInvoking(boolean[] built, String name) {
            return new InvocationBuilder<>(ib -> {
                arguments.add(ib);
                built[0] = true;
                return cast();
            }, name);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.newlineIfNewStatement();
            lines.wrappable(lbb -> {
                if (on != null) {
                    on.generateInto(lbb);
                    lbb.appendRaw(".");
                    lbb.appendRaw(name);
                } else {
                    if (isNew) {
                        lbb.word("new");
                        parseTypeName(name).generateInto(lines);
                    } else {
                        lbb.word(name);
                    }
                }
                lbb.parens(lb -> {
                    lbb.hangingWrap(lb1 -> {
                        for (Iterator<CodeGenerator> it = arguments.iterator(); it.hasNext();) {
                            CodeGenerator arg = it.next();
                            arg.generateInto(lb1);
                            if (it.hasNext()) {
                                lb1.appendRaw(',');
                            }
                        }
                    });
                });
            });
        }
    }

    public static final class SimpleLoopBuilder<T> implements CodeGenerator {

        private final Function<SimpleLoopBuilder<T>, T> converter;
        private final String loopVar;
        private String type;
        private CodeGenerator from;
        private BlockBuilder body;

        SimpleLoopBuilder(Function<SimpleLoopBuilder<T>, T> converter, String loopType, String loopVar) {
            this.converter = converter;
            this.type = loopType;
            this.loopVar = loopVar;
        }

        public FieldReferenceBuilder<BlockBuilder<T>> overFieldReference(String field) {
            return new FieldReferenceBuilder<>(field, frb -> {
                this.from = frb;
                BlockBuilder<T> result = new BlockBuilder<>(bb -> {
                    body = bb;
                    return converter.apply(this);
                }, true);
                return result;
            });
        }

        public BlockBuilder<T> overFieldReference(String field, Consumer<FieldReferenceBuilder<?>> c) {
            Holder<BlockBuilder<T>> h = new Holder<>();
            FieldReferenceBuilder<Void> result = new FieldReferenceBuilder<>(field, frb -> {
                this.from = frb;
                h.set(new BlockBuilder<>(bb -> {
                    body = bb;
                    return converter.apply(this);
                }, true));
                return null;
            });
            c.accept(result);
            return h.get("Field reference not completed");
        }

        public ArrayLiteralBuilder<BlockBuilder<T>> overArrayLiteral() {
            return new ArrayLiteralBuilder<>(alb -> {
                this.from = alb;
                return new BlockBuilder<>(bb -> {
                    this.body = bb;
                    return converter.apply(this);
                }, true);
            }, this.type);
        }

        public BlockBuilder<T> overArrayLiteral(Consumer<ArrayLiteralBuilder<?>> cb) {
            Holder<BlockBuilder<T>> holder = new Holder<>();
            ArrayLiteralBuilder<Void> result = new ArrayLiteralBuilder<>(alb -> {
                this.from = alb;
                BlockBuilder<T> bldr = new BlockBuilder<>(bb -> {
                    this.body = bb;
                    return converter.apply(this);
                }, true);
                holder.set(bldr);
                return null;
            }, this.type);
            cb.accept(result);
            holder.ifUnset(result::closeArrayLiteral);
            return holder.get("Array literal not completed");
        }

        public BlockBuilder<T> over(String expression) {
            return over(expression, new boolean[1]);
        }

        public T over(String expression, Consumer<? super BlockBuilder<?>> cb) {
            Holder<T> holder = new Holder<>();
            BlockBuilder<Void> bldr = new BlockBuilder<>(bb -> {
                this.body = bb;
                this.from = new Adhoc(expression);
                holder.set(converter.apply(this));
                return null;
            }, true);
            cb.accept(bldr);
            if (!holder.isSet()) {
                bldr.endBlock();
                holder.set(converter.apply(this));
            }
            return holder.get();
        }

        private BlockBuilder<T> over(String what, boolean[] built) {
            this.from = new Adhoc(what);
            return new BlockBuilder<>(bb -> {
                this.body = bb;
                built[0] = true;
                return converter.apply(this);
            }, true);
        }

        public BlockBuilder<T> overInvocationOf(String name, Consumer<InvocationBuilder<?>> c) {
            Holder<BlockBuilder<T>> h = new Holder<>();
            InvocationBuilder<Void> inv = new InvocationBuilder<>(ib -> {
                this.from = ib;
                BlockBuilder<T> block = new BlockBuilder<>(bb -> {
                    this.body = bb;
                    return converter.apply(this);
                }, true);
                h.set(block);
                return null;
            }, name);
            c.accept(inv);
            if (!h.isSet()) {
                inv.inScope();
            }
            return h.get("Invocation builder not completed");
        }

        public InvocationBuilder<BlockBuilder<T>> overInvocationOf(String name) {
            return new InvocationBuilder<>(ib -> {
                this.from = ib;
                return new BlockBuilder<>(bb -> {
                    this.body = bb;
                    return converter.apply(this);
                }, true);
            }, name);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.onNewLine();
            lines.word("for ");
            lines.parens(lb -> {
                lb.word(type);
                lb.word(loopVar);
                lb.word(":");
                from.generateInto(lines);
            });
            body.generateInto(lines);
        }
    }

    public static final class ForVarBuilder<T> extends CodeGeneratorBase {

        private final Function<ForVarBuilder<T>, T> converter;
        private String loopVarType = "int";
        private CodeGenerator initializedWith = new Adhoc("0");
        private boolean increment = true;
        private CodeGenerator condition;
        private final String loopVar;
        private CodeGenerator body;

        ForVarBuilder(Function<ForVarBuilder<T>, T> converter, String loopVar) {
            this.converter = converter;
            this.loopVar = loopVar;
        }

        public ForVarBuilder<T> decrement() {
            increment = false;
            return this;
        }

        public ForVarBuilder<T> initializedWith(Value v) {
            this.initializedWith = v;
            return this;
        }

        public ForVarBuilder<T> initializedWith(Number num) {
            initializedWith = friendlyNumber(num);
            return this;
        }

        public ComparisonBuilder<ForVarBuilder<T>> condition() {
            return new ConditionBuilder<>(fcb -> {
                condition = fcb;
                return this;
            }).variable(loopVar);
        }

        public T condition(Consumer<ComparisonBuilder<BlockBuilder<Void>>> c) {
            Holder<T> hold = new Holder<>();
            Holder<BlockBuilder<T>> blockHolder = new Holder<>();
            ComparisonBuilder<BlockBuilder<Void>> cb = new ConditionBuilder<BlockBuilder<Void>>(fcb -> {
                condition = fcb;
                BlockBuilder<T> result = new BlockBuilder<>(bb -> {
                    this.body = bb;
                    return converter.apply(this);
                }, true);
                blockHolder.set(result);
                return null;
            }).variable(loopVar);
            c.accept(cb);
            if (blockHolder.isSet() && !hold.isSet()) {
                blockHolder.get().endBlock();
            }
            return hold.get("Block builder or comparison builder was not closed");
        }

        public BlockBuilder<T> running() {
            return new BlockBuilder<>(bb -> {
                this.body = bb;
                return converter.apply(this);
            }, true);
        }

        public T running(Consumer<BlockBuilder<?>> c) {
            Holder<T> hold = new Holder<>();
            BlockBuilder<Void> block = new BlockBuilder<>(blk -> {
                this.body = blk;
                hold.set(converter.apply(this));
                return null;
            }, true);
            c.accept(block);
            hold.ifUnset(block::endBlock);
            return hold.get("Block not completed");
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.onNewLine();
            lines.word("for");
            lines.parens(lb -> {
                lb.word(loopVarType).word(loopVar).word("=");
                initializedWith.generateInto(lines);
                lb.appendRaw(";");
                condition.generateInto(lines);
                lb.appendRaw(";");
                if (increment) {
                    lb.word(loopVar);
                    lb.appendRaw("++");
                } else {
                    lb.word(loopVar);
                    lb.appendRaw("--");
                }
            });
            body.generateInto(lines);
        }
    }

    public static final class TryBuilder<T> extends BlockBuilderBase<T, TryBuilder<T>, T> {

        final List<CatchBuilder<?>> catches = new ArrayList<>();
        private CodeGenerator finallyBlock;
        private boolean tryBuilt;
        private T buildResult;

        public TryBuilder(Function<? super CodeGenerator, T> converter) {
            super(converter, true);
        }

        @Override
        T x() {
            return converter.apply(this);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.onNewLine();
            lines.word("try ");
            super.generateInto(lines);
            for (CatchBuilder<?> cb : catches) {
                lines.backup();
                cb.generateInto(lines);
            }
            if (finallyBlock != null) {
                lines.backup();
                lines.word("finally ");
                finallyBlock.generateInto(lines);
            }
        }

        @Override
        protected T endBlock() {
            // We can get built on each catch or finally, so
            // ensure we're only added once to the containing block -
            // if our contents are updated afterwards, that's fine
            if (tryBuilt) {
                return buildResult;
            }
            if (finallyBlock == null && catches.isEmpty()) {
                throw new IllegalStateException("Try block does not have any "
                        + "catch or finally blocks and will result in an uncompilable "
                        + "source.");
            }
            tryBuilt = true;
            return buildResult = super.endBlock();
        }

        public T catching(Consumer<? super CatchBuilder<?>> c, String type, String... moreTypes) {
            boolean[] done = new boolean[1];
            CatchBuilder<T> catcher = new CatchBuilder<>(this, catchTypes(type, moreTypes), cb -> {
                catches.add(cb);
                return null;
            });
            c.accept(catcher);
            if (!done[0]) {
                catches.add(catcher);
            }
            return endBlock();
        }

        public CatchBuilder<T> catching(String type, String... moreTypes) {
            CodeGenerator ct = catchTypes(type, moreTypes);
            CatchBuilder<T> result = new CatchBuilder<>(this, ct, cb -> {
                catches.add(cb);
                return endBlock();
            });
            return result;
        }

        public T fynalli(Consumer<? super BlockBuilder<?>> bb) {
            if (finallyBlock != null) {
                throw new IllegalStateException("Already have a finally block " + finallyBlock);
            }
            Holder<T> h = new Holder<>();
            BlockBuilder<Void> result = new BlockBuilder<>(b -> {
                finallyBlock = b;
                T res = endBlock();
                h.set(res);
                return null;
            }, true);
            bb.accept(result);
            if (!h.isSet()) {
                result.endBlock();
            }
            return buildResult;
        }

        public BlockBuilder<T> fynalli() {
            if (finallyBlock != null) {
                throw new IllegalStateException("Already have a finally block " + finallyBlock);
            }
            return new BlockBuilder<>(bb -> {
                finallyBlock = bb;
                return endBlock();
            }, true);
        }
    }

    public static final class CatchBuilder<T> extends BlockBuilderBase<T, CatchBuilder<T>, T> {

        private static final String DEFAULT_EX_NAME = "thrown";
        private String exceptionName = DEFAULT_EX_NAME;
        private final TryBuilder<T> parent;
        private final CodeGenerator types;

        CatchBuilder(TryBuilder<T> parent, CodeGenerator types, Function<? super CatchBuilder<T>, T> convert) {
            super(convert, true);
            this.parent = parent;
            this.types = types;
        }

        @Override
        T x() {
            return converter.apply(this);
        }

        public T endTryCatch() {
            return endBlock();
        }

        public CatchBuilder<T> log() {
            return logException(exceptionName);
        }

        public CatchBuilder<T> logException(Level level) {
            return logException(level, exceptionName);
        }

        public CatchBuilder<T> logExceptionWithMessage(String msg) {
            return logException(Level.SEVERE, exceptionName, msg);
        }

        public CatchBuilder<T> logExceptionWithMessage(Level level, String msg) {
            return logException(level, exceptionName, msg);
        }

        public BlockBuilder<T> fynalli() {
            parent.catches.add(this);
            return parent.fynalli();
        }

        public T finalli(Consumer<BlockBuilder<?>> c) {
            parent.catches.add(this);
            return parent.fynalli(c);
        }

        public CatchBuilder<T> catching(String type, String... moreTypes) {
            parent.catches.add(this);
            CatchBuilder<T> result = new CatchBuilder<>(parent, catchTypes(type, moreTypes), converter);
            result.exceptionName = exceptionName + "_1";
            return result;
        }

        public T catching(Consumer<? super CatchBuilder<?>> c, String type, String... moreTypes) {
            Holder<T> h = new Holder<>();
            CatchBuilder<T> result = new CatchBuilder<>(parent, catchTypes(type, moreTypes), cb -> {
                parent.catches.add(this);
                parent.catches.add(cb);
                h.set(endBlock());
                return null;
            });
            result.exceptionName = exceptionName + "_1";
            c.accept(result);
            if (!h.isSet()) {
                result.endBlock();
            }
            return h.get();
        }

        public CatchBuilder<T> as(String name) {
            notNull("exception name", name);
            checkIdentifier(name);
            if (!DEFAULT_EX_NAME.equals(exceptionName) && !exceptionName.equals(name)) {
                throw new IllegalStateException("Exception name already set to '" + exceptionName + "'");
            }
            exceptionName = name;
            return this;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.word("catch ");
            lines.parens(lb -> {
                lines.hangingWrap(lb1 -> {
                    types.generateInto(lb1);
                    lb1.word(exceptionName);
                });
            });
            super.generateInto(lines);
        }
    }

    private static CodeGenerator catchTypes(String type, String... more) {
        List<CodeGenerator> all = new ArrayList<>();
        all.add(parseTypeName(notNull("type", type)));
        if (more.length > 0) {
            for (int i = 0; i < more.length; i++) {
                all.add(new Adhoc("|"));
                all.add(new Adhoc(notNull("more[" + i + "]", more[i])));
            }
        }
        CodeGenerator types = (new Composite(all.toArray(new CodeGenerator[all.size()])));
        return types;

    }

    private static final class SynchronizedBlockBuilder<T> implements CodeGenerator {

        private final Function<SynchronizedBlockBuilder<T>, T> converter;
        private BlockBuilder<?> body;
        private final String on;

        SynchronizedBlockBuilder(Function<SynchronizedBlockBuilder<T>, T> converter, String on) {
            this.converter = converter;
            this.on = on;
        }

        BlockBuilder<T> block() {
            return new BlockBuilder<>(bb -> {
                body = bb;
                return converter.apply(this);
            }, true);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.onNewLine();
            lines.word("synchronized");
            lines.parens(lb -> {
                lb.word(on);
            });
            body.generateInto(lines);
        }
    }

    public static final class LambdaBuilder<T> implements CodeGenerator {

        private final Function<LambdaBuilder<T>, T> converter;
        private BlockBuilder<?> body;
        private final LinkedHashMap<String, CodeGenerator> arguments = new LinkedHashMap<>();
        private Exception creation;

        LambdaBuilder(Function<LambdaBuilder<T>, T> converter) {
            this.converter = converter;
            creation = new Exception();
        }

        public LambdaBuilder<T> withArgument(String type, String arg) {
            arguments.put(checkIdentifier(notNull("arg", arg)), parseTypeName(type));
            return this;
        }

        public LambdaBuilder<T> withArgument(String name) {
            arguments.put(checkIdentifier(notNull("name", name)), EMPTY);
            return this;
        }

        public BlockBuilder<T> body() {
            return block();
        }

        public T body(Consumer<? super BlockBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            BlockBuilder<Void> b = new BlockBuilder<>(bb -> {
                this.body = bb;

                LinesBuilder lb = new LinesBuilder();
                bb.generateInto(lb);
                h.set(converter.apply(this));
                return null;
            }, true);
            c.accept(b);
            if (!h.isSet()) {
                b.endBlock();
            }
            return h.get("Lambda not finished");
        }

        BlockBuilder<T> block() {
            return block(new boolean[1]);
        }

        private BlockBuilder<T> block(boolean[] built) {
            return new BlockBuilder<>(bb -> {
                body = bb;
                built[0] = true;
                return converter.apply(this);
            }, true);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            if (body == null) {
                throw new IllegalStateException("Body null", creation);
            }
            if (arguments.isEmpty()) {
                lines.word("()");
            } else if (arguments.size() == 1 && EMPTY == (arguments.get(arguments.keySet().iterator().next()))) {
                lines.word(arguments.keySet().iterator().next());
            } else {
                lines.parens(lb -> {
                    int argCount = 0;
                    for (Iterator<Map.Entry<String, CodeGenerator>> it = arguments.entrySet().iterator(); it.hasNext();) {
                        Map.Entry<String, CodeGenerator> e = it.next();
                        CodeGenerator type = e.getValue();
                        String name = e.getKey();
                        int ac = type == EMPTY ? 1 : 2;
                        if (argCount != 0 && argCount != ac) {
                            throw new IllegalStateException("Lambda arguments mix "
                                    + "specifying type and not specifying type for "
                                    + arguments, creation);
                        }
                        argCount = ac;
                        if (ac == 1) {
                            lb.word(name);
                        } else {
                            type.generateInto(lb);
                            lb.word(name);
                        }
                        if (it.hasNext()) {
                            lb.appendRaw(",");
                        }
                    }
                });
            }
            lines.word("->");
            body.generateInto(lines);
            lines.backup();
        }
    }

    interface Operator extends CodeGenerator {

        boolean applicableTo(Number num);
    }

    public static class StringConcatenationBuilder<T> implements CodeGenerator {

        private CodeGenerator leftSide;
        private final Function<? super StringConcatenationBuilder<T>, T> converter;

        StringConcatenationBuilder(CodeGenerator leftSide, Function<? super StringConcatenationBuilder<T>, T> converter) {
            this.leftSide = leftSide;
            this.converter = converter;
        }

        @Override
        public String toString() {
            return "StringConcatenationBuilder(" + leftSide + ")";
        }

        public StringConcatenationBuilder<T> with(Value val) {
            if (leftSide == null) {
                leftSide = val;
                return this;
            }
            CodeGenerator newLeftSide = new Composite(leftSide, Operators.PLUS, val);
            leftSide = newLeftSide;
            return this;
        }

        public ValueExpressionBuilder<StringConcatenationBuilder<T>> with() {
            return new ValueExpressionBuilder<>(veb -> {
                if (leftSide == null) {
                    leftSide = veb;
                    return this;
                }
                CodeGenerator newLeftSide = new Composite(leftSide, Operators.PLUS, veb);
                leftSide = newLeftSide;
                return this;
            });
        }

        public StringConcatenationBuilder<T> with(Consumer<ValueExpressionBuilder<?>> c) {
            Holder<StringConcatenationBuilder<T>> hold = new Holder<>();
            ValueExpressionBuilder<Void> vb = new ValueExpressionBuilder<Void>(veb -> {
                hold.set(this);
                if (leftSide == null) {
                    leftSide = veb;
                    return null;
                }
                CodeGenerator newLeftSide = new Composite(leftSide, Operators.PLUS, veb);
                leftSide = newLeftSide;
                return null;
            });
            c.accept(vb);
            hold.ifUnset(vb::closeIfComplete);
            return hold.get("Value builder not closed");
        }

        public StringConcatenationBuilder<T> appendAll(String delimiter, Iterable<? extends CharSequence> all) {
            StringConcatenationBuilder<T> last = this;
            for (Iterator<? extends CharSequence> it = all.iterator(); it.hasNext();) {
                CharSequence seq = it.next();
                last = append(seq.toString());
                if (it.hasNext()) {
                    last = append(delimiter);
                }
            }
            return last;
        }

        public StringConcatenationBuilder<T> append(String stringLiteral) {
            return with().literal(stringLiteral);
        }

        public StringConcatenationBuilder<T> append(char charLiteral) {
            return with().literal(charLiteral);
        }

        public StringConcatenationBuilder<T> append(int intLiteral) {
            return with().literal(intLiteral);
        }

        public StringConcatenationBuilder<T> append(float floatLiteral) {
            return with().literal(floatLiteral);
        }

        public StringConcatenationBuilder<T> append(long literal) {
            return with().literal(literal);
        }

        public StringConcatenationBuilder<T> append(double literal) {
            return with().literal(literal);
        }

        public StringConcatenationBuilder<T> append(byte literal) {
            return with().literal(literal);
        }

        public StringConcatenationBuilder<T> append(short literal) {
            return with().literal(literal);
        }

        public StringConcatenationBuilder<T> append(boolean literal) {
            return with().literal(literal);
        }

        public StringConcatenationBuilder<T> appendExpression(String expression) {
            return with().expression(expression);
        }

        public InvocationBuilder<StringConcatenationBuilder<T>> appendInvocationOf(String ofMethod) {
            return with().invoke(ofMethod);
        }

        public StringConcatenationBuilder<T> appendInvocationOf(String ofMethod, Consumer<InvocationBuilder<?>> c) {
            return with().invoke(ofMethod, c);
        }

        public FieldReferenceBuilder<StringConcatenationBuilder<T>> appendField(String fieldName) {
            return with().field(fieldName);
        }

        public StringConcatenationBuilder<T> appendField(String fieldName, Consumer<FieldReferenceBuilder<?>> c) {
            return with().field(fieldName, c);
        }

        public T endConcatenation() {
            if (leftSide == null) {
                throw new IllegalStateException("String concatenation never added to");
            }
            return converter.apply(this);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            leftSide.generateInto(lines);
        }
    }

    public enum NumericCast implements CodeGenerator {
        INTEGER("int"),
        SHORT("short"),
        BYTE("byte"),
        LONG("long"),
        DOUBLE("double"),
        FLOAT("float"),;
        private final String stringValue;

        private NumericCast(String stringValue) {
            this.stringValue = stringValue;
        }

        @Override
        public String toString() {
            return stringValue;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.parens(lb -> {
                lb.appendRaw(stringValue + " ");
            });
        }
    }

    /**
     * Base class for builders for creating the right side of a mathematical
     * expression - e.g. the <code>* 2</code> portion of <code>3 * 2</code>.
     *
     * @see ValueExpressionBuilder
     * @param <T> The type
     */
    public static abstract class NumericExpressionBuilderBase<T, N extends NumericExpressionBuilderBase<T, N, F>, F> {

        final CodeGenerator leftSide;
        final Function<F, T> converter;
        boolean parenthesized;
        NumericCast castTo;

        NumericExpressionBuilderBase(CodeGenerator leftSide, Function<F, T> converter) {
            this.leftSide = leftSide;
            this.converter = converter;
        }

        abstract F newFinishable(CodeGenerator rightSide, Operator op);

        @SuppressWarnings("unchecked")
        private N cast() {
            return (N) this;
        }

        /**
         * Wrap this expression in parentheses.
         *
         * @return this
         */
        public N parenthesized() {
            this.parenthesized = true;
            return cast();
        }

        /**
         * Cast the result of this expression.
         *
         * @param cast The type to cast to
         * @return A numeric type
         */
        public N castTo(NumericCast cast) {
            this.castTo = cast;
            return cast();
        }

        /**
         * Cast to int.
         *
         * @return this
         */
        public N asInt() {
            return castTo(NumericCast.INTEGER);
        }

        /**
         * Cast to long.
         *
         * @return this
         */
        public N asLong() {
            return castTo(NumericCast.LONG);
        }

        /**
         * Cast to short.
         *
         * @return this
         */
        public N asShort() {
            return castTo(NumericCast.SHORT);
        }

        /**
         * Cast to byte.
         *
         * @return this
         */
        public N asByte() {
            return castTo(NumericCast.BYTE);
        }

        /**
         * Cast to float.
         *
         * @return this
         */
        public N asFloat() {
            return castTo(NumericCast.FLOAT);
        }

        /**
         * Cast to double.
         *
         * @return this
         */
        public N asDouble() {
            return castTo(NumericCast.DOUBLE);
        }

        ValueExpressionBuilder<F> valueBuilder(Operator op) {
            return new ValueExpressionBuilder<>(veb -> {
                return newFinishable(veb.value, op);
            });
        }

        /**
         * Add the passed literal to the left side of the expression
         * encapsulated here.
         *
         * @param lit A number of some numeric type
         * @return A builder which encapsulates the left side, the operation and
         * the right side (the passed literal) which can be closed to add it to
         * the class element being constructed
         */
        public F plus(Number lit) {
            return newFinishable(friendlyNumber(lit), Operators.PLUS);
        }

        /**
         * Subtract the passed literal from the left side of the expression
         * encapsulated here.
         *
         * @param lit A number of some numeric type
         * @return A builder which encapsulates the left side, the operation and
         * the right side (the passed literal) which can be closed to add it to
         * the class element being constructed
         */
        public F minus(Number lit) {
            return newFinishable(friendlyNumber(lit), Operators.MINUS);
        }

        /**
         * Multiply the passed literal with the left side of the expression
         * encapsulated here.
         *
         * @param lit A number of some numeric type
         * @return A builder which encapsulates the left side, the operation and
         * the right side (the passed literal) which can be closed to add it to
         * the class element being constructed
         */
        public F times(Number lit) {
            return newFinishable(friendlyNumber(lit), Operators.TIMES);
        }

        /**
         * Divide the left side of the expression encapsulated here by the
         * passed numeric literal.
         *
         * @param lit A number of some numeric type
         * @return A builder which encapsulates the left side, the operation and
         * the right side (the passed literal) which can be closed to add it to
         * the class element being constructed
         */
        public F dividedBy(Number lit) {
            return newFinishable(friendlyNumber(lit), Operators.DIVIDED_BY);
        }

        /**
         * Take the modulus of the left side of the expression encapsulated here
         * and the passed numeric literal.
         *
         * @param lit A number of some numeric type
         * @return A builder which encapsulates the left side, the operation and
         * the right side (the passed literal) which can be closed to add it to
         * the class element being constructed
         */
        public F modulo(Number lit) {
            return newFinishable(friendlyNumber(lit), Operators.MODULO);
        }

        /*
        public N nested(String startingExpression, Consumer<NumericExpressionBuilder<?>> c) {
            Holder<FinishableNumericExpressionBuilder<Void>> f = new Holder();
            Holder<N> hold = new Holder<>();
            NumericExpressionBuilder<Void> nu = new NumericExpressionBuilder<>(new Adhoc(startingExpression),
                    fcb -> {
                        f.set(fcb);
                        fcb.parenthesized();
                        hold.set(cast());
                        return null;
                    });
            c.accept(nu);
            if (!hold.isSet() && f.isSet()) {
                f.get().endNumericExpression();
            }
            return hold.get("Sub-expression not completed.");
        }
         */
        /**
         * Create a builder for the right side value of this expression, which
         * will be added to the left side.
         *
         * @return A value builder to create the right-side value for the
         * expression
         */
        public ValueExpressionBuilder<F> plus() {
            return valueBuilder(Operators.PLUS);
        }

        /**
         * Create a builder for the right side value of this expression, which
         * will be subtracted from the left side.
         *
         * @return A value builder to create the right-side value for the
         * expression
         */
        public ValueExpressionBuilder<F> minus() {
            return valueBuilder(Operators.MINUS);
        }

        /**
         * Create a builder for the right side value of this expression, which
         * will be multiplied by the left side.
         *
         * @return A value builder to create the right-side value for the
         * expression
         */
        public ValueExpressionBuilder<F> times() {
            return valueBuilder(Operators.TIMES);
        }

        /**
         * Create a builder for the right side value of this expression, which
         * will be divided into the left side.
         *
         * @return A value builder to create the right-side value for the
         * expression
         */
        public ValueExpressionBuilder<F> dividedBy() {
            return valueBuilder(Operators.DIVIDED_BY);
        }

        /**
         * Create a builder for the right side value of this expression, which
         * will take the modulus of the left side and the number.
         *
         * @return A value builder to create the right-side value for the
         * expression
         */
        public ValueExpressionBuilder<F> modulo() {
            return valueBuilder(Operators.MODULO);
        }

        /**
         * Create a builder for the right side value of this expression, which
         * will be bitwise <code>OR</code>'d with the left side.
         *
         * @return A value builder to create the right-side value for the
         * expression
         */
        public ValueExpressionBuilder<F> bitwiseOr() {
            return valueBuilder(BitwiseOperators.OR);
        }

        /**
         * Create a builder for the right side value of this expression, which
         * will be bitwise <code>AND</code>'d with the left side.
         *
         * @return A value builder to create the right-side value for the
         * expression
         */
        public ValueExpressionBuilder<F> bitwiseAnd() {
            return valueBuilder(BitwiseOperators.AND);
        }

        /**
         * Create a builder for the right side value of this expression, which
         * will be bitwise <code>XOR</code>'d with the left side.
         *
         * @return A value builder to create the right-side value for the
         * expression
         */
        public ValueExpressionBuilder<F> bitwiseXor() {
            return valueBuilder(BitwiseOperators.XOR);
        }

        /**
         * Create a builder for the right side value of this expression, which
         * will be bitwise <code>XOR</code>'d with the left side.
         *
         * @return A value builder to create the right-side value for the
         * expression
         */
        public ValueExpressionBuilder<F> bitwiseComplement() {
            return valueBuilder(BitwiseOperators.COMPLEMENT);
        }

        /**
         * Create a builder for the right side value of this expression, which
         * will be bitwise <code>XOR</code>'d with the left side.
         *
         * @return A value builder to create the right-side value for the
         * expression
         */
        public ValueExpressionBuilder<F> rotateBy() {
            return valueBuilder(BitwiseOperators.ROTATE);
        }

        /**
         * Create a builder for the right side value of this expression, which
         * will be bitwise <code>XOR</code>'d with the left side.
         *
         * @return A value builder to create the right-side value for the
         * expression
         */
        public ValueExpressionBuilder<F> shiftLeftBy() {
            return valueBuilder(BitwiseOperators.SHIFT_LEFT);
        }

        /**
         * Create a builder for the right side value of this expression, which
         * will be bitwise <code>XOR</code>'d with the left side.
         *
         * @return A value builder to create the right-side value for the
         * expression
         */
        public ValueExpressionBuilder<F> shiftRightBy() {
            return valueBuilder(BitwiseOperators.SHIFT_LEFT);
        }
    }

    /**
     * Base class for builders for creating the right side of a mathematical or
     * bitwise expression - e.g. the <code>* 2</code> portion of
     * <code>3 * 2</code>.
     *
     * @see ValueExpressionBuilder
     * @param <T> The type
     */
    public static abstract class NumericOrBitwiseExpressionBuilderBase<T, N extends NumericOrBitwiseExpressionBuilderBase<T, N, F>, F> extends NumericExpressionBuilderBase<T, N, F> {

        NumericOrBitwiseExpressionBuilderBase(CodeGenerator leftSide, Function<F, T> converter) {
            super(leftSide, converter);
        }

        /**
         * Compute the <i>bitwise complement</i> the left side of the
         * expression.
         *
         * @return A value builder which, once a value is supplied, returns a
         * builder which encapsulates both the left side and the complement
         * operation, which can be closed to add it to whatever is being edited
         */
        public F complement() {
            if (leftSide instanceof NumericExpressionBuilderBase<?, ?, ?>) {
                ((NumericExpressionBuilderBase<?, ?, ?>) leftSide).parenthesized();
            }
            return newFinishable(EMPTY, BitwiseOperators.COMPLEMENT);
        }

        /**
         * <i>Bitwise AND</i> the left side of the expression with the value or
         * expression you provide to the returned builder.
         *
         * @return A value builder which, once a value is supplied, returns a
         * builder which encapsulates both the left side from this builder, an
         * AND operation and the right side you supply by calling methods on the
         * returned builder
         */
        public ValueExpressionBuilder<F> and() {
            return valueBuilder(BitwiseOperators.AND);
        }

        /**
         * <i>Bitwise XOR</i> the left side of the expression with the value or
         * expression you provide to the returned builder.
         *
         * @return A value builder which, once a value is supplied, returns a
         * builder which encapsulates both the left side from this builder, an
         * AND operation and the right side you supply by calling methods on the
         * returned builder
         */
        public ValueExpressionBuilder<F> xor() {
            return valueBuilder(BitwiseOperators.XOR);
        }

        /**
         * <i>Bitwise left-rotate</i> the left side of the expression with the
         * value or expression you provide to the returned builder.
         *
         * @return A value builder which, once a value is supplied, returns a
         * builder which encapsulates both the left side from this builder, an
         * AND operation and the right side you supply by calling methods on the
         * returned builder
         */
        public ValueExpressionBuilder<F> rotate() {
            return valueBuilder(BitwiseOperators.ROTATE);
        }

        /**
         * <i>Bitwise left-shift</i> the left side of the expression with the
         * value or expression you provide to the returned builder.
         *
         * @return A value builder which, once a value is supplied, returns a
         * builder which encapsulates both the left side from this builder, an
         * AND operation and the right side you supply by calling methods on the
         * returned builder
         */
        public ValueExpressionBuilder<F> shiftLeft() {
            return valueBuilder(BitwiseOperators.SHIFT_LEFT);
        }

        /**
         * <i>Bitwise right-shift</i> the left side of the expression with the
         * value or expression you provide to the returned builder.
         *
         * @return A value builder which, once a value is supplied, returns a
         * builder which encapsulates both the left side from this builder, an
         * AND operation and the right side you supply by calling methods on the
         * returned builder
         */
        public ValueExpressionBuilder<F> shiftRight() {
            return valueBuilder(BitwiseOperators.SHIFT_LEFT);
        }

        /**
         * <i>Bitwise OR</i> the left side of the expression with the value or
         * expression you provide to the returned builder.
         *
         * @return A value builder which, once a value is supplied, returns a
         * builder which encapsulates both the left side from this builder, an
         * AND operation and the right side you supply by calling methods on the
         * returned builder
         */
        public ValueExpressionBuilder<F> or() {
            return valueBuilder(BitwiseOperators.OR);
        }

        public F or(Value val) {
            return newFinishable(val, BitwiseOperators.OR);
        }

        public F and(Value val) {
            return newFinishable(val, BitwiseOperators.AND);
        }

        public F rotate(Value val) {
            return newFinishable(val, BitwiseOperators.ROTATE);
        }

        public F shiftLeft(Value val) {
            return newFinishable(val, BitwiseOperators.SHIFT_LEFT);
        }

        public F shiftRight(Value val) {
            return newFinishable(val, BitwiseOperators.SHIFT_RIGHT);
        }

        public F xor(Value val) {
            return newFinishable(val, BitwiseOperators.XOR);
        }

        /**
         * <i>Bitwise OR</i> the left side of this expression with the passed
         * literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F or(int lit) {
            return newFinishable(friendlyNumber(lit), BitwiseOperators.OR);
        }

        /**
         * <i>Bitwise OR</i> the left side of this expression with the passed
         * literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F or(long lit) {
            return newFinishable(friendlyNumber(lit), BitwiseOperators.OR);
        }

        /**
         * <i>Bitwise OR</i> the left side of this expression with the passed
         * literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F or(byte lit) {
            return newFinishable(friendlyNumber(lit), BitwiseOperators.OR);
        }

        /**
         * <i>Bitwise OR</i> the left side of this expression with the passed
         * literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F or(short lit) {
            return newFinishable(friendlyNumber(lit), BitwiseOperators.OR);
        }

        /**
         * <i>Bitwise AND</i> the left side of this expression with the passed
         * literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F and(long lit) {
            return newFinishable(friendlyNumber(lit), BitwiseOperators.AND);
        }

        /**
         * <i>Bitwise AND</i> the left side of this expression with the passed
         * literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F and(int lit) {
            return newFinishable(friendlyNumber(lit), BitwiseOperators.AND);
        }

        /**
         * <i>Bitwise AND</i> the left side of this expression with the passed
         * literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F and(byte lit) {
            return newFinishable(friendlyNumber(lit), BitwiseOperators.AND);
        }

        /**
         * <i>Bitwise AND</i> the left side of this expression with the passed
         * literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F and(short lit) {
            return newFinishable(friendlyNumber(lit), BitwiseOperators.AND);
        }

        /**
         * <i>Bitwise XOR</i> the left side of this expression with the passed
         * literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F xor(int lit) {
            return newFinishable(friendlyNumber(lit), BitwiseOperators.XOR);
        }

        /**
         * <i>Bitwise XOR</i> the left side of this expression with the passed
         * literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F xor(long lit) {
            return newFinishable(friendlyNumber(lit), BitwiseOperators.XOR);
        }

        /**
         * <i>Bitwise XOR</i> the left side of this expression by the value of
         * the passed literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F xor(byte lit) {
            return newFinishable(friendlyNumber(lit), BitwiseOperators.XOR);
        }

        /**
         * <i>Bitwise XOR</i> the left side of this expression by the value of
         * the passed literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F xor(short lit) {
            return newFinishable(friendlyNumber(lit), BitwiseOperators.XOR);
        }

        /**
         * <i>Bitwise rotate</i> the left side of this expression by the value
         * of the passed literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F rotate(int by) {
            return newFinishable(friendlyNumber(by), BitwiseOperators.ROTATE);
        }

        /**
         * <i>Bitwise shift-left</i> the left side of this expression by the
         * value of the passed literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F shiftLeft(int by) {
            return newFinishable(friendlyNumber(by), BitwiseOperators.SHIFT_LEFT);
        }

        /**
         * <i>Bitwise shift-right</i> the left side of this expression by the
         * value of the passed literal.
         *
         * @param lit A literal
         * @return A value builder which encapsulates the left and right side
         * and operation which can be closed to add its contents to whatever
         * element is under construction.
         */
        public F shiftRight(int by) {
            return newFinishable(friendlyNumber(by), BitwiseOperators.SHIFT_RIGHT);
        }
    }

    /**
     * Builder for creating the <i>right side</i> of a mathematical - e.g. the
     * <code>* 2</code> portion of <code>3 * 2</code>, used with doubles and
     * floats which do not permit bitwise operations.
     *
     * @see ValueExpressionBuilder
     * @param <T> The type
     */
    public static class NumericExpressionBuilder<T> extends NumericExpressionBuilderBase<T, NumericExpressionBuilder<T>, FinishableNumericExpressionBuilder<T>> {

        Consumer<FinishableNumericExpressionBuilder<T>> notifying;

        NumericExpressionBuilder(CodeGenerator leftSide, Function<FinishableNumericExpressionBuilder<T>, T> converter) {
            super(leftSide, converter);
        }

        NumericExpressionBuilder<T> notifying(Consumer<FinishableNumericExpressionBuilder<T>> c) {
            if (notifying != null) {
                notifying = notifying.andThen(c);
            } else {
                notifying = c;
            }
            return this;
        }

        @Override
        FinishableNumericExpressionBuilder<T> newFinishable(CodeGenerator rightSide, Operator op) {
            FinishableNumericExpressionBuilder<T> result = new FinishableNumericExpressionBuilder<>(rightSide, leftSide, op, parenthesized, converter);
            if (castTo != null) {
                result.castTo = castTo;
            }
            if (notifying != null) {
                notifying.accept(result);
            }
            return result;
        }

    }

    /**
     * Builder for creating the <i>right side</i> of a mathematical or bitwise
     * expression - e.g. the <code>* 2</code> portion of <code>3 * 2</code>.
     *
     * @see ValueExpressionBuilder
     * @param <T> The type
     */
    public static class NumericOrBitwiseExpressionBuilder<T> extends NumericOrBitwiseExpressionBuilderBase<T, NumericOrBitwiseExpressionBuilder<T>, FinishableNumericOrBitwiseExpressionBuilder<T>> {

        Consumer<FinishableNumericOrBitwiseExpressionBuilder<T>> notifying;

        NumericOrBitwiseExpressionBuilder(CodeGenerator leftSide, Function<FinishableNumericOrBitwiseExpressionBuilder<T>, T> converter) {
            super(leftSide, converter);
        }

        NumericOrBitwiseExpressionBuilder<T> notifying(Consumer<FinishableNumericOrBitwiseExpressionBuilder<T>> c) {
            if (notifying != null) {
                notifying = notifying.andThen(c);
            } else {
                notifying = c;
            }
            return this;
        }

        @Override
        FinishableNumericOrBitwiseExpressionBuilder<T> newFinishable(CodeGenerator rightSide, Operator op) {
            FinishableNumericOrBitwiseExpressionBuilder<T> result = new FinishableNumericOrBitwiseExpressionBuilder<>(rightSide, leftSide, op, parenthesized, converter);
            if (notifying != null) {
                notifying.accept(result);
            }
            return result;
        }
    }

    /**
     * Numeric expression which has a left side and a right side and an
     * operation, and can be added to or closed.
     *
     * @param <T> The type
     */
    public static final class FinishableNumericExpressionBuilder<T> extends NumericExpressionBuilderBase<T, FinishableNumericExpressionBuilder<T>, FinishableNumericExpressionBuilder<T>> implements CodeGenerator {

        private final CodeGenerator rightSide;
        private final Operator op;

        FinishableNumericExpressionBuilder(CodeGenerator rightSide, CodeGenerator leftSide, Operator op, boolean parenthesized, Function<FinishableNumericExpressionBuilder<T>, T> converter) {
            super(leftSide, converter);
            this.rightSide = rightSide;
            this.op = op;
            this.parenthesized = parenthesized;
        }

        @Override
        FinishableNumericExpressionBuilder<T> newFinishable(CodeGenerator rightSide, Operator op) {
            return new FinishableNumericExpressionBuilder<>(rightSide, this, op, false, converter);
        }

        public T endNumericExpression() {
            return converter.apply(this);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            if (parenthesized) {
                lines.parens(this::reallyBuildInto);
            } else {
                reallyBuildInto(lines);
            }
        }

        private void reallyBuildInto(LinesBuilder lines) {
            if (castTo != null) {
                castTo.generateInto(lines);
            }
            leftSide.generateInto(lines);
            op.generateInto(lines);
            rightSide.generateInto(lines);
        }
    }

    public static final class FinishableNumericOrBitwiseExpressionBuilder<T> extends NumericOrBitwiseExpressionBuilderBase<T, FinishableNumericOrBitwiseExpressionBuilder<T>, FinishableNumericOrBitwiseExpressionBuilder<T>> implements CodeGenerator {

        private final CodeGenerator rightSide;
        private final Operator op;

        FinishableNumericOrBitwiseExpressionBuilder(CodeGenerator rightSide, CodeGenerator leftSide, Operator op, boolean parenthesized, Function<FinishableNumericOrBitwiseExpressionBuilder<T>, T> converter) {
            super(leftSide, converter);
            this.rightSide = rightSide;
            this.op = op;
            this.parenthesized = parenthesized;
        }

        @Override
        FinishableNumericOrBitwiseExpressionBuilder<T> newFinishable(CodeGenerator rightSide, Operator op) {
            return new FinishableNumericOrBitwiseExpressionBuilder<>(rightSide, this, op, false, converter);
        }

        public T endNumericExpression() {
            return converter.apply(this);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            if (parenthesized) {
                lines.parens(this::reallyBuildInto);
            } else {
                reallyBuildInto(lines);
            }
        }

        private void reallyBuildInto(LinesBuilder lines) {
            if (op == COMPLEMENT) {
                op.generateInto(lines);
                leftSide.generateInto(lines);
                assert EMPTY == rightSide;
            } else {
                leftSide.generateInto(lines);
                op.generateInto(lines);
                rightSide.generateInto(lines);
            }
        }
    }

    /**
     * Generic builder for Java expressions which return something - literal
     * values, method invocations, fields on accessible objects, etc. Most
     * builders have a way to get one of these, which is more verbose but a more
     * flexible way to create any sort of expression.
     *
     * @param <T> The type under construction
     */
    public static class ValueExpressionBuilder<T> implements CodeGenerator {

        private CodeGenerator value;
        private final Function<ValueExpressionBuilder<T>, T> converter;
        private boolean parenthesized;
        private boolean newline;
        private Composite cast;
        private Exception creation = new Exception("Created"); // XXX deleteme

        ValueExpressionBuilder(Function<ValueExpressionBuilder<T>, T> converter) {
            this.converter = converter;
        }

        T closeIfComplete() {
            if (value == null) {
                throw new IllegalStateException("Value was not completed - unclosed sub-builder?");
            }
            return converter.apply(this);
        }

        public ValueExpressionBuilder<T> onNewLine() {
            this.newline = newline;
            return this;
        }

        @Override
        public String toString() {
            return "ValueExpressionBuilder(" + value + ")";
        }

        public ValueExpressionBuilder<T> parenthesized() {
            parenthesized = true;
            return this;
        }

        public ValueExpressionBuilder<T> subexpression() {
            return new ValueExpressionBuilder<T>(veb -> {
                veb.parenthesized = true;
                value = veb;
                return converter.apply(this);
            });
        }

        public ConditionBuilder<T> booleanExpression() {
            return new ConditionBuilder<>(cond -> {
                value = cond;
                return converter.apply(this);
            });
        }

        public ComparisonBuilder<T> booleanExpression(String startingTerm) {
            return booleanExpression().variable(startingTerm);
        }

        public T booleanExpression(String startingTerm, Consumer<ComparisonBuilder<?>> c) {
            return booleanExpression(cond -> {
                c.accept(cond.variable(startingTerm));
            });
        }

        public T booleanExpression(Consumer<ConditionBuilder<?>> c) {
            Holder<T> hold = new Holder<>();
            ConditionBuilder<Void> result = new ConditionBuilder<>(cond -> {
                value = cond;
                hold.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return hold.get("Condition builder not completed");
        }

        /**
         * Create a reference to a field of some object, some newly created
         * object or the return value of invocation of something.
         *
         * @param name The name of the field to be referenced - you will fill in
         * the "what" part in the returned builder
         *
         * @return A field reference builder
         */
        public FieldReferenceBuilder<T> field(String name) {
            return new FieldReferenceBuilder<>(notNull("name", name), frb -> {
                value = frb;
                return converter.apply(this);
            });
        }

        /**
         * Create a reference to a field of some object, some newly created
         * object or the return value of invocation of something.
         *
         * @param name The name of the field to be referenced - you will fill in
         * the "what" part in the returned builder
         * @param c A consumer for the created builder, which <i>must</i> be
         * completed immediately
         * @throws IllegalStateException if the field builder was not completed
         * before the consumer exited
         *
         * @return A field reference builder
         */
        public T field(String field, Consumer<FieldReferenceBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            FieldReferenceBuilder<Void> result = new FieldReferenceBuilder<>(notNull("field", field), frb -> {
                value = frb;
                holder.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return holder.get("Field reference not completed");
        }

        public LambdaBuilder<T> lambda() {
            LambdaBuilder<T> ib = new LambdaBuilder<>(b -> {
                value = b;
                return converter.apply(this);
            });
            return ib;
        }

        public T lambda(Consumer<LambdaBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            LambdaBuilder<Void> result = new LambdaBuilder<>(lb -> {
                value = lb;
                h.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return h.get("Lambda builder not completed");
        }

        public T invoke(String method, Consumer<InvocationBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            InvocationBuilder<Void> ib = new InvocationBuilder<>(b -> {
                value = b;
                holder.set(converter.apply(this));
                return null;
            }, notNull("method", method));
            c.accept(ib);
            holder.ifUnset(ib::inScope);
            return holder.get("Invocation not completed");
        }

        public InvocationBuilder<T> invoke(String method) {
            InvocationBuilder<T> ib = new InvocationBuilder<>(b -> {
                value = b;
                return converter.apply(this);
            }, notNull("method", method));
            return ib;
        }

        public T expression(String expression) {
            value = new Adhoc(expression);
            return converter.apply(this);
        }

        public T literal(boolean val) {
            return expression(Boolean.toString(val));
        }

        public T literal(Number num) {
            value = friendlyNumber(num);
            return converter.apply(this);
        }

        public T literal(char ch) {
            value = new Adhoc(friendlyChar(ch));
            return converter.apply(this);
        }

        public T literal(String s) {
            value = new Adhoc(LinesBuilder.stringLiteral(s));
            return converter.apply(this);
        }

        public ValueExpressionBuilder<T> castTo(String type) {
            CodeGenerator typeBody = parseTypeName(type);
            cast = new Composite(new Punctuation('('), typeBody, new Punctuation(')'));
            return this;
        }

        public ArrayElementsDereferenceBuilder<ValueExpressionBuilder<T>> arrayElements() {
            return new ArrayElementsDereferenceBuilder<>(value, aedb -> {
                value = aedb;
                return this;
            });
        }

        public ValueExpressionBuilder<T> arrayElements(Consumer<ArrayElementsDereferenceBuilder<?>> c) {
            Holder<ValueExpressionBuilder<T>> hold = new Holder<>();
            ArrayElementsDereferenceBuilder<Void> bldr = new ArrayElementsDereferenceBuilder<>(value,
                    aedb -> {
                        value = aedb;
                        hold.set(this);
                        return null;
                    });
            c.accept(bldr);
            return hold.get(() -> bldr.closeArrayElements());
        }

        public ValueExpressionBuilder<StringConcatenationBuilder<T>> stringConcatenation() {
            return new ValueExpressionBuilder<>(veb -> {
                return new StringConcatenationBuilder<>(veb, scb -> {
                    value = scb;
                    return converter.apply(this);
                });
            });
        }

        public StringConcatenationBuilder<T> concatenatedWith() {
            if (value == null) {
                throw new IllegalStateException("No existing value to concatenate with", creation);
            }
            return new StringConcatenationBuilder<T>(value, scb -> {
                this.value = scb;
                return converter.apply(this);
            });
        }

        public StringConcatenationBuilder<T> concatenate(String stringLiteral) {
            CodeGenerator base = new Adhoc(LinesBuilder.stringLiteral(stringLiteral));
            return new StringConcatenationBuilder<>(base, scb -> {
                value = scb;
                return converter.apply(this);
            });
        }

        public NumericOrBitwiseExpressionBuilder<T> numeric(int num) {
            return new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(num), fnobeb -> {
                value = fnobeb;
                return converter.apply(this);
            });
        }

        public T numeric(int num, Consumer<NumericOrBitwiseExpressionBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            NumericOrBitwiseExpressionBuilder<Void> result = new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(num), fnobeb -> {
                value = fnobeb;
                h.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return h.get("Numeric expression builder not completed");
        }

        public NumericOrBitwiseExpressionBuilder<T> numeric(byte num) {
            return new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(num), fnobeb -> {
                value = fnobeb;
                return converter.apply(this);
            });
        }

        public T numeric(byte num, Consumer<NumericOrBitwiseExpressionBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            NumericOrBitwiseExpressionBuilder<Void> result = new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(num), fnobeb -> {
                value = fnobeb;
                h.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return h.get("Numeric expression builder not completed");
        }

        public NumericOrBitwiseExpressionBuilder<T> numeric(long num) {
            return new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(num), fnobeb -> {
                value = fnobeb;
                return converter.apply(this);
            });
        }

        public T numeric(long num, Consumer<NumericOrBitwiseExpressionBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            NumericOrBitwiseExpressionBuilder<Void> result = new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(num), fnobeb -> {
                value = fnobeb;
                h.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return h.get("Numeric expression builder not completed");
        }

        public NumericOrBitwiseExpressionBuilder<T> numeric(short num) {
            return new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(num), fnobeb -> {
                value = fnobeb;
                return converter.apply(this);
            });
        }

        public T numeric(short num, Consumer<NumericOrBitwiseExpressionBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            NumericOrBitwiseExpressionBuilder<Void> result = new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(num), fnobeb -> {
                value = fnobeb;
                h.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return h.get("Numeric expression builder not completed");
        }

        public NumericExpressionBuilder<T> numeric(double num) {
            return new NumericExpressionBuilder<>(friendlyNumber(num), fnobeb -> {
                value = fnobeb;
                return converter.apply(this);
            });
        }

        public T numeric(double num, Consumer<NumericExpressionBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            NumericExpressionBuilder<Void> result = new NumericExpressionBuilder<>(friendlyNumber(num), fnobeb -> {
                value = fnobeb;
                h.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return h.get("Numeric expression builder not completed");
        }

        public NumericExpressionBuilder<T> numeric(float num) {
            return new NumericExpressionBuilder<>(friendlyNumber(num), fnobeb -> {
                value = fnobeb;
                return converter.apply(this);
            });
        }

        public T numeric(float num, Consumer<NumericExpressionBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            NumericExpressionBuilder<Void> result = new NumericExpressionBuilder<>(friendlyNumber(num), fnobeb -> {
                value = fnobeb;
                h.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return h.get("Numeric expression builder not completed");
        }

        public T numeric(Consumer<? super ValueExpressionBuilder<? extends NumericOrBitwiseExpressionBuilder<?>>> c) {
            Holder<T> h = new Holder<>();
            Holder<NumericOrBitwiseExpressionBuilder<Void>> hh = new Holder<>();
            ValueExpressionBuilder<NumericOrBitwiseExpressionBuilder<Void>> result
                    = new ValueExpressionBuilder<>(veb -> {
                        h.set(converter.apply(this));
                        NumericOrBitwiseExpressionBuilder<Void> res = new NumericOrBitwiseExpressionBuilder<>(veb, nobeb -> {
                            value = nobeb;
                            h.set(converter.apply(this));
                            return null;
                        });
                        hh.set(res);
                        return res;
                    });
            c.accept(result);
            return h.get("Value or numeric expression not completed");
        }

        public ValueExpressionBuilder<NumericOrBitwiseExpressionBuilder<T>> numeric() {
            return new ValueExpressionBuilder<>(veb -> {
                return new NumericOrBitwiseExpressionBuilder<>(veb, nobeb -> {
                    value = nobeb;
                    return converter.apply(this);
                });
            });
        }

        /**
         * Create a ternary expression, e.g.
         * <code>someBooleanExpression ? a : b</code>, starting with the test
         * condition, then the value if the test is true, then the value if the
         * test is false.
         *
         * @return A condition builder for setting up the test
         */
        public ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<T>>> ternary() {
            ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<T>>> b
                    = new ConditionBuilder<>(cb -> {
                        TernaryBuilder<T> tb = new TernaryBuilder<>(tbb -> {
                            value = tbb;
                            return converter.apply(this);
                        }, cb);
                        return tb.ifTrue();
                    });
            return b;
        }

        public T toTernary(Consumer<ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<Void>>>> c) {
            Holder<T> holder = new Holder<>();
            ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<Void>>> b
                    = new ConditionBuilder<>(cb -> {
                        TernaryBuilder<Void> tb = new TernaryBuilder<>(tbb -> {
                            value = tbb;
                            holder.set(converter.apply(this));
                            return null;
                        }, cb);
                        return tb.ifTrue();
                    });
            c.accept(b);
            return holder.get("Ternary condition not completed");
        }

        public NewBuilder<T> toNewInstance() {
            return new NewBuilder<>(nb -> {
                this.value = nb;
                return converter.apply(this);
            });
        }

        public NewBuilder<T> toNewInstanceWithArgument(String s) {
            return toNewInstance().withArgument(s);
        }

        public T toNewInstance(Consumer<? super NewBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            NewBuilder<Void> result = new NewBuilder<>(nb -> {
                this.value = nb;
                holder.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return holder.get("New instance builder not completed - call ofType()");
        }

        public ArrayLiteralBuilder<T> toArrayLiteral(String type) {
            return new ArrayLiteralBuilder<>(alb -> {
                this.value = alb;
                return converter.apply(this);
            }, type);
        }

        public T toArrayLiteral(String type, Consumer<ArrayLiteralBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            ArrayLiteralBuilder<Void> res = new ArrayLiteralBuilder<>(alb -> {
                this.value = alb;
                holder.set(converter.apply(this));
                return null;
            }, type);
            c.accept(res);
            holder.ifUnset(res::closeArrayLiteral);
            return holder.get("Array literal not completed");
        }

        public AnnotationBuilder<T> annotation(String type) {
            return new AnnotationBuilder<>(ab -> {
                value = ab;
                return converter.apply(this);
            }, type);
        }

        public T annotation(String type, Consumer<? super AnnotationBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            AnnotationBuilder<?> bldr = new AnnotationBuilder<Void>(ab -> {
                value = ab;
                holder.set(converter.apply(this));
                return null;
            }, type);
            c.accept(bldr);
            holder.ifUnset(bldr::closeAnnotation);
            return holder.get("AnnotationBuilder was not completed");
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            if (value == null) {
                throw new IllegalStateException("No value", creation);
            }
            if (newline) {
                lines.onNewLine();
            }
            if (parenthesized) {
                if (cast != null) {
                    cast.generateInto(lines);
                }
                lines.parens(value::generateInto);
            } else {
                if (cast != null) {
                    cast.generateInto(lines);
                }
                value.generateInto(lines);
            }
        }
    }

    static class TernaryBuilder<T> implements CodeGenerator {

        private final Function<TernaryBuilder<T>, T> converter;

        private final CodeGenerator condition;
        private CodeGenerator trueSide;
        private CodeGenerator falseSide;

        TernaryBuilder(Function<TernaryBuilder<T>, T> converter, CodeGenerator condition) {
            this.converter = converter;
            this.condition = condition;
        }

        public ValueExpressionBuilder<ValueExpressionBuilder<T>> ifTrue() {
            ValueExpressionBuilder<ValueExpressionBuilder<T>> first
                    = new ValueExpressionBuilder<>(veb -> {
                        trueSide = veb;
                        return new ValueExpressionBuilder<>(fal -> {
                            falseSide = fal;
                            return converter.apply(this);
                        });
                    });
            return first;
        }

        @Override
        public void generateInto(LinesBuilder lb) {
//            lines.wrappable(lb -> {
            condition.generateInto(lb);
            lb.indent(lbb -> {
                lb.onNewLine();
                lbb.word("?");
                trueSide.generateInto(lbb);
                lbb.onNewLine();
                lbb.word(":");
                falseSide.generateInto(lbb);
            });
//            });
        }
    }

    public static class BlockBuilder<T> extends BlockBuilderBase<T, BlockBuilder<T>, T> {

        BlockBuilder(Function<? super BlockBuilder<T>, T> converter, boolean openBlock) {
            super(converter, openBlock);
        }

        @Override
        T x() {
            return converter.apply(this);
        }

        @Override
        public T endBlock() {
            return super.endBlock();
        }
    }

    public static class WhileBuilder<T> extends BlockBuilderBase<T, WhileBuilder<T>, WhileBuilder<T>> {

        private final boolean tailCondition;
        private CodeGenerator condition;

        WhileBuilder(boolean tail, Function<? super BlockBuilderBase<T, WhileBuilder<T>, WhileBuilder<T>>, T> converter) {
            super(converter, true);
            this.tailCondition = tail;
        }

        @Override
        WhileBuilder<T> x() {
            return this;
        }

        public ConditionBuilder<T> underCondition() {
            return new ConditionBuilder<T>(bb -> {
                this.condition = bb;
                return converter.apply(this);
            });
        }

        public WhileBuilder<T> continuing() {
            statements.add(new StatementWrapper("continue"));
            return this;
        }

        public WhileBuilder<T> breaking() {
            statements.add(new StatementWrapper("break"));
            return this;
        }

        public WhileBuilder<T> breaking(String label) {
            statements.add(new StatementWrapper("break", notNull("label", label)));
            return this;
        }

        public T underCondition(Consumer<ConditionBuilder<?>> c) {
            Holder<T> hold = new Holder<>();
            ConditionBuilder<Void> result = new ConditionBuilder<>(cb -> {
                hold.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return hold.get("Condition not applied - call underCondition() to close while builder");
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            if (tailCondition) {
                lines.onNewLine().word("do");
                super.generateInto(lines);
                lines.word("while");
                lines.parens(condition::generateInto);
                lines.onNewLine();
            } else {
                lines.onNewLine().word("while");
                lines.parens(condition::generateInto);
                super.generateInto(lines);
                lines.onNewLine();
            }
        }
    }

    private void addDebugStackTraceElementComment() {
        if (CONTEXT.get() != null) {
            if (CONTEXT.get().generateDebugCode) {
                Exception ex = new Exception();
                StackTraceElement[] els = ex.getStackTrace();
                if (els != null && els.length > 0) {
                    String pkg = ClassBuilder.class.getPackage().getName();
                    for (StackTraceElement e : els) {
                        if (!e.getClassName().startsWith(pkg)) {
//                                lineComment(stripPackage(e));
                            this.members.add(new LineComment(stripPackage(e), false));
                            break;
                        }
                    }
                }
            }
        }
    }

    static String stripPackage(StackTraceElement el) {
        // Avoids generating gargantuan comments
        String s = el.toString();
        int ix = s.indexOf('(');
        if (ix < 0) { // ??
            return s;
        }
        int start = 0;
        for (int i = 0; i < ix; i++) {
            if (s.charAt(i) == '.') {
                start = i + 1;
            }
        }
        return s.substring(start);
    }

    /**
     * Base class for code-block builders, which include basic code blocks such
     * as method bodies, and subtypes which add purpose-specific methods such as
     * <code>TryBuilder</code>, <code>IfBuilder</code>,
     * <code>ElseBuilder</code>.
     * <p>
     * Some subtypes vary the return type of some methods based on their purpose
     * - some "return" methods make sense, in a method body, to be treated as a
     * signal to close the block and add it to its parent class. Others, such as
     * <code>IfBuilder</code> would cause any return statement to make it
     * impossible to add further else clauses, so return methods on that return
     * themselves.
     * </p>
     *
     * @param <T> The return type when this block is built - the parent builder.
     * @param <B> Self-type
     * @param <X> The return type for methods which, under some circumstances
     * should close the builder, where which that does not make sense for all
     * subtypes.
     */
    public static abstract class BlockBuilderBase<T, B extends BlockBuilderBase<T, B, X>, X> extends CodeGeneratorBase {

        final List<CodeGenerator> statements = new LinkedList<>();
        final Function<? super B, T> converter;
        private final boolean openBlock;
        private BiConsumer<? super B, ? super CodeGenerator> probe;

        BlockBuilderBase(Function<? super B, T> converter, boolean openBlock) {
            this.converter = converter;
            this.openBlock = openBlock;
            addDebugStackTraceElementComment();
        }

        abstract X x();

        private void addDebugStackTraceElementComment() {
            if (CONTEXT.get() != null) {
                if (CONTEXT.get().generateDebugCode) {
                    Exception ex = new Exception();
                    StackTraceElement[] els = ex.getStackTrace();
                    if (els != null && els.length > 0) {
                        String pkg = ClassBuilder.class.getPackage().getName();
                        for (StackTraceElement e : els) {
                            if (!e.getClassName().startsWith(pkg)) {
                                lineComment(stripPackage(e));
                                break;
                            }
                        }
                    }
                }
            }
        }

        B probeAdditionsWith(BiConsumer<? super B, ? super CodeGenerator> c) {
            // for debugging duplicate adds
            this.probe = c;
            return cast();
        }

        void addStatement(CodeGenerator bb) {
            statements.add(bb);
            if (probe != null) {
                probe.accept(cast(), bb);
            }
        }

        public WhileBuilder<B> whileLoop() {
            return new WhileBuilder<>(false, wb -> {
                statements.add(wb);
                return cast();
            });
        }

        public WhileBuilder<B> doWhile() {
            return new WhileBuilder<>(true, wb -> {
                statements.add(wb);
                return cast();
            });
        }

        public B whileLoop(Consumer<WhileBuilder<?>> c) {
            Holder<B> hold = new Holder<>();
            WhileBuilder<Void> result = new WhileBuilder<>(false, wb -> {
                statements.add(wb);
                hold.set(cast());
                return null;
            });
            c.accept(result);
            return hold.get("While loop not completed - call underCondition() before exiting the lambda");
        }

        public B doWhile(Consumer<WhileBuilder<?>> c) {
            Holder<B> hold = new Holder<>();
            WhileBuilder<Void> result = new WhileBuilder<>(true, wb -> {
                statements.add(wb);
                hold.set(cast());
                return null;
            });
            c.accept(result);
            return hold.get("While loop not completed - call underCondition() before exiting the lambda");
        }

        public ValueExpressionBuilder<T> returningValue() {
            return new ValueExpressionBuilder<>(veb -> {
                addStatement(new ReturnStatement(veb));
                return endBlock();
            });
        }

        public T returningValue(Consumer<ValueExpressionBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            ValueExpressionBuilder<Void> result = new ValueExpressionBuilder<>(veb -> {
                addDebugStackTraceElementComment();
                addStatement(new ReturnStatement(veb));
                holder.set(endBlock());
                return null;
            });
            c.accept(result);
            return holder.get("Value expression builder not completed");
        }

        public NewBuilder<T> returningNew() {
            return new NewBuilder<>(nb -> {
                addDebugStackTraceElementComment();
                addStatement(new ReturnStatement(nb));
                return endBlock();
            });
        }

        public T returningNew(Consumer<? super NewBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            NewBuilder<Void> result = new NewBuilder<>(nb -> {
                addDebugStackTraceElementComment();
                addStatement(new ReturnStatement(nb));
                h.set(endBlock());
                return null;
            });
            c.accept(result);
            return h.get("Builder for new instance to return not completed");
        }

        public T returningLambda(Consumer<? super LambdaBuilder<Void>> c) {
            Holder<T> h = new Holder<>();
            boolean[] done = new boolean[1];
            LambdaBuilder<Void> result = new LambdaBuilder<>(lb -> {
                addDebugStackTraceElementComment();
                addStatement(new ReturnStatement(lb));
                done[0] = true;
                h.set(endBlock());
                return null;
            });
            c.accept(result);
            if (!done[0]) {
                h.set(endBlock());
//                result.
            }
            return h.get("Lambda builder not completed");
        }

        public LambdaBuilder<T> returningLambda() {
            LambdaBuilder<T> result = new LambdaBuilder<>(lb -> {
                addDebugStackTraceElementComment();
                addStatement(new ReturnStatement(lb));
                return endBlock();
            });
            return result;
        }

        public NewBuilder<MethodBuilder<T>> invokeConstructor() {
            NewBuilder<MethodBuilder<T>> result = new NewBuilder<>(nb -> {
                addDebugStackTraceElementComment();
                addStatement(new StatementWrapper(nb));
                return null;
            });
            return result;
        }

        public B invokeConstructor(Consumer<? super NewBuilder<?>> c) {
            Holder<B> h = new Holder<>();
            NewBuilder<?> result = new NewBuilder<>(nb -> {
                addDebugStackTraceElementComment();
                addStatement(new StatementWrapper(nb));
                h.set(cast());
                return null;
            });
            c.accept(result);
            return h.get("New builder not completed - call onType()");
        }

        public IfBuilder<B> ifNotNull(String expression) {
            addDebugStackTraceElementComment();
            return BlockBuilderBase.this.iff().variable(expression).notEquals().expression("null").endCondition();
        }

        public B ifNotNull(String expression, Consumer<? super IfBuilder<?>> c) {
            addDebugStackTraceElementComment();
            return iff(cb -> {
                cb.variable(expression).notEquals().expression("null").endCondition(ib2 -> {
                    c.accept(ib2);
                });
            });
        }

        public IfBuilder<B> ifNull(String expression) {
            addDebugStackTraceElementComment();
            return BlockBuilderBase.this.iff().variable(expression).equals().expression("null").endCondition();
        }

        public B ifNull(String expression, Consumer<? super IfBuilder<?>> c) {
            addDebugStackTraceElementComment();
            return iff(cb -> {
                cb.variable(expression).equals().expression("null").endCondition(ib2 -> {
                    c.accept(ib2);
                });
            });
        }

        public B iff(Consumer<? super ConditionBuilder<? extends IfBuilder<?>>> c) {
            Holder<B> h = new Holder<>();
            Holder<IfBuilder<Void>> ibh = new Holder<>();
            ConditionBuilder<IfBuilder<Void>> result = new ConditionBuilder<>(fcb -> {
                IfBuilder<Void> ib2 = new IfBuilder<>(ib -> {
                    addStatement(ib);
                    h.set(cast());
                    return null;
                }, fcb);
                ibh.set(ib2);
                return ib2;
            });
            c.accept(result);
            if (!h.isSet()) {
                if (ibh.isSet()) {
                    ibh.get().endIf();
                }
            }
            return h.get("If clause or condition not completed within closure of " + c);
        }

        public ConditionBuilder<IfBuilder<B>> iff() {
            addDebugStackTraceElementComment();
            ConditionBuilder<IfBuilder<B>> result = new ConditionBuilder<>(fcb -> {
                IfBuilder<B> ib2 = new IfBuilder<>(ib -> {
                    addStatement(ib);
                    return cast();
                }, fcb);
                return ib2;
            });
            return result;
        }

        @SuppressWarnings("unchecked")
        private B cast() {
            return (B) this;
        }

        public B conditionally(boolean test, Consumer<? super B> cb) {
            if (test) {
                addDebugStackTraceElementComment();
                cb.accept(cast());
            }
            return cast();
        }

        /**
         * Add a println statement which will only be included in source if
         * debug log statements was set enabled on the parent class builder
         * before calling this method.
         *
         * @param line A string literal
         * @return this
         */
        public B debugLog(String line) {
            if ((CONTEXT.get() != null && CONTEXT.get().generateDebugCode)) {
                invoke("println").withStringLiteral(line).on("System.out");
            }
            return cast();
        }

        /**
         * Log an exception with the default level of
         * java.util.logging.Level.SEVERE.
         *
         * @param exceptionName The name of the variable holding the exception
         * @return this
         */
        public B logException(String exceptionName) {
            return logException(Level.SEVERE, exceptionName, null);
        }

        /**
         * Log an exception with the passed logging level and no message.
         *
         * @param level the logging level
         * @param exceptionName The name of the variable holding the exception
         * @return this
         */
        public B logException(Level level, String exceptionName) {
            return logException(level, exceptionName, null);
        }

        /**
         * Log an exception with the default level of
         * java.util.logging.Level.SEVERE.
         *
         * @param exceptionName The name of the variable holding the exception
         * @param msg The message to log with the exception
         * @return this
         */
        public B logException(String exceptionName, String msg) {
            return logException(Level.SEVERE, exceptionName, msg);
        }

        /**
         * Log an exception.
         *
         * @param level The level
         * @param exceptionName The variable name holding the exception instance
         * @param msg The message
         * @return this
         */
        public B logException(Level level, String exceptionName, String msg) {
            ClassBuilder<?> ctx = CONTEXT.get();
            if (ctx != null) {
                ctx.logger();
                ctx.importing(Level.class.getName());
            }
            if (exceptionName == null) {
                exceptionName = "thrown";
            }
            InvocationBuilder<B> inv = invoke("log")
                    .withArgument("Level." + level.getName());

            if (msg == null) {
                inv.withArgument("null");
            } else {
                inv.withStringLiteral(msg);
            }
            return inv.withArgument(exceptionName).on("LOGGER");
        }

        public B log(String line) {
            if (CONTEXT.get() != null) {
                CONTEXT.get().logger();
            }
            return invoke("log")
                    .withArgument("Level.INFO")
                    .withStringLiteral(line).on("LOGGER");
        }

        public B log(Level level, Consumer<? super LogLineBuilder<?>> c) {
            boolean[] built = new boolean[1];
            LogLineBuilder<B> b = log(level, built);
            c.accept(b);
            if (!built[0]) {
                throw new IllegalStateException("logging(line) not called - LogLineBuilder not completed");
            }
            return cast();
        }

        public LogLineBuilder<B> log(Level level) {
            return log(level, new boolean[1]);
        }

        private LogLineBuilder<B> log(Level level, boolean[] built) {
            if (CONTEXT.get() != null) {
                CONTEXT.get().logger();
            }
            return new LogLineBuilder<>(llb -> {
                addStatement(llb);
                return cast();
            }, level);
        }

        public static final class LogLineBuilder<T> implements CodeGenerator {

            private final Function<LogLineBuilder<T>, T> converter;
            private String line;
            private List<CodeGenerator> arguments = new ArrayList<>(5);
            private final Level level;

            public LogLineBuilder(Function<LogLineBuilder<T>, T> converter, Level level) {
                this.converter = converter;
                this.level = level;
            }

            public LogLineBuilder<T> argument(String arg) {
                arguments.add(new Adhoc(arg));
                return this;
            }

            public LogLineBuilder<T> stringLiteral(String arg) {
                arguments.add(new Adhoc(LinesBuilder.stringLiteral(arg)));
                return this;
            }

            public LogLineBuilder<T> argument(Number arg) {
                return argument(arg.toString());
            }

            public LogLineBuilder<T> argument(boolean arg) {
                return argument(Boolean.toString(arg));
            }

            public T logging(String line) {
                assert line != null : "line null";
                this.line = line;
                for (int i = 0; i < arguments.size(); i++) {
                    String searchFor = "{" + i + "}";
                    if (!line.contains(searchFor)) {
                        throw new IllegalArgumentException("At least "
                                + arguments.size() + " logger arguments "
                                + "present, but the template has no "
                                + "occurrence of " + searchFor + " - that "
                                + "argument will not be logged");
                    }
                }
                for (int i = arguments.size(); i < 100; i++) {
                    String searchFor = "{" + i + "}";
                    if (line.contains(searchFor)) {
                        throw new IllegalArgumentException("Line contains a "
                                + "string template " + searchFor + " but "
                                + "only " + arguments.size()
                                + " arguments are present");
                    }
                }
                return converter.apply(this);
            }

            @Override
            public void generateInto(LinesBuilder lines) {
                lines.statement(sb -> {
                    sb.word("LOGGER.log");
                    sb.delimit('(', ')', llb -> {
                        llb.word("Level").appendRaw(".").appendRaw(level.getName()).appendRaw(",");
                        llb.word(LinesBuilder.stringLiteral(line));
                        switch (arguments.size()) {
                            case 0:
                                break;
                            case 1:
                                llb.appendRaw(",");
                                arguments.get(0).generateInto(llb);
                                break;
                            default:
                                llb.appendRaw(",");
                                llb.word("new").word("Object[]");
                                llb.delimit('{', '}', db -> {
                                    for (Iterator<CodeGenerator> it = arguments.iterator(); it.hasNext();) {
                                        it.next().generateInto(db);
                                        if (it.hasNext()) {
                                            db.appendRaw(",");
                                        }
                                    }
                                });
                        }
                    });
                });
            }

        }

        public B log(String line, Level level, Object... args) {
            if (CONTEXT.get() != null) {
                CONTEXT.get().logger();
            }
            if (args.length == 0) {
                return invoke("log")
                        .withArgument("Level." + level.getName())
                        .withStringLiteral(line).on("LOGGER");
            } else {
                ArrayValueBuilder<InvocationBuilder<B>> ab
                        = invoke("log").withArgument("Level." + level.getName())
                                .withStringLiteral(line).withNewArrayArgument("Object");
                for (Object o : args) {
                    if (o != null && o.getClass().isArray()) {
                        throw new IllegalArgumentException("Element of array "
                                + "is an actual Java array - this cannot "
                                + "possibly be what you want, as it will log as"
                                + "L[" + o.getClass().getName());
                    }
                    ab.expression(Objects.toString(o));
                }
                ab.closeArray().on("LOGGER");
            }
            return cast();
        }

        public LambdaBuilder<B> lambda() {
            return lambda(new boolean[1]);
        }

        public B blankLine() {
            addStatement(new DoubleNewline());
            return cast();
        }

        public B lambda(Consumer<? super LambdaBuilder<?>> c) {
            boolean[] built = new boolean[1];
            LambdaBuilder<B> bldr = lambda(built);
            c.accept(bldr);
            if (!built[0]) {
                throw new IllegalStateException("closeBlock() not called on lambda - not added");
            }
            return cast();
        }

        private LambdaBuilder<B> lambda(boolean[] built) {
            return new LambdaBuilder<>(lb -> {
                addStatement(lb);
                built[0] = true;
                return cast();
            });
        }

        public BlockBuilder<B> synchronize() {
            return synchronizeOn("this");
        }

        public BlockBuilder<B> synchronizeOn(String what) {
            return synchronizeOn(what, new boolean[1]);
        }

        public B synchronize(String what, Consumer<? super BlockBuilder<B>> c) {
            return synchronizeOn("this", c);
        }

        public B synchronizeOn(String what, Consumer<? super BlockBuilder<B>> c) {
            boolean[] built = new boolean[1];
            BlockBuilder<B> bldr = synchronizeOn(what, built);
            c.accept(bldr);
            if (!built[0]) {
                bldr.endBlock();
            }
            return cast();
        }

        private BlockBuilder<B> synchronizeOn(String what, boolean[] built) {
            SynchronizedBlockBuilder<B> res = new SynchronizedBlockBuilder<>(sb -> {
                addStatement(sb);
                return cast();
            }, what);
            return res.block();
        }

        public SimpleLoopBuilder<B> simpleLoop(String type, String loopVarName) {
            return simpleLoop(type, loopVarName, new boolean[1]);
        }

        private SimpleLoopBuilder<B> simpleLoop(String type, String loopVarName, boolean[] built) {
            return new SimpleLoopBuilder<>(slb -> {
                if (!built[0]) {
                    addStatement(slb);
                    built[0] = true;
                }
                return cast();
            }, type, loopVarName);
        }

        public B simpleLoop(String type, String loopVarName, Consumer<? super SimpleLoopBuilder<?>> consumer) {
            boolean[] built = new boolean[1];
            SimpleLoopBuilder<B> bldr = simpleLoop(type, loopVarName, built);
            consumer.accept(bldr);
            if (!built[0]) {
                throw new IllegalStateException("SimpleLoopBuilder was not built and added in consumer");
            }
            return cast();
        }

        public B forVar(String name, Consumer<? super ForVarBuilder<?>> consumer) {
            boolean[] built = new boolean[1];
            ForVarBuilder<B> b = forVar(name, built);
            consumer.accept(b);
            if (!built[0]) {
                throw new IllegalStateException("ForVarBuilder was not closed and added in consumer");
            }
            return cast();
        }

        public ForVarBuilder<B> forVar(String name) {
            return forVar(name, new boolean[1]);
        }

        private ForVarBuilder<B> forVar(String name, boolean[] built) {
            return new ForVarBuilder<>(fvb -> {
                built[0] = true;
                addStatement(fvb);
                return cast();
            }, name);
        }

        private void writeStatements(LinesBuilder into) {
            if (statements.isEmpty()) {
                into.onNewLine().appendRaw("// do nothing");
                into.onNewLine();
                return;
            }
            for (CodeGenerator bb : statements) {
                bb.generateInto(into);
            }
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            if (openBlock) {
                lines.block(lb -> {
                    writeStatements(lb);
                });
            } else {
                writeStatements(lines);
            }
        }

        public B lineComment(String cmt) {
            return lineComment(cmt, false);
        }

        public B lineComment(String cmt, boolean trailing) {
            addStatement(new LineComment(cmt, trailing));
            return cast();
        }

        public TryBuilder<B> trying() {
            TryBuilder<B> tb = new TryBuilder<>(b -> {
                addDebugStackTraceElementComment();
                addStatement(b);
                return cast();
            });
            return tb;
        }

        public B trying(Consumer<? super TryBuilder<?>> cb) {
            boolean[] done = new boolean[1];
            TryBuilder<Void> tb = new TryBuilder<>(b -> {
                addDebugStackTraceElementComment();
                addStatement(b);
                done[0] = true;
                return null;
            });
            cb.accept(tb);
            if (!done[0]) {
                Exception ex = new Exception();
                BlockBuilder<Void> fin = tb.fynalli();
                fin.lineComment("Try builder did not have any catch or finally blocks added to it, so this");
                fin.lineComment("empty catch block was generated.  This is probably a bug in your annotation");
                fin.lineComment("processor");
                fin.lineComment("");
                for (StackTraceElement st : ex.getStackTrace()) {
                    fin.lineComment("    " + st);
                }
                fin.endBlock();
                assert done[0];
            }
            return cast();
        }

        public B invoke(String method, Consumer<? super InvocationBuilder<?>> c) {
            boolean[] closed = new boolean[1];
            InvocationBuilder<Void> ib = new InvocationBuilder<>(b -> {
                closed[0] = true;
                addDebugStackTraceElementComment();
                addStatement(new StatementWrapper(b));
                return null;
            }, method);
            c.accept(ib);
            if (!closed[0]) {
                throw new IllegalStateException("InvocationBuilder.on() never called - "
                        + "will not be added");
            }
            return cast();
        }

        public T andThrow(String what) {
            addStatement(new StatementWrapper(new Composite(new Adhoc("throw"), new Adhoc(checkIdentifier(notNull("what", what))))));
            return endBlock();
        }

        public NewBuilder<T> andThrow() {
            NewBuilder<T> result = new NewBuilder<>(nb -> {
                addStatement(new StatementWrapper(new Composite(new Adhoc("throw"), nb)));
                return endBlock();
            });
            return result;
        }

        public B andThrow(Consumer<? super NewBuilder<?>> c) {
            Holder<B> h = new Holder<>();
            NewBuilder<Void> result = new NewBuilder<>(nb -> {
                addStatement(new StatementWrapper(new Composite(new Adhoc("throw"), nb)));
                h.set(cast());
                return null;
            });
            c.accept(result);
            if (!h.isSet()) {
                result.ofType("Exception");
            }
            return h.get("NewBuilder not completed");
        }

        public AssignmentBuilder<B> assign(String variable) {
            return new AssignmentBuilder<>(b -> {
                addStatement(new StatementWrapper(b));
                return cast();
            }, new Adhoc(variable));
        }

        public B assignArrayElement(Consumer<ArrayElementsBuilder<AssignmentBuilder<Void>>> c) {
            Holder<B> hold = new Holder<>();
            ArrayElementsBuilder<AssignmentBuilder<Void>> bldr
                    = new ArrayElementsBuilder<>(aedb -> {
                        return new AssignmentBuilder<>(assig -> {
                            addStatement(new StatementWrapper(assig));
                            hold.set(cast());
                            return null;
                        }, aedb);
                    });
            c.accept(bldr);
            return hold.get("Array element assignment not completed");
        }

        public AssignmentBuilder<B> assignArrayElements(Object exp1, Object... more) {
            ArrayElementsBuilder<AssignmentBuilder<B>> b = assignArrayElement();
            b.element(exp1.toString());
            for (Object o : more) {
                b.element(o.toString());
            }
            return b.closeArrayElements();
        }

        public ArrayElementsBuilder<AssignmentBuilder<B>> assignArrayElement() {
            return new ArrayElementsBuilder<>(aedb -> {
                return new AssignmentBuilder<>(assig -> {
                    addStatement(new StatementWrapper(assig));
                    return cast();
                }, aedb);
            });
        }

        public ArrayElementsBuilder<AssignmentBuilder<B>> assignArrayElement(Object first) {
            return new ArrayElementsBuilder<AssignmentBuilder<B>>(aedb -> {
                return new AssignmentBuilder<>(assig -> {
                    addStatement(new StatementWrapper(assig));
                    return cast();
                }, aedb);
            }).element(first.toString());
        }

        public B assign(String variable, Consumer<? super AssignmentBuilder<?>> c) {
            boolean[] closed = new boolean[1];
            AssignmentBuilder<Void> ab = new AssignmentBuilder<>(b -> {
                closed[0] = true;
                addStatement(new Composite(b, new Adhoc(";")));
                return null;
            }, new Adhoc(variable));
            c.accept(ab);
            if (!closed[0]) {
                throw new IllegalStateException(".to() not called on AssignmentBuilder - "
                        + "statement not complete");
            }
            return cast();
        }

        public B asserting(String expression) {
            if (expression == null || expression.trim().isEmpty()) {
                throw new IllegalStateException("Null or empty expression");
            }
            if (expression.trim().charAt(expression.length() - 1) != ';') {
                expression = expression.trim() + ';';
            }
            addStatement(new Composite(new Adhoc("assert", true), new Adhoc(expression, true)));
            return cast();
        }

        public B assertingNotNull(String expression) {
            addStatement(new StatementWrapper(new Composite(new Adhoc("assert", true), new Adhoc(expression, true),
                    new Adhoc("!=", true), new Adhoc("null", true))));
            return cast();
        }

        public ValueExpressionBuilder<AssertionBuilder<B>> assertingNotNull() {
            AssertionBuilder<B> res = new AssertionBuilder<>(ab -> {
                assert !statements.contains(ab) : " Adding twice: " + ab;
                addStatement(ab);
                return cast();
            }, false);
            return res.assertingNotNull();
        }

        public B assertingNotNull(Consumer<ValueExpressionBuilder<AssertionBuilder<?>>> c) {
            Holder<B> hold = new Holder<>();
            Holder<AssertionBuilder<Void>> secondary = new Holder<>();
            ValueExpressionBuilder<AssertionBuilder<?>> bldr = new ValueExpressionBuilder<>(veb -> {
                AssertionBuilder<Void> result = new AssertionBuilder<>(ab -> {
                    assert !statements.contains(ab) : " Adding twice: " + ab;
                    addStatement(ab);
                    hold.set(cast());
                    return null;
                }, true);
                secondary.set(result);
                result.assertThat = new Composite(veb, new Adhoc("!=", true), new Adhoc("null", true));
                return result;
            });
            c.accept(bldr);
            if (secondary.get() != null) {
                if (!secondary.get().built) {
                    secondary.get().build();
                }
            }
            return hold.get("Value or assertion builder not completed");
        }

        public ConditionBuilder<AssertionBuilder<B>> asserting() {
            return new ConditionBuilder<>(cb -> {
                AssertionBuilder<B> bldr = new AssertionBuilder<>(ab -> {
                    assert !statements.contains(ab) : " Adding twice: " + ab;
                    addStatement(ab);
                    return cast();
                }, false);
                bldr.assertThat = cb;
                return bldr;
            });
        }

        public B asserting(Consumer<ConditionBuilder<AssertionBuilder<?>>> c) {
            Holder<B> hold = new Holder<>();
            Holder<AssertionBuilder<Void>> secondary = new Holder<>();
            ConditionBuilder<AssertionBuilder<?>> bldr = new ConditionBuilder<>(cb -> {
                AssertionBuilder<Void> second = new AssertionBuilder<>(ab -> {
                    assert !statements.contains(ab) : " Adding twice: " + ab;
                    addStatement(ab);
                    hold.set(cast());
                    return null;
                }, true);
                second.assertThat = cb;
                secondary.set(second);
                return second;
            });
            c.accept(bldr);
            if (!hold.isSet()) {
                if (secondary.isSet()) {
                    if (!secondary.get().built) {
                        secondary.get().build();
                    }
                }
            }
            return hold.get("Assertion builder not completed");
        }

        public InvocationBuilder<B> invoke(String method) {
            return new InvocationBuilder<>(ib -> {
                addDebugStackTraceElementComment();
                addStatement(new Statement(ib));
                return cast();
            }, method);
        }

        public BlockBuilder<B> block() {
            return new BlockBuilder<>(bk -> {
                addStatement(bk);
                return cast();
            }, true);
        }

        public B breaking() {
            return statement("break");
        }

        public B continuing() {
            return statement("continue");
        }

        public B statement(String stmt) {
            if (stmt.endsWith(";")) {
                stmt = stmt.substring(0, stmt.length() - 1);
            }
            addStatement(new OneStatement(stmt));
            return cast();
        }

        public FieldReferenceBuilder<B> returningField(String name) {
            return new FieldReferenceBuilder<>(name, fb -> {
                addDebugStackTraceElementComment();
                addStatement(new ReturnStatement(fb));
                return cast();
            });
        }

        public B returningField(String name, Consumer<FieldReferenceBuilder<?>> c) {
            Holder<B> holder = new Holder<>();
            FieldReferenceBuilder<Void> result = new FieldReferenceBuilder<>(name, frb -> {
                addDebugStackTraceElementComment();
                addStatement(new ReturnStatement(frb));
                holder.set(cast());
                return null;
            });
            c.accept(result);
            return holder.get("Field reference not completed");
        }

        public B returning(String s) {
            addDebugStackTraceElementComment();
            addStatement(new ReturnStatement(new Adhoc(s)));
            return cast();
        }

        /**
         * Convenience method for returning <code>this</code>.
         *
         * @return this
         */
        public B returningThis() {
            addDebugStackTraceElementComment();
            addStatement(new ReturnStatement(new Adhoc("this")));
            return cast();
        }

        /**
         * Adds a return statement to this block <i>without closing it</i> -
         * this is needed by IfBuilder and friends, where you may still want to
         * add an else clause, so you want to get back this instance, not the
         * thing its build method creates.
         *
         * @return A NewBuilder
         */
        public NewBuilder<B> conditionallyReturningNew() {
            return new NewBuilder<>(nb -> {
                addDebugStackTraceElementComment();
                addStatement(new ReturnStatement(nb));
                return cast();
            });
        }

        /**
         * Adds a return statement to this block <i>without closing it</i> -
         * this is needed by IfBuilder and friends, where you may still want to
         * add an else clause, so you want to get back this instance, not the
         * thing its build method creates.
         *
         * @return A NewBuilder
         */
        public B conditionallyReturningNew(Consumer<NewBuilder<?>> c) {
            Holder<B> hold = new Holder<>();
            NewBuilder<Void> result = new NewBuilder<>(nb -> {
                addDebugStackTraceElementComment();
                addStatement(new ReturnStatement(nb));
                hold.set(cast());
                return null;
            });
            c.accept(result);
            return hold.get("New builder not completed");
        }

        public ConditionBuilder<B> returningBoolean() {
            return new ConditionBuilder<B>(cb -> {
                addDebugStackTraceElementComment();
                addStatement(new ReturnStatement(cb));
                return cast();
            });
        }

        public B returning(boolean what) {
            addDebugStackTraceElementComment();
            addStatement(new ReturnStatement(new Adhoc(Boolean.toString(what))));
            return cast();
        }

        public B returningNull() {
            return returning("null");
        }

        public B returning(Number num) {
            addDebugStackTraceElementComment();
            addStatement(new ReturnStatement(friendlyNumber(num)));
            return cast();
        }

        public B returningStringLiteral(String s) {
            addDebugStackTraceElementComment();
            addStatement(new ReturnStatement(new Adhoc(LinesBuilder.stringLiteral(s))));
            return cast();
        }

        public B returningStringConcatenation(String initialLiteral, Consumer<StringConcatenationBuilder<?>> c) {
            addDebugStackTraceElementComment();
            Holder<B> hold = new Holder<>();
            StringConcatenationBuilder<Void> scb = new StringConcatenationBuilder<>(
                    new Adhoc(LinesBuilder.stringLiteral(initialLiteral)), bldr -> {
                        addStatement(new ReturnStatement(bldr));
                        hold.set(cast());
                        return null;
                    });
            c.accept(scb);
            hold.ifUnset(scb::endConcatenation);
            return hold.get("StringConcatenationBuilder was not closed");
        }

        public B returningStringConcatenationExpression(String initialExpression, Consumer<StringConcatenationBuilder<?>> c) {
            addDebugStackTraceElementComment();
            Holder<B> hold = new Holder<>();
            StringConcatenationBuilder<Void> scb = new StringConcatenationBuilder<>(
                    new Adhoc(initialExpression), bldr -> {
                        addStatement(new ReturnStatement(bldr));
                        hold.set(cast());
                        return null;
                    });
            c.accept(scb);
            hold.ifUnset(scb::endConcatenation);
            return hold.get("StringConcatenationBuilder was not closed");
        }

        public InvocationBuilder<B> returningInvocationOf(String method) {
            addDebugStackTraceElementComment();
            return new InvocationBuilder<>(ib -> {
                addDebugStackTraceElementComment();
                addStatement(new ReturnStatement(ib));
                return cast();
            }, method);
        }

        public X returningInvocationOf(String method, Consumer<? super InvocationBuilder<?>> c) {
            addDebugStackTraceElementComment();
            boolean[] built = new boolean[1];
            Holder<X> holder = new Holder<>();
            InvocationBuilder<Void> b = new InvocationBuilder<>(ib -> {
                addDebugStackTraceElementComment();
                addStatement(new ReturnStatement(ib));
                built[0] = true;
                X result = x();
                holder.set(result);
                return null;
            }, method);
            c.accept(b);
            if (!built[0]) {
                b.inScope();
            }
            X result = holder.get("Invocation builder not completed - call"
                    + "inScope() or on()");
            return result;
        }

        public B incrementVariable(String var) {
            addStatement(new OneStatement(var + "++"));
            return cast();
        }

        public B decrementVariable(String var) {
            addStatement(new OneStatement(var + "--"));
            return cast();
        }

        public DeclarationBuilder<B> declare(String what) {
            return new DeclarationBuilder<>(db -> {
                addDebugStackTraceElementComment();
                addStatement(db);
                return cast();
            }, what);
        }

        public B declare(String what, Consumer<DeclarationBuilder<?>> c) {
            Holder<B> h = new Holder<>();
            DeclarationBuilder<Void> result = new DeclarationBuilder<>(db -> {
                addDebugStackTraceElementComment();
                addStatement(db);
                h.set(cast());
                return null;
            }, what);
            c.accept(result);
            return h.get("Declaration not completed");
        }

        public SwitchBuilder<B> switchingOn(String on) {
            return new SwitchBuilder<>(sb -> {
                addDebugStackTraceElementComment();
                addStatement(sb);
                return cast();
            }, on);
        }

        public InvocationBuilder<SwitchBuilder<B>> switchingOnInvocationOf(String mth) {
            return new InvocationBuilder<>(ib -> {
                return new SwitchBuilder<B>(sb -> {
                    addDebugStackTraceElementComment();
                    addStatement(sb);
                    return cast();
                }, ib);
            }, mth);
        }

        public B switchingOnInvocationOf(String mth, Consumer<InvocationBuilder<SwitchBuilder<?>>> c) {
            Holder<B> h = new Holder<>();
            Holder<SwitchBuilder<Void>> switcher = new Holder<>();
            InvocationBuilder<SwitchBuilder<?>> result = new InvocationBuilder<>(ib -> {
                SwitchBuilder<Void> sw = new SwitchBuilder<Void>(sb -> {
                    addDebugStackTraceElementComment();
                    addStatement(sb);
                    h.set(cast());
                    return null;
                }, ib);
                switcher.set(sw);
                return sw;
            }, mth);
            c.accept(result);
            if (!h.isSet()) {
                if (switcher.isSet()) {
                    switcher.get().build();
                }
            }
            return h.get("Invocation builder or switch builder not completed");
        }

        public B switchingOn(String on, Consumer<? super SwitchBuilder<?>> c) {
            boolean[] built = new boolean[1];
            SwitchBuilder<Void> sw = new SwitchBuilder<>(sb -> {
                addDebugStackTraceElementComment();
                addStatement(sb);
                built[0] = true;
                return null;
            }, on);
            c.accept(sw);
            if (!built[0]) {
                if (!sw.isEmpty()) {
                    sw.build();
                } else {
                    throw new IllegalStateException("Switch builder never added "
                            + "to and never closed");
                }
            }
            return cast();
        }

        Exception built;

        protected T endBlock() {
            if (built != null) {
                throw new IllegalStateException("Block built twice", built);
            }
            built = new Exception("endBlock");
            return converter.apply(cast());
        }

        static class OneStatement extends CodeGeneratorBase {

            private final String text;
            private final boolean initialNewline;

            OneStatement(String text) {
                this(text, true);
            }

            OneStatement(String text, boolean initialNewline) {
                this.text = text;
                this.initialNewline = initialNewline;
            }

            @Override
            public void generateInto(LinesBuilder lines) {
                if (text.indexOf('\n') > 0) {
                    if (initialNewline) {
                        lines.maybeNewline();
                    }
                    lines.wrappable(lb -> {
                        String[] parts = text.split("\n");
                        boolean first = true;
                        for (int i = 0; i < parts.length; i++) {
                            String part = parts[i];
                            part = part.trim();
                            if (part.isEmpty()) {
                                lb.doubleNewline();
                            } else {
                                if (first) {
                                    first = false;
//                                    if (initialNewline) {
//                                        lb.onNewLine();
//                                    }
                                }
                                lb.word(part);
                                if (i != parts.length - 1) {
                                    lb.onNewLine();
                                }
                            }
                        }
                        if (!first) {
                            lb.statementTerminator();
                        }
                    });
                } else {
                    if (initialNewline) {
                        lines.statement(text.trim());
                    } else {
                        lines.word(text.trim());
                        lines.statementTerminator().onNewLine();
                    }
                }
            }
        }
    }

    public static final class LineComment extends CodeGeneratorBase {

        private final String line;
        private final boolean trailing;

        public LineComment(String what, boolean trailing) {
            this.line = what;
            this.trailing = trailing;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.lineComment(trailing, line);
        }
    }

    public static final class AssertionBuilder<T> extends CodeGeneratorBase {

        private CodeGenerator assertThat;
        private CodeGenerator message;
        private final Function<AssertionBuilder<T>, T> converter;
        private boolean built;
        private Exception builtAt;
        private final boolean forLambda;

        AssertionBuilder(CodeGenerator leftSide, Function<AssertionBuilder<T>, T> converter) {
            assertThat = leftSide;
            this.converter = converter;
            forLambda = true;
        }

        AssertionBuilder(Function<AssertionBuilder<T>, T> converter, boolean forLambda) {
            this.converter = converter;
            this.forLambda = forLambda;
        }

        @Override
        public String toString() {
            return "assert " + assertThat + (message == null ? ";" : ":" + message + "; //"
                    + (built ? "built" : "not-yet-built"));
        }

        public StringConcatenationBuilder<T> withMessage() {
            return new StringConcatenationBuilder<>(message, sb -> {
                message = sb;
                return build();
            });
        }

        public StringConcatenationBuilder<T> assertingNotNull(String expression) {
            assertThat = new Composite(new Adhoc(expression, true), new Adhoc("!=", true), new Adhoc("null", true));
            return withMessage();
        }

        ValueExpressionBuilder<AssertionBuilder<T>> assertingNotNull() {
            return new ValueExpressionBuilder<>(veb -> {
                assertThat = new Composite(veb, new Adhoc("!=", true), new Adhoc("null", true));
                return this;
            });
        }

        public T withMessage(Consumer<StringConcatenationBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            boolean[] finished = new boolean[1];
            StringConcatenationBuilder<Void> bldr = new StringConcatenationBuilder<>(null, sb -> {
                message = sb;
                if (!forLambda) {
                    holder.set(build());
                }
                finished[0] = true;
                return null;
            });
            c.accept(bldr);
            if (!finished[0]) {
                bldr.endConcatenation();
            }
            return forLambda ? null : holder.get("Assertion message builder created but not completed");
        }

        public T withMessage(String message) {
            if (this.message != null) {
                throw new IllegalStateException("Message already set to " + this.message);
            }
            this.message = new Adhoc(LinesBuilder.stringLiteral(message), true);
            return built ? null : build();
        }

        public T build() {
            if (built) {
                throw new IllegalStateException("Built twice", builtAt);
            }
            if (assertThat == null) {
                throw new IllegalStateException("AssertionBuilder not completed - "
                        + "no clause for what to assert provided");
            }
            built = true;
            builtAt = new Exception(toString());
            return converter.apply(this);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            if (assertThat == null) {
                throw new IllegalStateException("AssertionBuilder not completed - "
                        + "no clause for what to assert provided");
            }
            lines.statement(lb -> {
                lb.onNewLine().word("assert");
                assertThat.generateInto(lb);
                if (message != null) {
                    lines.word(":", true);
                    message.generateInto(lb);

                }
            });
        }
    }

    public static final class TypeAssignment<T> extends CodeGeneratorBase {

        private CodeGenerator type;
        private final Function<TypeAssignment<T>, T> converter;

        public TypeAssignment(Function<TypeAssignment<T>, T> converter) {
            this.converter = converter;
        }

        public T as(String type) {
            this.type = parseTypeName(checkIdentifier(notNull("type", type)));
            return converter.apply(this);
        }

        public T asString() {
            return as("String");
        }

        public T asInt() {
            return as("int");
        }

        public T asBoolean() {
            return as("boolean");
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            type.generateInto(lines);
        }
    }

    public static final class DeclarationBuilder<T> extends CodeGeneratorBase {

        private final Function<DeclarationBuilder<T>, T> converter;
        private final String name;
        private CodeGenerator as;
        private CodeGenerator initializer;

        DeclarationBuilder(Function<DeclarationBuilder<T>, T> converter, String name) {
            this.converter = converter;
            this.name = name;
        }

        public T as(String type) {
            this.as = parseTypeName(checkIdentifier(notNull("type", type)));
            LinesBuilder lb = new LinesBuilder();
            as.generateInto(lb);
            return converter.apply(this);
        }

        public FieldReferenceBuilder<TypeAssignment<T>> initializedFromField(String name) {
            return new FieldReferenceBuilder<>(name, frb -> {
                initializer = frb;
                return new TypeAssignment<>(ta -> {
                    this.as = ta.type;
                    return converter.apply(this);
                });
            });
        }

        public LambdaBuilder<TypeAssignment<T>> initializedFromLambda() {
            return new LambdaBuilder<TypeAssignment<T>>(frb -> {
                initializer = frb;
                return new TypeAssignment<>(ta -> {
                    this.as = ta.type;
                    return converter.apply(this);
                });
            });
        }

        public TypeAssignment<T> initializedFromLambda(Consumer<LambdaBuilder<?>> c) {
            Holder<TypeAssignment<T>> hold = new Holder<>();
            LambdaBuilder<TypeAssignment<T>> res = new LambdaBuilder<TypeAssignment<T>>(frb -> {
                initializer = frb;
                hold.set(new TypeAssignment<>(ta -> {
                    this.as = ta.type;
                    return converter.apply(this);
                }));
                return null;
            });
            c.accept(res);
            return hold.get("LambdaBuilder or TypeAssignment not completed");
        }

        public TypeAssignment<T> initializedFromField(String name, Consumer<FieldReferenceBuilder<?>> c) {
            Holder<TypeAssignment<T>> h = new Holder<>();
            FieldReferenceBuilder<Void> frb = new FieldReferenceBuilder<>(name, b -> {
                initializer = b;
                h.set(new TypeAssignment<>(ta -> {
                    this.as = ta.type;
                    return converter.apply(this);
                }));
                return null;
            });
            c.accept(frb);
            return h.get("Field reference not completed");
        }

        public NewBuilder<TypeAssignment<T>> initializedWithNew() {
            return new NewBuilder<>(nb -> {
                initializer = nb;
                return new TypeAssignment<>(ta -> {
                    this.as = ta.type;
                    return converter.apply(this);
                });
            });
        }

        public TypeAssignment<T> initializedWithNew(Consumer<NewBuilder<?>> c) {
            Holder<TypeAssignment<T>> h = new Holder<>();
            NewBuilder<Void> frb = new NewBuilder<>(b -> {
                initializer = b;
                h.set(new TypeAssignment<>(ta -> {
                    this.as = ta.type;
                    return converter.apply(this);
                }));
                return null;
            });
            c.accept(frb);
            return h.get("New invocation not completed");
        }

        public InvocationBuilder<TypeAssignment<T>> initializedWithNew(String what) {
            return new InvocationBuilder<>(ib -> {
                initializer = ib;
                return new TypeAssignment<>(ta -> {
                    this.as = ta;
                    return converter.apply(this);
                });
            }, "new " + what);
        }

        public TypeAssignment<T> initializedWith(String init) {
            this.initializer = new Adhoc(init);
            return new TypeAssignment<>(ta -> {
                this.as = ta.type;
                return converter.apply(this);
            });
        }

        public TypeAssignment<T> initializedWithStringLiteral(String init) {
            this.initializer = new Adhoc(LinesBuilder.stringLiteral(init));
            return new TypeAssignment<>(ta -> {
                this.as = ta.type;
                return converter.apply(this);
            });
        }

        public ValueExpressionBuilder<TypeAssignment<T>> initializedTo() {
            return new ValueExpressionBuilder<>(veb -> {
                initializer = veb;
                return new TypeAssignment<>(ta -> {
                    this.as = ta.type;
                    return converter.apply(this);
                });
            });
        }

        public ConditionBuilder<T> initializedWithBooleanExpression() {
            return new ConditionBuilder<>(fcb -> {
                initializer = fcb;
                as = new TypeNameItem("boolean");
                return converter.apply(DeclarationBuilder.this);
            });
        }

        public InvocationBuilder<TypeAssignment<T>> initializedByInvoking(String what) {
            return new InvocationBuilder<>(ib -> {
                initializer = ib;
                return new TypeAssignment<>(ta -> {
                    as = ta.type;
                    return converter.apply(this);
                });
            }, what);
        }

        public TypeAssignment<T> initializedByInvoking(String what, Consumer<InvocationBuilder<?>> c) {
            Holder<TypeAssignment<T>> h = new Holder<>();
            InvocationBuilder<Void> result = new InvocationBuilder<>(ib -> {
                initializer = ib;
                h.set(new TypeAssignment<>(ta -> {
                    this.as = ta.type;
                    return converter.apply(this);
                }));
                return null;
            }, name);
            c.accept(result);
            if (!h.isSet()) {
                result.inScope();
            }
            return h.get("Invocation builder not completed");
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.onNewLine();
            lines.statement(l -> {
                as.generateInto(lines);
                l.word(name);
                if (initializer != null) {
                    l.word("=");
                    initializer.generateInto(l);
                }
            });
        }

        public ArrayLiteralBuilder<T> initializedAsNewArray(String type) {
            return new ArrayLiteralBuilder<>(alb -> {
                as = typeAndDimensions(alb);
                initializer = alb;
                return converter.apply(this);
            }, type);
        }

        public T initializedAsNewArray(String arrayType, Consumer<? super ArrayLiteralBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            ArrayLiteralBuilder<Void> bldr = new ArrayLiteralBuilder<>(alb -> {
                as = typeAndDimensions(alb);
                initializer = alb;
                holder.set(converter.apply(this));
                return null;
            }, arrayType);
            c.accept(bldr);
            return holder.get(() -> bldr.closeArrayLiteral());
        }
    }

    public static final class ElseClauseBuilder<T> extends BlockBuilderBase<T, ElseClauseBuilder<T>, ElseClauseBuilder<T>> {

        ElseClauseBuilder(Function<? super ElseClauseBuilder<T>, T> converter, boolean openBlock) {
            super(converter, openBlock);
        }

        @Override
        ElseClauseBuilder<T> x() {
            return this;
        }

        public T endIf() {
            return endBlock();
        }
    }

    public static final class IfBuilder<T> extends BlockBuilderBase<T, IfBuilder<T>, IfBuilder<T>> {

        private BlockBuilderBase<?, ?, ?> finalElse;
        private final List<Pair<CodeGenerator, IfBuilder<?>>> clausePairs = new ArrayList<>();

        IfBuilder(Function<? super IfBuilder<T>, T> converter, CodeGenerator condition) {
            super(converter, true);
            clausePairs.add(new Pair<>(condition, this));
        }

        IfBuilder(IfBuilder<T> orig, CodeGenerator condition) {
            super(orig.converter, true);
            clausePairs.addAll(orig.clausePairs);
            clausePairs.add(new Pair<>(condition, this));
        }

        IfBuilder(IfBuilder<?> orig, Function<? super IfBuilder<T>, T> converter, CodeGenerator condition) {
            super(converter, true);
            clausePairs.addAll(orig.clausePairs);
            clausePairs.add(new Pair<>(condition, this));
        }

        @Override
        IfBuilder<T> x() {
            return this;
        }

        public T elseIf(Consumer<? super ConditionBuilder<? extends IfBuilder<?>>> c) {
            Holder<T> holder = new Holder<>();
            boolean[] built = new boolean[1];
            Holder<IfBuilder<T>> sub = new Holder<>();
            ConditionBuilder<IfBuilder<T>> b = new ConditionBuilder<>(fcb -> {
                Function<IfBuilder<T>, T> cvt = (ib2 -> {
                    built[0] = true;
                    holder.set(converter.apply(ib2));
                    return null;
                });
                IfBuilder<T> nue = new IfBuilder<>(this, cvt, fcb);
                sub.set(nue);
                return nue;
            });
            c.accept(b);
            if (!built[0] && sub.isSet()) {
                holder.set(sub.get().endBlock());
            }
            return holder.get();
        }

        public T endIf() {
            return endBlock();
        }

        public ConditionBuilder<IfBuilder<T>> elseIf() {
            ConditionBuilder<IfBuilder<T>> c = new ConditionBuilder<>(fcb -> {
                IfBuilder<T> nue = new IfBuilder<>(this, fcb);
                return nue;
            });
            return c;
        }

        public ElseClauseBuilder<T> orElse() {
            return new ElseClauseBuilder<>(bb -> {
                finalElse = bb;
                return endBlock();
            }, true);
        }

        public T orElse(Consumer<? super ElseClauseBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            ElseClauseBuilder<Void> result = new ElseClauseBuilder<>(bb -> {
                finalElse = bb;
                h.set(endBlock());
                return null;
            }, true);
            c.accept(result);
            if (!h.isSet()) {
                result.endBlock();
            }
            return h.get();
        }

        private void writeBlockInto(LinesBuilder lines) {
            super.generateInto(lines);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.backup().onNewLine(); // XXX
            assert !clausePairs.isEmpty();
            for (int i = 0; i < clausePairs.size(); i++) {
                Pair<CodeGenerator, IfBuilder<?>> pair = clausePairs.get(i);
                if (i == 0) {
                    lines.onNewLine();
                    lines.word("if ");
                }
                lines.parens(lb -> {
                    pair.a.generateInto(lb);
                });
                lines.backup();
                pair.b.writeBlockInto(lines);
                if (i < clausePairs.size() - 1) {
                    lines.backup().word("else").word("if ");
                }
            }
            if (finalElse != null) {
                lines.backup();
                lines.word("else");
                finalElse.generateInto(lines);
            }
        }
    }

    public static class ParenthesizedCondition<T> extends CodeGeneratorBase {

        private boolean negated;
        private final BiFunction<ParenthesizedCondition<T>, LogicalOperation, T> converter;
        private final CodeGenerator inner;

        ParenthesizedCondition(BiFunction<ParenthesizedCondition<T>, LogicalOperation, T> converter,
                CodeGenerator inner) {
            this.converter = converter;
            this.inner = inner;
        }

        public T closeParenthesesAnd() {
            return converter.apply(this, LogicalOperation.AND);
        }

        public ParenthesizedCondition<T> negate() {
            negated = true;
            return this;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            if (negated) {
                lines.appendRaw('!');
            }
            lines.parens(lb -> {
                inner.generateInto(lines);
            });
        }
    }

    public static final class ConditionBuilder<T> extends CodeGeneratorBase {

        private final Function<CodeGenerator, T> converter;
        private boolean negated;
        private boolean parenthesized;
        private CodeGenerator prev;
        private LogicalOperation op;
        private CodeGenerator clause;

        ConditionBuilder(Function<CodeGenerator, T> converter) {
            this.converter = converter;
        }

        ConditionBuilder(Function<CodeGenerator, T> converter, CodeGenerator prev, LogicalOperation op) {
            this.converter = converter;
            this.prev = prev;
            this.op = op;
        }

        public ConditionBuilder<ParenthesizedCondition<ComparisonBuilder<T>>> parenthesize() {
            ConditionBuilder<ParenthesizedCondition<ComparisonBuilder<T>>> cp
                    = new ConditionBuilder<ParenthesizedCondition<ComparisonBuilder<T>>>(cb -> {
                        return new ParenthesizedCondition<ComparisonBuilder<T>>((pc, op) -> {
                            clause = pc;
                            return new ComparisonBuilder<>(this);
                        }, cb);
                    });
            return cp;
        }

        @Override
        public String toString() {
            LinesBuilder lines = new LinesBuilder(4);
            if (negated) {
                lines.appendRaw('!');
            }
            if (parenthesized) {
                lines.appendRaw('(');
            }
            if (prev != null) {
                prev.generateInto(lines);
            }
            if (op != null) {
                op.generateInto(lines);
            }
            if (clause != null) {
                clause.generateInto(lines);
            }
            if (parenthesized) {
                lines.appendRaw(')');
            }
            return lines.toString();
        }

        public ConditionBuilder<T> not() {
            negated = !negated;
            parenthesized();
            return this;
        }

        public ConditionBuilder<T> parenthesized() {
            this.parenthesized = true;
            return this;
        }

        public InvocationBuilder<T> invokeAsBoolean(String what) {
            return new InvocationBuilder<>(ib -> {
                clause = ib;
                return converter.apply(this);
            }, what);
        }

        public NumericOrBitwiseExpressionBuilder<ComparisonBuilder<T>> numericCondition(String initialExpression) {
            return numericCondition(new Adhoc(initialExpression));
        }

        public NumericOrBitwiseExpressionBuilder<ComparisonBuilder<T>> numericCondition(Number initialLiteral) {
            return numericCondition(friendlyNumber(initialLiteral));
        }

        public ComparisonBuilder<T> numericCondition(String initialExpression, Consumer<NumericOrBitwiseExpressionBuilder<?>> c) {
            return numericCondition(new Adhoc(initialExpression), c);
        }

        public ComparisonBuilder<T> numericCondition(Number initialLiteral, Consumer<NumericOrBitwiseExpressionBuilder<?>> c) {
            return numericCondition(friendlyNumber(initialLiteral), c);
        }

        private NumericOrBitwiseExpressionBuilder<ComparisonBuilder<T>> numericCondition(CodeGenerator initialExpression) {
            return new NumericOrBitwiseExpressionBuilder<ComparisonBuilder<T>>(initialExpression,
                    num -> {
                        clause = num;
                        return new ComparisonBuilder<>(this);
                    });
        }

        private ComparisonBuilder<T> numericCondition(CodeGenerator initialExpression,
                Consumer<NumericOrBitwiseExpressionBuilder<?>> c) {
            Holder<FinishableNumericOrBitwiseExpressionBuilder<ComparisonBuilder<Void>>> fin = new Holder<>();
            Holder<ComparisonBuilder<T>> hd = new Holder<>();
            NumericOrBitwiseExpressionBuilder<ComparisonBuilder<Void>> nb
                    = new NumericOrBitwiseExpressionBuilder<ComparisonBuilder<Void>>(initialExpression,
                            (FinishableNumericOrBitwiseExpressionBuilder<ComparisonBuilder<Void>> num) -> {
                                fin.set(num);
                                clause = num;
                                hd.set(new ComparisonBuilder<>(this));
                                return null;
                            }).notifying(fin);

            c.accept(nb);
            if (!hd.isSet() && fin.isSet()) {
                fin.get().endNumericExpression();
            }
            return hd.get("NumericOrBitwiseExpressionBuilder for '"
                    + initialExpression + "' was not completed");
        }

        public FinishableConditionBuilder<T> isNotNull(String name) {
            ConditionRightSideBuilder<T> crb = new ConditionRightSideBuilder<T>(converter,
                    new Adhoc(name), ComparisonOperation.NE);
            return new FinishableConditionBuilder<T>(converter, crb, new Adhoc("null"));
        }

        public FinishableConditionBuilder<T> isNull(String name) {
            ConditionRightSideBuilder<T> crb = new ConditionRightSideBuilder<T>(converter,
                    new Adhoc(name), ComparisonOperation.EQ);
            return new FinishableConditionBuilder<T>(converter, crb, new Adhoc("null"));
        }

        public InvocationBuilder<ComparisonBuilder<T>> invoke(String what) {
            return new InvocationBuilder<>(ib -> {
                clause = ib;
                return new ComparisonBuilder<>(this);
            }, what);
        }

        public ComparisonBuilder<T> variable(String name) {
            clause = new Adhoc(name);
            return new ComparisonBuilder<>(this);
        }

        public NumericOrBitwiseExpressionBuilder<ComparisonBuilder<T>> numericExpression(String initialExpression) {
            return value().numeric().expression(initialExpression);
        }

        public ComparisonBuilder<T> compare(Value val) {
            clause = val;
            return new ComparisonBuilder<>(this);
        }

        public ValueExpressionBuilder<ComparisonBuilder<T>> value() {
            return new ValueExpressionBuilder<>(veb -> {
                clause = veb;
                return new ComparisonBuilder<>(this);
            });
        }

        public ComparisonBuilder<T> value(Consumer<ValueExpressionBuilder<?>> c) {
            Holder<ComparisonBuilder<T>> hold = new Holder<>();
            ValueExpressionBuilder<Void> result = new ValueExpressionBuilder<>(veb -> {
                this.clause = veb;
                hold.set(new ComparisonBuilder<>(this));
                return null;
            });
            c.accept(result);
            return hold.get("Value expression not completed");
        }

        public T booleanExpression(String expression) {
            clause = new Adhoc(expression);
            return converter.apply(this);
        }

        public ComparisonBuilder<T> literal(String lit) {
            clause = new Adhoc(LinesBuilder.stringLiteral(lit));
            return new ComparisonBuilder<>(this);
        }

        public ComparisonBuilder<T> literal(Number val) {
            clause = friendlyNumber(val);
            return new ComparisonBuilder<>(this);
        }

        public ComparisonBuilder<T> literal(char val) {
            clause = new Adhoc(friendlyChar(val));
            return new ComparisonBuilder<>(this);
        }

        public FinishableConditionBuilder<T> literal(boolean val) {
            clause = new Adhoc(Boolean.toString(val));
            FinishableConditionBuilder<T> result = new FinishableConditionBuilder<>(fcb -> {
                return converter.apply(fcb);
            }, new ConditionRightSideBuilder<>(null, this, null), null);
            return result;
        }

        private static final ThreadLocal<Boolean> BUILDING_CONDITION = ThreadLocal.withInitial(() -> false);

        @Override
        public void generateInto(LinesBuilder lines) {
            boolean entry = !BUILDING_CONDITION.get();
            if (entry) {
                BUILDING_CONDITION.set(true);
            }
            Consumer<LinesBuilder> cb = (lb) -> {
                if (negated) {
                    lines.appendRaw('!');
                    if (clause instanceof InvocationBuilder<?>) {
                        doBuildInto(lines);
                    } else {
                        lines.parens(this::doBuildInto);
                    }
                } else {
                    doBuildInto(lines);
                }
            };
            Consumer<LinesBuilder> c = lb -> {
                if (parenthesized) {
                    lb.parens(cb);
                } else {
                    cb.accept(lb);
                }
            };
            if (entry) {
                lines.doubleHangingWrap(c);
            } else {
                c.accept(lines);
            }
            if (entry) {
                BUILDING_CONDITION.set(false);
            }
        }

        private void doBuildInto(LinesBuilder lines) {
            if (prev != null) {
                prev.generateInto(lines);
                int remainingLength = clause.toString().length()
                        + (op == null ? 0 : op.toString().length() + 1);
                lines.hintLineBreak(remainingLength);
                if (op != null) {
                    op.generateInto(lines);
                } else {
                    lines.word("==");
                }
            }
            clause.generateInto(lines);
        }
    }

    public static final class ComparisonBuilder<T> extends CodeGeneratorBase {

        private final ConditionBuilder<T> leftSide;
        private Consumer<T> notify;

        ComparisonBuilder(ConditionBuilder<T> leftSide) {
            this.leftSide = leftSide;
        }

        ComparisonBuilder<T> notifying(Consumer<T> no) {
            if (this.notify != null) {
                this.notify = this.notify.andThen(no);
            } else {
                this.notify = no;
            }
            return this;
        }

        private Function<CodeGenerator, T> converter() {
            if (notify == null) {
                return leftSide.converter;
            } else {
                return bb -> {
                    T result = leftSide.converter.apply(bb);
                    notify.accept(result);
                    return result;
                };
            }
        }

        public FinishableConditionBuilder<T> isTrue() {
            ConditionRightSideBuilder<T> cb = new ConditionRightSideBuilder<>(converter(), leftSide, null);
            return new FinishableConditionBuilder<>(fcb -> {
                return leftSide.converter.apply(fcb);
            }, cb, null);
        }

        public FinishableConditionBuilder<T> isFalse() {
            ConditionRightSideBuilder<T> cb = new ConditionRightSideBuilder<>(converter(), leftSide, null);
            return new FinishableConditionBuilder<>(fcb -> {
                return converter().apply(new Composite(new Adhoc("!"), fcb));
            }, cb, null);
        }

        public FinishableConditionBuilder<T> equals(Value val) {
            ConditionRightSideBuilder<T> cv = new ConditionRightSideBuilder<>(converter(), leftSide, ComparisonOperation.EQ);
            return new FinishableConditionBuilder<>(converter(), cv, val);
        }

        public FinishableConditionBuilder<T> notEquals(Value val) {
            ConditionRightSideBuilder<T> cv = new ConditionRightSideBuilder<>(converter(), leftSide, ComparisonOperation.NE);
            return new FinishableConditionBuilder<>(converter(), cv, val);
        }

        public FinishableConditionBuilder<T> greaterThan(Value val) {
            ConditionRightSideBuilder<T> cv = new ConditionRightSideBuilder<>(converter(), leftSide, ComparisonOperation.GT);
            return new FinishableConditionBuilder<>(converter(), cv, val);
        }

        public FinishableConditionBuilder<T> lessThan(Value val) {
            ConditionRightSideBuilder<T> cv = new ConditionRightSideBuilder<>(converter(), leftSide, ComparisonOperation.LT);
            return new FinishableConditionBuilder<>(converter(), cv, val);
        }

        public FinishableConditionBuilder<T> greaterThanOrEqualTo(Value val) {
            ConditionRightSideBuilder<T> cv = new ConditionRightSideBuilder<>(converter(), leftSide, ComparisonOperation.GTE);
            return new FinishableConditionBuilder<>(converter(), cv, val);
        }

        public FinishableConditionBuilder<T> lessThanOrEqualTo(Value val) {
            ConditionRightSideBuilder<T> cv = new ConditionRightSideBuilder<>(converter(), leftSide, ComparisonOperation.LTE);
            return new FinishableConditionBuilder<>(converter(), cv, val);
        }

        public ConditionRightSideBuilder<T> equals() {
            return new ConditionRightSideBuilder<>(converter(), leftSide, ComparisonOperation.EQ);
        }

        public ConditionRightSideBuilder<T> notEquals() {
            return new ConditionRightSideBuilder<>(converter(), leftSide, ComparisonOperation.NE);
        }

        public ConditionRightSideBuilder<T> greaterThan() {
            return new ConditionRightSideBuilder<>(converter(), leftSide, ComparisonOperation.GT);
        }

        public ConditionRightSideBuilder<T> lessThan() {
            return new ConditionRightSideBuilder<>(converter(), leftSide, ComparisonOperation.LT);
        }

        public ConditionRightSideBuilder<T> greaterThanOrEqualto() {
            return new ConditionRightSideBuilder<>(converter(), leftSide, ComparisonOperation.GTE);
        }

        public ConditionRightSideBuilder<T> lessThanOrEqualto() {
            return new ConditionRightSideBuilder<>(converter(), leftSide, ComparisonOperation.LTE);
        }

        public ConditionRightSideBuilder<T> instanceOf() {
            return new ConditionRightSideBuilder<>(converter(), leftSide, ComparisonOperation.INSTANCEOF);
        }

        public T eqaullingExpression(String expression) {
            return equals().expression(expression).endCondition();
        }

        /**
         * Object rather than instance equality.
         *
         * @param expression An expression
         * @return the owner of this builder
         */
        public T isEquals(String expression) {
            return leftSide.converter.apply(new Composite(leftSide,
                    new Adhoc(".equals", true),
                    new Punctuation('('),
                    new Adhoc(expression), new Punctuation(')')));
        }

        public InvocationBuilder<T> equalsInvocationOf(String what) {
            return new InvocationBuilder<>(ib -> {
                return converter().apply(new Composite(leftSide, new Adhoc(".equals", true),
                        new Punctuation('('), ib, new Punctuation(')')));
            }, what);
        }

        public InvocationBuilder<T> notEqualsInvocationOf(String what) {
            return new InvocationBuilder<>(ib -> {
                return converter().apply(new Composite(new Punctuation('!'), leftSide, new Adhoc(".equals", true),
                        new Punctuation('('), ib, new Punctuation(')')));
            }, what);
        }

        public InvocationBuilder<T> isEqualToInvocationOf(String what) {
            return new InvocationBuilder<>(ib -> {
                return equals().invoke(what).applyFrom(ib).endCondition();
            }, what);
        }

        public InvocationBuilder<T> isLessThanInvocationOf(String what) {
            return new InvocationBuilder<>(ib -> {
                return lessThan().invoke(what).applyFrom(ib).endCondition();
            }, what);
        }

        public InvocationBuilder<T> isGreaterThanInvocationOf(String what) {
            return new InvocationBuilder<>(ib -> {
                return greaterThan().invoke(what).applyFrom(ib).endCondition();
            }, what);
        }

        public InvocationBuilder<T> isLessThanOrEqualToInvocationOf(String what) {
            return new InvocationBuilder<>(ib -> {
                return lessThanOrEqualto().invoke(what).applyFrom(ib).endCondition();
            }, what);
        }

        public InvocationBuilder<T> isGreaterThanOrEqualToInvocationOf(String what) {
            return new InvocationBuilder<>(ib -> {
                return greaterThanOrEqualto().invoke(what).applyFrom(ib).endCondition();
            }, what);
        }

        public InvocationBuilder<T> isNotEqualToInvocationOf(String what) {
            return new InvocationBuilder<>(ib -> {
                return notEquals().invoke(what).applyFrom(ib).endCondition();
            }, what);
        }

        public FieldReferenceBuilder<T> equalsField(String name) {
            return new FieldReferenceBuilder<>(name, frb -> {
                return converter().apply(new Composite(leftSide, new Adhoc(".equals", true),
                        new Punctuation('('),
                        frb, new Punctuation(')')));
            });
        }

        public FieldReferenceBuilder<T> isNotEqualsField(String name) {
            return new FieldReferenceBuilder<>(name, frb -> {
                return converter().apply(new Composite(new Punctuation('!'),
                        leftSide, new Adhoc(".equals", true),
                        new Punctuation('('),
                        frb, new Punctuation(')')));
            });
        }

        public FieldReferenceBuilder<T> isEqualToField(String name) {
            return new FieldReferenceBuilder<>(name, frb -> {
                return equals().field(name).setFrom(frb).endCondition();
            });
        }

        public FieldReferenceBuilder<T> isGreaterThanField(String name) {
            return new FieldReferenceBuilder<>(name, frb -> {
                return greaterThan().field(name).setFrom(frb).endCondition();
            });
        }

        public FieldReferenceBuilder<T> isGreaterThanOrEqualToField(String name) {
            return new FieldReferenceBuilder<>(name, frb -> {
                return greaterThanOrEqualto().field(name).setFrom(frb).endCondition();
            });
        }

        public FieldReferenceBuilder<T> isLessThanField(String name) {
            return new FieldReferenceBuilder<>(name, frb -> {
                return lessThan().field(name).setFrom(frb).endCondition();
            });
        }

        public FieldReferenceBuilder<T> isLessThanOrEqualToField(String name) {
            return new FieldReferenceBuilder<>(name, frb -> {
                return lessThanOrEqualto().field(name).setFrom(frb).endCondition();
            });
        }

        public FieldReferenceBuilder<T> notEqualToField(String name) {
            return new FieldReferenceBuilder<>(name, frb -> {
                return notEquals().field(name).setFrom(frb).endCondition();
            });
        }

        public T isEqualToString(String lit) {
            return converter().apply(new Composite(leftSide, new Adhoc(".equals", true),
                    new Punctuation('('),
                    new Adhoc(LinesBuilder.stringLiteral(lit)), new Punctuation(')')));
        }

        public T isNotEquals(String expression) {
            return converter().apply(new Composite(new Punctuation('!'),
                    leftSide, new Adhoc(".equals", true),
                    new Punctuation('('),
                    new Adhoc(expression), new Punctuation(')')));
        }

        public T isNotEqualToString(String lit) {
            return converter().apply(new Composite(new Punctuation('!'),
                    leftSide, new Adhoc(".equals", true),
                    new Punctuation('('),
                    new Adhoc(LinesBuilder.stringLiteral(lit)), new Punctuation(')')));
        }

        public ValueExpressionBuilder<T> isEqualTo() {
            return new ValueExpressionBuilder<>(veb -> {
                ConditionRightSideBuilder<T> eq = equals();
                T res = eq.forValue(veb).endCondition();
                return res;
            });
        }

        public T isEqualTo(Consumer<ValueExpressionBuilder<?>> c) {
            Holder<T> hold = new Holder<>();
            ValueExpressionBuilder<?> v = new ValueExpressionBuilder<T>(veb -> {
                ConditionRightSideBuilder<T> eq = equals();
                T res = eq.forValue(veb).endCondition();
                hold.set(res);
                return null;
            });
            c.accept(v);
            return hold.get("ValueExpressionBuilder not completed.");
        }

        public ValueExpressionBuilder<T> isNotEqualTo() {
            return new ValueExpressionBuilder<>(veb -> {
                ConditionRightSideBuilder<T> eq = notEquals();
                T res = eq.forValue(veb).endCondition();
                return res;
            });
        }

        public T isNotEqualTo(Consumer<ValueExpressionBuilder<?>> c) {
            Holder<T> hold = new Holder<>();
            ValueExpressionBuilder<?> v = new ValueExpressionBuilder<T>(veb -> {
                ConditionRightSideBuilder<T> eq = notEquals();
                T res = eq.forValue(veb).endCondition();
                hold.set(res);
                return null;
            });
            c.accept(v);
            return hold.get("ValueExpressionBuilder not completed.");
        }

        // XXX should have isEqualTo() returning ValueExpressionBuilder
        // and taking a lambda with one
        public T isEqualTo(String lit) {
            return equals().literal(lit).endCondition();
        }

        public T isEqualTo(int lit) {
            return equals().literal(lit).endCondition();
        }

        public T isEqualTo(short lit) {
            return equals().literal(lit).endCondition();
        }

        public T isEqualTo(float lit) {
            return equals().literal(lit).endCondition();
        }

        public T isEqualTo(long lit) {
            return equals().literal(lit).endCondition();
        }

        public T isEqualTo(double lit) {
            return equals().literal(lit).endCondition();
        }

        public T isEqualTo(byte lit) {
            return equals().literal(lit).endCondition();
        }

        public T isGreaterThan(String expression) {
            return equals().expression(expression).endCondition();
        }

        public T isGreaterThan(int lit) {
            return greaterThan().literal(lit).endCondition();
        }

        public T isGreaterThan(short lit) {
            return greaterThan().literal(lit).endCondition();
        }

        public T isGreaterThan(float lit) {
            return greaterThan().literal(lit).endCondition();
        }

        public T isGreaterThan(long lit) {
            return greaterThan().literal(lit).endCondition();
        }

        public T isGreaterThan(double lit) {
            return greaterThan().literal(lit).endCondition();
        }

        public T isGreaterThan(byte lit) {
            return greaterThan().literal(lit).endCondition();
        }

        public T isGreaterThanOrEqualTo(String expression) {
            return greaterThanOrEqualto().expression(expression).endCondition();
        }

        public T isGreaterThanOrEqualTo(int lit) {
            return greaterThanOrEqualto().literal(lit).endCondition();
        }

        public T isGreaterThanOrEqualTo(short lit) {
            return greaterThanOrEqualto().literal(lit).endCondition();
        }

        public T isGreaterThanOrEqualTo(float lit) {
            return greaterThanOrEqualto().literal(lit).endCondition();
        }

        public T isGreaterThanOrEqualTo(long lit) {
            return greaterThanOrEqualto().literal(lit).endCondition();
        }

        public T isGreaterThanOrEqualTo(double lit) {
            return greaterThanOrEqualto().literal(lit).endCondition();
        }

        public T isGreaterThanOrEqualTo(byte lit) {
            return greaterThanOrEqualto().literal(lit).endCondition();
        }

        public T isLessThan(String expression) {
            return lessThan().expression(expression).endCondition();
        }

        public T isLessThan(int lit) {
            return lessThan().literal(lit).endCondition();
        }

        public T isLessThan(short lit) {
            return lessThan().literal(lit).endCondition();
        }

        public T isLessThan(float lit) {
            return lessThan().literal(lit).endCondition();
        }

        public T isLessThan(long lit) {
            return lessThan().literal(lit).endCondition();
        }

        public T isLessThan(double lit) {
            return lessThan().literal(lit).endCondition();
        }

        public T isLessThan(byte lit) {
            return lessThan().literal(lit).endCondition();
        }

        public T isLessThanOrEqualTo(String expression) {
            return lessThanOrEqualto().expression(expression).endCondition();
        }

        public T isLessThanOrEqualTo(int lit) {
            return lessThanOrEqualto().literal(lit).endCondition();
        }

        public T isLessThanOrEqualTo(short lit) {
            return lessThanOrEqualto().literal(lit).endCondition();
        }

        public T isLessThanOrEqualTo(float lit) {
            return lessThanOrEqualto().literal(lit).endCondition();
        }

        public T isLessThanOrEqualTo(long lit) {
            return lessThanOrEqualto().literal(lit).endCondition();
        }

        public T isLessThanOrEqualTo(double lit) {
            return lessThanOrEqualto().literal(lit).endCondition();
        }

        public T isLessThanOrEqualTo(byte lit) {
            return lessThanOrEqualto().literal(lit).endCondition();
        }

        public T isNotEqualTo(String expression) {
            return notEquals().expression(expression).endCondition();
        }

        public T isNotEqualTo(int lit) {
            return notEquals().literal(lit).endCondition();
        }

        public T isNotEqualTo(short lit) {
            return notEquals().literal(lit).endCondition();
        }

        public T isNotEqualTo(float lit) {
            return notEquals().literal(lit).endCondition();
        }

        public T isNotEqualTo(long lit) {
            return notEquals().literal(lit).endCondition();
        }

        public T isNotEqualTo(double lit) {
            return notEquals().literal(lit).endCondition();
        }

        public T isNotEqualTo(byte lit) {
            return notEquals().literal(lit).endCondition();
        }

        public T instanceOf(String type) {
            return instanceOf().expression(type).endCondition();
        }

        public T equalling(boolean lit) {
            return equals().literal(lit).endCondition();
        }

        public InvocationBuilder<T> equallingInvocation(String what) {
            return new InvocationBuilder<>(ib -> {
                InvocationBuilder<FinishableConditionBuilder<T>> ivb = equals().invoke(what);
                return ivb.applyFrom(ib).endCondition();
            }, what);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            leftSide.generateInto(lines);
        }
    }

    public static final class ConditionRightSideBuilder<T> extends CodeGeneratorBase {

        private final Function<CodeGenerator, T> converter;
        boolean negated;
        final CodeGenerator leftSide;
        final ComparisonOperation op;

        ConditionRightSideBuilder(Function<CodeGenerator, T> converter, CodeGenerator leftSide, ComparisonOperation op) {
            this.converter = converter;
            this.leftSide = leftSide;
            if (leftSide instanceof ConditionBuilder<?>) {
                negated = ((ConditionBuilder<?>) ((ConditionBuilder<?>) leftSide)).negated;
            }
            this.op = op;
        }

        @Override
        public String toString() {
            return (negated ? "!" : "") + leftSide + " " + op;
        }

        public FieldReferenceBuilder<FinishableConditionBuilder<T>> field(String name) {
            return veb().field(name);
        }

        public FinishableConditionBuilder<T> field(String field, Consumer<FieldReferenceBuilder<?>> c) {
            return veb().field(field, c);
        }

        public FinishableConditionBuilder<T> invoke(String method, Consumer<InvocationBuilder<?>> c) {
            return veb().invoke(method, c);
        }

        public InvocationBuilder<FinishableConditionBuilder<T>> invoke(String method) {
            return veb().invoke(method);
        }

        public FinishableConditionBuilder<T> expression(String expression) {
            return new FinishableConditionBuilder<>(converter, this, new Adhoc(expression));
        }

        public FinishableConditionBuilder<T> literal(boolean val) {
            return veb().literal(val);
        }

        public FinishableConditionBuilder<T> literal(Number num) {
            return veb().literal(num);
        }

        public FinishableConditionBuilder<T> literal(char ch) {
            return veb().literal(ch);
        }

        public FinishableConditionBuilder<T> literal(String s) {
            return veb().literal(s);
        }

        public ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<FinishableConditionBuilder<T>>>> ternary() {
            return veb().ternary();
        }

        public FinishableConditionBuilder<T> toTernary(Consumer<ConditionBuilder<ValueExpressionBuilder<ValueExpressionBuilder<Void>>>> c) {
            return veb().toTernary(c);
        }

        public NewBuilder<FinishableConditionBuilder<T>> toNewInstance() {
            return veb().toNewInstance();
        }

        public NewBuilder<FinishableConditionBuilder<T>> toNewInstanceWithArgument(String s) {
            return veb().toNewInstanceWithArgument(s);
        }

        public FinishableConditionBuilder<T> toNewInstance(Consumer<? super NewBuilder<?>> c) {
            return veb().toNewInstance(c);
        }

        public ArrayLiteralBuilder<FinishableConditionBuilder<T>> toArrayLiteral(String type) {
            return veb().toArrayLiteral(type);
        }

        public FinishableConditionBuilder<T> toArrayLiteral(String type, Consumer<ArrayLiteralBuilder<?>> c) {
            return veb().toArrayLiteral(type, c);
        }

        public AnnotationBuilder<FinishableConditionBuilder<T>> annotation(String type) {
            return veb().annotation(type);
        }

        public FinishableConditionBuilder<T> annotation(String type, Consumer<? super AnnotationBuilder<?>> c) {
            return veb().annotation(type, c);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            if (negated) {
                lines.word("!");
                lines.parens(this::doBuildInto);
            } else {
                doBuildInto(lines);
            }
        }

        private void doBuildInto(LinesBuilder lines) {
            leftSide.generateInto(lines);
            if (op != null) {
                op.generateInto(lines);
                lines.appendRaw(' ');
            }
        }

        public NumericOrBitwiseExpressionBuilder<FinishableConditionBuilder<T>> numeric(int num) {
            return veb().numeric(num);
        }

        public NumericOrBitwiseExpressionBuilder<FinishableConditionBuilder<T>> numeric(long num) {
            return veb().numeric(num);
        }

        public NumericOrBitwiseExpressionBuilder<FinishableConditionBuilder<T>> numeric(short num) {
            return veb().numeric(num);
        }

        public NumericExpressionBuilder<FinishableConditionBuilder<T>> numeric(double num) {
            return veb().numeric(num);
        }

        public NumericExpressionBuilder<FinishableConditionBuilder<T>> numeric(float num) {
            return veb().numeric(num);
        }

        public ValueExpressionBuilder<NumericOrBitwiseExpressionBuilder<FinishableConditionBuilder<T>>> numeric() {
            return veb().numeric();
        }

        FinishableConditionBuilder<T> forValue(ValueExpressionBuilder<?> veb) {
            return new FinishableConditionBuilder<>(converter, this, veb.value);
        }

        ValueExpressionBuilder<FinishableConditionBuilder<T>> veb() {
            return new ValueExpressionBuilder<>(veb -> {
                return new FinishableConditionBuilder<>(converter, this, veb.value);
            });
        }
    }

    static final class NegatedWrapper implements CodeGenerator {

        private final CodeGenerator bb;

        NegatedWrapper(CodeGenerator bb) {
            this.bb = bb;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.appendRaw('!');
            if (bb instanceof InvocationBuilder<?> || bb instanceof ValueExpressionBuilder<?> || bb instanceof AssignmentBuilder<?>) {
                bb.generateInto(lines);
            } else {
                lines.parens(bb::generateInto);
            }
        }
    }

    public static final class FinishableConditionBuilder<T> extends CodeGeneratorBase {

        private final Function<CodeGenerator, T> converter;
        final ConditionRightSideBuilder<?> leftSideAndOp;
        private final CodeGenerator rightSide;

        FinishableConditionBuilder(Function<CodeGenerator, T> converter, ConditionRightSideBuilder<?> leftSideAndOp, CodeGenerator rightSide) {
            this.converter = converter;
            this.leftSideAndOp = leftSideAndOp;
            this.rightSide = rightSide;
        }

        @Override
        public String toString() {
            return leftSideAndOp + " " + rightSide;
        }

        public ConditionBuilder<T> or() {
            return new ConditionBuilder<>(converter, this, LogicalOperation.OR);
        }

        public ConditionBuilder<T> and() {
            return new ConditionBuilder<>(converter, this, LogicalOperation.AND);
        }

        public T negated() {
            if (rightSide != null && leftSideAndOp != null && leftSideAndOp.op != null && leftSideAndOp.op.hasNegatedForm()) {
                ConditionRightSideBuilder<T> invertedOp = new ConditionRightSideBuilder<>(converter, leftSideAndOp.leftSide, leftSideAndOp.op.negated());
                return converter.apply(new FinishableConditionBuilder<>(converter, invertedOp, rightSide));
            }
            return converter.apply(new NegatedWrapper(this));
        }

        public T endCondition() {
            return converter.apply(this);
        }

        public void endCondition(Consumer<? super T> c) {
            c.accept(converter.apply(this));
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            leftSideAndOp.generateInto(lines);
            if (rightSide != null) {
                rightSide.generateInto(lines);
            }
        }
    }

    private static enum UnaryOperator implements CodeGenerator {
        POSITIVE("+"),
        NEGATIVE("-"),
        INCREMENT("++"),
        DECREMENT("--"),
        NEGATE("!");
        private final String s;

        private UnaryOperator(String s) {
            this.s = s;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.word(s);
        }

        @Override
        public String toString() {
            return s;
        }

    }

    private static enum ArithmenticOperator implements CodeGenerator {
        PLUS("+"),
        MINUS("-"),
        TIMES("*"),
        DIVIDED_BY("/"),
        REMAINDER("%");
        private final String s;

        private ArithmenticOperator(String s) {
            this.s = s;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.word(s);
        }

        @Override
        public String toString() {
            return s;
        }
    }

    private static enum AssignmentOperator implements CodeGenerator {
        EQUALS("="),
        PLUS_EQUALS("+="),
        MINUS_EQUALS("-="),
        OR_EQUALS("|="),
        AND_EQUALS("&="),
        XOR_EQUALS("^="),
        MOD_EQUALS("%="),
        DIV_EQUALS("/="),
        MUL_EQUALS("*="),;

        private final String s;

        private AssignmentOperator(String s) {
            this.s = s;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.word(s);
        }

        @Override
        public String toString() {
            return s;
        }
    }

    private static enum ComparisonOperation implements CodeGenerator {
        EQ("=="),
        GT(">"),
        LT("<"),
        GTE(">="),
        LTE("<="),
        NE("!="),
        INSTANCEOF("instanceof");
        String s;

        private ComparisonOperation(String s) {
            this.s = s;
        }

        public boolean hasNegatedForm() {
            switch (this) {
                case INSTANCEOF:
                    return false;
                default:
                    return true;
            }
        }

        public ComparisonOperation negated() {
            switch (this) {
                case INSTANCEOF:
                    throw new UnsupportedOperationException("No negative form of instanceof");
                case EQ:
                    return NE;
                case GT:
                    return LTE;
                case LTE:
                    return GT;
                case LT:
                    return GTE;
                case GTE:
                    return LT;
                case NE:
                    return EQ;
                default:
                    throw new AssertionError(this);
            }
        }

        @Override
        public String toString() {
            return s;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.word(s);
        }
    }

    private static enum LogicalOperation implements CodeGenerator {
        OR,
        AND,
        XOR;

        @Override
        public void generateInto(LinesBuilder lines) {
            switch (this) {
                case OR:
                    lines.word("||");
                    break;
                case AND:
                    lines.word("&&");
                    break;
                case XOR:
                    lines.word("^");
                    break;
                default:
                    throw new AssertionError(this);
            }
        }
    }

    public static final class SwitchBuilder<T> extends CodeGeneratorBase {

        private final Function<SwitchBuilder<T>, T> converter;
        private final Map<Object, CodeGenerator> cases = new LinkedHashMap<>();
        private final CodeGenerator what;
        private final Set<String> allCases = new HashSet<>();

        SwitchBuilder(Function<SwitchBuilder<T>, T> converter, String on) {
            this.converter = converter;
            this.what = new Adhoc(on);
        }

        SwitchBuilder(Function<SwitchBuilder<T>, T> converter, CodeGenerator on) {
            this.converter = converter;
            this.what = on;
        }

        public boolean isEmpty() {
            return cases.isEmpty();
        }

        public BlockBuilder<SwitchBuilder<T>> inStringLiteralCase(String what) {
            return _case(LinesBuilder.stringLiteral(what));
        }

        public BlockBuilder<SwitchBuilder<T>> inDefaultCase() {
            // We translate a case of "*" into the default case
            return _case("*");
        }

        public BlockBuilder<SwitchBuilder<T>> inCase(char c) {
            return _case(LinesBuilder.escapeCharLiteral(c));
        }

        public BlockBuilder<SwitchBuilder<T>> inCase(Number num) {
            if (num instanceof Double || num instanceof Float || num instanceof Long) {
                throw new IllegalArgumentException(
                        "Java switches cannot be over floating point numbers or longs: "
                        + num + " is a " + num.getClass().getSimpleName());
            }
            return _case(num.toString());
        }

        public BlockBuilder<SwitchBuilder<T>> inCase(String what) {
            return _case(what);
        }

        public BlockBuilder<SwitchBuilder<T>> inCases(String... what) {
            return _case(what);
        }

        private BlockBuilder<SwitchBuilder<T>> _case(Object what) {
            return _case(what, new boolean[1]);
        }

        private SwitchBuilder<T> _case(Object what, Consumer<? super BlockBuilder<?>> c) {
            boolean[] built = new boolean[1];
            BlockBuilder<Void> bldr = new BlockBuilder<>(bb -> {
                addCase(bb, what);
                built[0] = true;
                return null;
            }, false);
            c.accept(bldr);
            if (!built[0]) {
                addCase(bldr, what);
            }
            return this;
        }

        private BlockBuilder<SwitchBuilder<T>> _case(Object what, boolean[] built) {
            assert what != null : "Null argument";
            return new BlockBuilder<>(bb -> {
                addCase(bb, what);
                built[0] = true;
                return SwitchBuilder.this;
            }, false);
        }

        private void addCase(BlockBuilder<?> bldr, Object what) {
            if (what == null) {
                what = "default";
            }
            if (what instanceof String) {
                String value = what.toString();
                if (allCases.contains(value)) {
                    throw new IllegalStateException("A case for '" + value
                            + "' was already added");
                }
                allCases.add(value);
                cases.put(value, bldr);
            } else if (what instanceof String[]) {
                String[] all = (String[]) what;
                for (int i = 0; i < all.length; i++) {
                    String value = all[i];
                    if (allCases.contains(value)) {
                        throw new IllegalStateException("A case for '" + value
                                + "' was already added");
                    }
                    allCases.add(value);
                }
                cases.put(all, bldr);
            } else {
                throw new IllegalArgumentException("Unknown type " + what
                        + " " + (what == null ? "null" : what.getClass().getName()));
            }
        }

        public SwitchBuilder<T> inStringLiteralCase(String what, Consumer<? super BlockBuilder<?>> c) {
            return _case(LinesBuilder.stringLiteral(what), c);
        }

        public SwitchBuilder<T> inDefaultCase(Consumer<? super BlockBuilder<?>> c) {
            return _case("*", c);
        }

        public SwitchBuilder<T> inCase(char ch, Consumer<? super BlockBuilder<?>> c) {
            return _case(LinesBuilder.escapeCharLiteral(ch), c);
        }

        public SwitchBuilder<T> inCase(Number num, Consumer<? super BlockBuilder<?>> c) {
            return _case(num.toString(), c);
        }

        public SwitchBuilder<T> inCase(String what, Consumer<? super BlockBuilder<?>> c) {
            return _case(what, c);
        }

        public SwitchBuilder<T> inCases(Consumer<? super BlockBuilder<?>> c, String... cases) {
            return _case(cases, c);
        }

        public T build() {
            return converter.apply(this);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.onNewLine()
                    .word("switch")
                    .parens(what::generateInto);
            lines.block(lb -> {
                for (Map.Entry<Object, CodeGenerator> e : cases.entrySet()) {
                    lb.backup().onNewLine();
                    if ("*".equals(e.getKey())) {
                        lb.switchCase(null, (lb1) -> {
                            e.getValue().generateInto(lb1);
                        });
                    } else {
                        if (e.getKey() instanceof String[]) {
                            String[] parts = (String[]) e.getKey();
                            lb.multiCase(e.getValue()::generateInto, parts);
                        } else {
                            String str = (String) e.getKey();
                            lb.switchCase(str, e.getValue()::generateInto);
                        }
                    }
                }
            });
        }
    }

    public static final class ArrayValueBuilder<T> extends CodeGeneratorBase {

        private final char end;

        private final Function<ArrayValueBuilder<T>, T> converter;
        private final List<CodeGenerator> values = new LinkedList<>();
        private final char start;

        ArrayValueBuilder(char start, char end, Function<ArrayValueBuilder<T>, T> converter) {
            this.end = end;
            this.converter = converter;
            this.start = start;
        }

        ArrayValueBuilder(Function<ArrayValueBuilder<T>, T> converter) {
            this('[', ']', converter);
        }

        public T closeArray() {
            return converter.apply(this);
        }

        public ArrayValueBuilder<T> literal(String s) {
            values.add(new Adhoc(LinesBuilder.stringLiteral(s), true));
            return this;
        }

        public ArrayValueBuilder<T> literal(char c) {
            values.add(new Adhoc(friendlyChar(c), true));
            return this;
        }

        public ArrayValueBuilder<T> number(Number n) {
            values.add(new Adhoc(n.toString(), true));
            return this;
        }

        public ArrayValueBuilder<T> expression(String s) {
            values.add(new Adhoc(s, true));
            return this;
        }

        public AnnotationBuilder<ArrayValueBuilder<T>> annotation(String type) {
            return annotation(type, new boolean[1]);
        }

        public ArrayValueBuilder<T> annotation(String type, Consumer<? super AnnotationBuilder<?>> c) {
            Holder<ArrayValueBuilder<T>> h = new Holder<>();
            AnnotationBuilder<Void> ab = new AnnotationBuilder<>(b -> {
                values.add(b);
                h.set(ArrayValueBuilder.this);
                return null;
            }, type);
            c.accept(ab);
            if (!h.isSet()) {
                ab.closeAnnotation();
            }
            return h.get();
        }

        private AnnotationBuilder<ArrayValueBuilder<T>> annotation(String type, boolean[] built) {
            return new AnnotationBuilder<>(ab -> {
                values.add(ab);
                return this;
            }, type);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.delimit(start, end, lb -> {
                for (Iterator<CodeGenerator> it = values.iterator(); it.hasNext();) {
                    it.next().generateInto(lb);
                    lb.backup();
                    if (it.hasNext()) {
                        lb.appendRaw(",");
                    }
                }
            });
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 67 * hash + this.end;
            hash = 67 * hash + Objects.hashCode(this.converter);
            hash = 67 * hash + Objects.hashCode(this.values);
            hash = 67 * hash + this.start;
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final ArrayValueBuilder<?> other = (ArrayValueBuilder<?>) obj;
            if (this.end != other.end) {
                return false;
            }
            if (this.start != other.start) {
                return false;
            }
            if (!Objects.equals(this.converter, other.converter)) {
                return false;
            }
            return Objects.equals(this.values, other.values);
        }
    }

    public static final class AnnotationBuilder<T> extends CodeGeneratorBase {

        private final Function<AnnotationBuilder<T>, T> converter;
        private final String annotationType;
        private final Map<String, CodeGenerator> arguments = new LinkedHashMap<>();

        AnnotationBuilder(Function<AnnotationBuilder<T>, T> converter, String annotationType) {
            this.converter = converter;
            this.annotationType = annotationType;
        }

        @Override
        public String toString() {
            LinesBuilder lb = new LinesBuilder();
            this.generateInto(lb);
            return lb.toString();
        }

        public AnnotationBuilder<T> addArrayArgument(String name, Consumer<? super ArrayValueBuilder<?>> c) {
            boolean[] built = new boolean[1];
            ArrayValueBuilder<?> bldr = addArrayArgument(name, new boolean[1]);
            c.accept(bldr);
            if (!built[0]) {
                bldr.closeArray();
            }
            return this;
        }

        public ArrayValueBuilder<AnnotationBuilder<T>> addArrayArgument(String name) {
            return addArrayArgument(name, new boolean[1]);
        }

        private ArrayValueBuilder<AnnotationBuilder<T>> addArrayArgument(String name, boolean[] built) {
            return new ArrayValueBuilder<>('{', '}', avb -> {
                arguments.put(name, avb);
                built[0] = true;
                return this;
            });
        }

        public AnnotationBuilder<AnnotationBuilder<T>> addAnnotationArgument(String name, String annotationType) {
            return addAnnotationArgument(name, annotationType, new boolean[1]);
        }

        public AnnotationBuilder<T> addAnnotationArgument(String name, String annotationType, Consumer<? super AnnotationBuilder<?>> c) {
            boolean[] built = new boolean[1];
            AnnotationBuilder<?> bldr = addAnnotationArgument(name, annotationType, built);
            c.accept(bldr);
            if (built[0] == false) {
                bldr.closeAnnotation();
            }
            return this;
        }

        private AnnotationBuilder<AnnotationBuilder<T>> addAnnotationArgument(String name, String annotationType, boolean[] built) {
            return new AnnotationBuilder<>(ab -> {
                arguments.put(name, ab);
                built[0] = true;
                return this;
            }, annotationType);
        }

        public AnnotationBuilder<T> addExpressionArgument(String name, String value) {
            arguments.put(name, new Adhoc(value));
            return this;
        }

        public AnnotationBuilder<T> addArgument(String name, boolean value) {
            arguments.put(name, new Adhoc(Boolean.toString(value)));
            return this;
        }

        public AnnotationBuilder<T> addArgument(String name, Number value) {
            arguments.put(name, friendlyNumber(value));
            return this;
        }

        public AnnotationBuilder<T> addArgument(String name, String value) {
            arguments.put(name, new Adhoc(LinesBuilder.stringLiteral(value)));
            return this;
        }

        public AnnotationBuilder<T> addArgument(String name, Class<?> type) {
            return addClassArgument(name, type.getName());
        }

        public AnnotationBuilder<T> addClassArgument(String name, String type) {
            arguments.put(name, new Adhoc(type + ".class"));
            return this;
        }

        public T closeAnnotation() {
            return converter.apply(this);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.wrappable(l -> {
                lines.onNewLine().word("@" + annotationType);
                if (!arguments.isEmpty()) {
                    lines.parens(lb -> {
                        if (arguments.size() == 1 && arguments.containsKey("value")) {
                            arguments.get("value").generateInto(lb);
                        } else {
                            lb.wrappable(wb -> {
                                for (Iterator<Map.Entry<String, CodeGenerator>> it = arguments.entrySet().iterator(); it.hasNext();) {
                                    Map.Entry<String, CodeGenerator> e = it.next();
                                    wb.word(e.getKey()).word("=");
                                    e.getValue().generateInto(lines);
                                    if (it.hasNext()) {
                                        wb.appendRaw(", ");
                                    }
                                }
                            });
                        }
                    });
                }
            });
            lines.onNewLine();
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 59 * hash + Objects.hashCode(this.converter);
            hash = 59 * hash + Objects.hashCode(this.annotationType);
            hash = 59 * hash + Objects.hashCode(this.arguments);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final AnnotationBuilder<?> other = (AnnotationBuilder<?>) obj;
            if (!Objects.equals(this.annotationType, other.annotationType)) {
                return false;
            }
            if (!Objects.equals(this.converter, other.converter)) {
                return false;
            }
            return Objects.equals(this.arguments, other.arguments);
        }
    }

    private List<FieldBuilder<?>> fields() {
        List<FieldBuilder<?>> all = new ArrayList<>();
        for (CodeGenerator bb : members) {
            if (bb instanceof FieldBuilder<?>) {
                all.add((FieldBuilder<?>) bb);
            }
        }
        return all;
    }

    /**
     * Generate an implementation of <code>toString()</code> that simply
     * iterates all fields added and concatenates them.
     *
     * @return this
     */
    public ClassBuilder<T> autoToString() {
        members.add(CodeGenerator.lazy(() -> {
            MethodBuilder<Object> mb = new MethodBuilder<>(x -> {
                return null;
            }, "toString", Modifier.PUBLIC, Modifier.FINAL)
                    .returning("String")
                    .annotatedWith("Override").closeAnnotation();
            BlockBuilder<Object> bb = mb.body();
            StringConcatenationBuilder<Object> scb = bb.returningValue().stringConcatenation().literal(className()).append('(');
            List<FieldBuilder<?>> flds = fields();
            if (flds.isEmpty()) {
                scb.append("-empty-");
            } else {
                Collections.sort(flds, (a, b) -> {
                    return a.name.compareToIgnoreCase(b.name);
                });
                // do not use char appends here or they can result in adding
                // to an int if one precedes it
                for (Iterator<FieldBuilder<?>> it = fields().iterator(); it.hasNext();) {
                    FieldBuilder<?> fb = it.next();
                    String pfx;
                    if (fb.modifiers.contains(Modifier.STATIC)) {
                        pfx = this.className() + '.';
                    } else {
                        pfx = "this.";
                    }
                    pfx += fb.name;
                    scb.append(fb.name).append("=");
                    if (fb.looksLikeArray()) {
                        scb.appendExpression("(" + pfx + " == null ? \"null\" : "
                                + "java.util.Arrays.toString(" + pfx + "))");
                    } else {
                        scb.appendExpression(pfx);
                    }
                    if (it.hasNext()) {
                        scb.append(", ");
                    }
                }
            }
            scb.append(")");
            scb.endConcatenation();
            return mb;
        }));
        return this;
    }

    public static final class FieldBuilder<T> extends CodeGeneratorBase implements NamedMember {

        private final Function<FieldBuilder<T>, T> converter;
        private CodeGenerator type;
        private CodeGenerator initializer;
        private final Set<Modifier> modifiers = new TreeSet<>();
        private final String name;
        private Set<AnnotationBuilder> annotations = new LinkedHashSet<>();
        private String docComment;

        FieldBuilder(Function<FieldBuilder<T>, T> converter, String name) {
            this.converter = converter;
            this.name = name;
        }

        boolean looksLikeArray() {
            String t = Objects.toString(type);
            int bix = t.indexOf('[');
            int gix = t.indexOf('<');
            if (bix > 0) {
                if (gix < 0) {
                    return true;
                }
            }
            return false;
        }

        public String name() {
            return name;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 17 * hash + Objects.hashCode(this.name);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final FieldBuilder<?> other = (FieldBuilder<?>) obj;
            return Objects.equals(this.name, other.name);
        }

        boolean isStatic() {
            return modifiers.contains(Modifier.STATIC);
        }

        public AnnotationBuilder<FieldBuilder<T>> annotatedWith(String anno) {
            return annotatedWith(anno, new boolean[1]);
        }

        public ArrayLiteralBuilder<T> initializedAsArrayLiteral(String type) {
            return initializedAsArrayLiteral(type, new boolean[1]);
        }

        private ArrayLiteralBuilder<T> initializedAsArrayLiteral(String type, boolean[] built) {
            return new ArrayLiteralBuilder<>(alb -> {
                this.type = typeAndDimensions(alb);
                initializer = alb;
                built[0] = true;
                return converter.apply(this);
            }, type);
        }

        public LambdaBuilder<T> initializedAsLambda(String type) {
            return new LambdaBuilder<>(lb -> {
                this.type = parseTypeName(type);
                initializer = lb;
                return converter.apply(this);
            });
        }

        public T initializedAsLambda(String type, Consumer<? super LambdaBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            LambdaBuilder<Void> result = new LambdaBuilder<>(lb -> {
                this.type = parseTypeName(type);
                initializer = lb;
                h.set(converter.apply(this));
                return null;
            });
            c.accept(result);
            return h.get("Lambda not completed");
        }

        public T initializedAsArrayLiteral(String type, Consumer<? super ArrayLiteralBuilder<?>> c) {
            boolean[] built = new boolean[1];
            Holder<T> t = new Holder<>();
            ArrayLiteralBuilder<Void> bldr = new ArrayLiteralBuilder<>(alb -> {
                this.type = typeAndDimensions(alb);
                this.initializer = alb;
                built[0] = true;
                t.set(converter.apply(this));
                return null;
            }, type);
            c.accept(bldr);
            if (!built[0]) {
                bldr.closeArrayLiteral();
            }
            return t.get();
        }

        public FieldBuilder<T> annotatedWith(String anno, Consumer<? super AnnotationBuilder<?>> c) {
            boolean[] built = new boolean[1];
            AnnotationBuilder<?> ab = annotatedWith(anno, built);
            c.accept(ab);
            if (!built[0]) {
                ab.closeAnnotation();
            }
            return this;
        }

        private AnnotationBuilder<FieldBuilder<T>> annotatedWith(String anno, boolean[] built) {
            return new AnnotationBuilder<>(ab -> {
                annotations.add(ab);
                built[0] = true;
                return this;
            }, anno);
        }

        public FieldBuilder<T> initializedWith(Value value) {
            if (initializer != null) {
                throw new IllegalStateException("Initializer already set");
            }
            initializer = value;
            return this;
        }

        public T initializedWith(String stringLiteral) {
            if (initializer != null) {
                throw new IllegalStateException("Initializer already set");
            }
            initializer = new Adhoc(LinesBuilder.stringLiteral(stringLiteral));
            type = new TypeNameItem("String");
            return converter.apply(this);
        }

        public T initializedWith(char ch) {
            if (initializer != null) {
                throw new IllegalStateException("Initializer already set");
            }
            initializer = new Adhoc(LinesBuilder.escapeCharLiteral(ch));
            type = new TypeNameItem("char");
            return converter.apply(this);
        }

        public T initializedWith(Number num) {
            if (initializer != null) {
                throw new IllegalStateException("Initializer already set");
            }
            initializer = friendlyNumber(num);
            String typeName;
            if (num instanceof Integer) {
                typeName = "int";
            } else {
                typeName = num.getClass().getSimpleName().toLowerCase();
            }
            type = new TypeNameItem(typeName);
            return converter.apply(this);
        }

        public T initializedWith(boolean val) {
            if (initializer != null) {
                throw new IllegalStateException("Initializer already set");
            }
            initializer = new Adhoc(Boolean.toString(val));
            type = new TypeNameItem("boolean");
            return converter.apply(this);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.onNewLine();
            if (docComment != null) {
                writeDocComment(docComment, lines);
                lines.onNewLine();
            }
            for (AnnotationBuilder<?> ab : annotations) {
                ab.generateInto(lines);
            }
            lines.statement(lb -> {
                for (Modifier m : modifiers) {
                    lb.word(m.toString());
                }
                if (type != null) {
                    type.generateInto(lb);
                }
                lb.word(name);
                if (initializer != null) {
                    lb.wrappable(lbb -> {
                        if (!(initializer instanceof AssignmentBuilder<?>)) {
                            lbb.word("=");
                        }
                        initializer.generateInto(lbb);
                    });
                }
            });
        }

        public FieldBuilder<T> initializedTo(String expression) {
            if (this.initializer != null) {
                throw new IllegalStateException("Initializer already set");
            }
            this.initializer = new Adhoc(expression);
            return this;
        }

        public FieldBuilder<T> initializedFromInvocationOf(String method, Consumer<? super InvocationBuilder<?>> c) {
            boolean[] built = new boolean[1];
            InvocationBuilder<?> bldr = initializedFromInvocationOf(method, built);
            c.accept(bldr);
            if (!built[0]) {
                throw new IllegalStateException("Invocation builder was not completed (call on())");
            }
            return this;
        }

        public InvocationBuilder<FieldBuilder<T>> initializedFromInvocationOf(String method) {
            return initializedFromInvocationOf(method, new boolean[1]);
        }

        public NewBuilder<FieldBuilder<T>> initializedWithNew() {
            return new NewBuilder<>(nb -> {
                this.initializer = nb;
                if (this.type == null) {
                    this.type = parseTypeName(nb.name);
                }
                return this;
            });
        }

        public FieldBuilder<T> initializedWithNew(Consumer<? super NewBuilder<Void>> c) {
            Holder<FieldBuilder<T>> h = new Holder<>();
            NewBuilder<Void> result = new NewBuilder<>(nb -> {
                this.initializer = nb;
                if (this.type == null) {
                    this.type = parseTypeName(nb.name);
                }
                h.set(this);
                return null;
            });
            c.accept(result);
            return h.get("NewBuilder not completed");
        }

        private InvocationBuilder<FieldBuilder<T>> initializedFromInvocationOf(String method, boolean[] built) {
            return new InvocationBuilder<>(ib -> {
                this.initializer = ib;
                built[0] = true;
                return this;
            }, method);
        }

        public AssignmentBuilder<T> assignedTo() {
            return new AssignmentBuilder<>(ab -> {
                initializer = ab;
                this.type = ab.type;
                return converter.apply(this);
            }, null);
        }

        public FieldBuilder<T> withModifier(Modifier mod, Modifier... mods) {
            addModifier(mod);
            for (Modifier m : mods) {
                addModifier(m);
            }
            return this;
        }

        public FieldBuilder<T> docComment(String docComment) {
            this.docComment = docComment;
            return this;
        }

        private void addModifier(Modifier mod) {
            switch (mod) {
                case ABSTRACT:
                case DEFAULT:
                case NATIVE:
                case STRICTFP:
                case SYNCHRONIZED:
                    throw new IllegalArgumentException("Inappropriate modifier for field: " + mod);
                case VOLATILE:
                    if (modifiers.contains(FINAL)) {
                        throw new IllegalStateException("Cannot combined volatile and final");
                    }
                    break;
                case FINAL:
                    if (modifiers.contains(VOLATILE)) {
                        throw new IllegalStateException("Cannot combined volatile and final");
                    }
                    break;
                case PRIVATE:
                    if (modifiers.contains(PROTECTED) || modifiers.contains(PUBLIC)) {
                        throw new IllegalStateException("Cannot be private and also protected or public");
                    }
                    break;
                case PROTECTED:
                    if (modifiers.contains(PRIVATE) || modifiers.contains(PUBLIC)) {
                        throw new IllegalStateException("Cannot be private and also protected or public");
                    }
                    break;
                case PUBLIC:
                    if (modifiers.contains(PRIVATE) || modifiers.contains(PROTECTED)) {
                        throw new IllegalStateException("Cannot be private and also protected or public");
                    }
                    break;
            }
            modifiers.add(mod);
        }

        public T ofType(String type) {
            this.type = parseTypeName(type);
            return converter.apply(this);
        }
    }

    static final class ClassBuilderStringFunction implements Function<ClassBuilder<String>, String> {
        // Type is used for top level test

        @Override
        public String apply(ClassBuilder<String> cb) {
            return cb.text();
        }
    }

    static class StatementWrapper extends CodeGeneratorBase {

        private final CodeGenerator bb;

        StatementWrapper(CodeGenerator... multi) {
            this(new Composite(multi));
        }

        StatementWrapper(CodeGenerator bb) {
            this.bb = bb;
        }

        StatementWrapper(String what) {
            this(new Adhoc(what));
        }

        StatementWrapper(String a, String b) {
            this(new Adhoc(a), new Adhoc(b));
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            if (bb instanceof Composite) {
                Composite comp = (Composite) bb;
                if (comp.isEmpty()) {
                    return;
                }
            }
            lines.onNewLine();
            bb.generateInto(lines);
            lines.backup();
            lines.statementTerminator();
        }
    }

    static final class Pair<A, B> {

        final A a;
        final B b;

        Pair(A a, B b) {
            this.a = a;
            this.b = b;
        }
    }

    public static final class BackupAndAppendRaw implements CodeGenerator {

        private final String what;

        BackupAndAppendRaw(String what) {
            this.what = what;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.backup();
            lines.appendRaw(what);
        }

        @Override
        public String toString() {
            return "";
        }
    }

    static final class ReturnStatement extends CodeGeneratorBase {

        private final CodeGenerator what;

        ReturnStatement(CodeGenerator what) {
            this.what = what;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.statement(lb -> {
                lb.onNewLine();
                lb.word("return ");
                what.generateInto(lb);
                lb.backup();
            });
        }
    }

    private static final Pattern ARR = Pattern.compile("^\\s*?(\\S+)\\s*?\\[\\s*?\\]\\s*$");

    private static String checkIdentifier(String name) {
        return checkIdentifier(name, false);
    }

    private static String checkIdentifier(String name, boolean emptyOk) {
        if (name == null) {
            throw new IllegalArgumentException("Null identifier");
        }
        if (name.trim().isEmpty()) {
            if (emptyOk) {
                return name;
            }
            throw new IllegalArgumentException("Empty or all-whitespace "
                    + "identifier: '" + name + "'");
        }
        Matcher arrM = ARR.matcher(name);
        if (arrM.find()) {
            checkIdentifier(arrM.group(1));
            return name;
        }
        if (name.indexOf('<') > 0) {
            visitTypeNames(name, nm -> {
                checkIdentifier(nm, true);
            });
            return name;
        } else if (name.indexOf('.') > 0) {
            for (String nm : name.split("\\.")) {
                checkIdentifier(nm, false);
            }
            return name;
        }
        int max = name.length();
        for (int i = 0; i < max; i++) {
            char c = name.charAt(i);
            if (i == 0 && !Character.isJavaIdentifierStart(c)) {
                throw new IllegalArgumentException("A java identifier cannot start with '" + c + "': " + name);
            } else if (!Character.isJavaIdentifierPart(c)) {
                throw new IllegalArgumentException("A java identifier cannot contain '" + c + "': " + name);
            }
        }
        return name;
    }

    static CodeGenerator friendlyNumber(Number num) {
        CodeGenerator result;
        if (num instanceof Long) {
            if (num.longValue() == Long.MAX_VALUE) {
                result = new Adhoc("Long.MAX_VALUE");
            } else if (num.longValue() == Long.MIN_VALUE) {
                result = new Adhoc("Long.MIN_VALUE");
            } else {
                result = new Adhoc(friendlyLong((Long) num) + "L");
            }
        } else if (num instanceof Integer) {
            if (num.intValue() == Integer.MAX_VALUE) {
                return new Adhoc("Integer.MAX_VALUE");
            } else if (num.intValue() == Integer.MIN_VALUE) {
                return new Adhoc("Integer.MIN_VALUE");
            } else {
                result = new Adhoc(friendlyInt(num));
            }
        } else if (num instanceof Double) {
            if (num.doubleValue() == Double.MAX_VALUE) {
                return new Adhoc("Integer.MAX_VALUE");
            } else if (num.doubleValue() == Double.MIN_VALUE) {
                return new Adhoc("Double.MIN_VALUE");
            } else {
                result = new Adhoc(num.toString() + "D");
            }
        } else if (num instanceof Float) {
            if (num.floatValue() == Float.MAX_VALUE) {
                return new Adhoc("Float.MAX_VALUE");
            } else if (num.floatValue() == Float.MIN_VALUE) {
                return new Adhoc("Float.MIN_VALUE");
            } else {
                result = new Adhoc(num.toString() + "F");
            }
        } else if (num instanceof Short) {
            if (num.shortValue() == Short.MAX_VALUE) {
                return new Adhoc("Short.MAX_VALUE");
            } else if (num.shortValue() == Short.MIN_VALUE) {
                return new Adhoc("Short.MIN_VALUE");
            } else {
                result = new Adhoc("(short) " + Short.toString(num.shortValue()));
            }
        } else if (num instanceof Byte) {
            if (num.byteValue() == Byte.MAX_VALUE) {
                result = new Adhoc("Byte.MAX_VALUE");
            } else if (num.byteValue() == Byte.MIN_VALUE) {
                result = new Adhoc("Byte.MIN_VALUE");
            } else {
//                result = new Adhoc("(byte) " + Integer.toString(num.byteValue()));
                result = new Adhoc(friendlyByte(num.byteValue()));
            }
        } else {
            result = new Adhoc(num.toString());
        }
        return result;
    }

    static String friendlyChar(char c) {
        switch (c) {
            case Character.MIN_VALUE:
                return "Character.MIN_VALUE";
            case Character.MAX_VALUE:
                return "Character.MAX_VALUE";
            case Character.MAX_HIGH_SURROGATE:
                return "Character.MAX_HIGH_SURROGATE";
            case Character.MAX_LOW_SURROGATE:
                return "Character.MAX_LOW_SURROGATE";
            case Character.MIN_LOW_SURROGATE:
                return "Character.MIN_LOW_SURROGATE";
            case Character.MIN_HIGH_SURROGATE:
                return "Character.MIN_HIGH_SURROGATE";
            default:
                return LinesBuilder.escapeCharLiteral(c);
        }
    }

    static String friendlyByte(byte b) {
        int val = b & 0x000000FF;
        String result = Integer.toHexString(val);
        if (result.length() == 1) {
            return "0x0" + result;
        } else {
            return "(byte) 0x" + result;
        }
    }

    static String friendlyLong(Number lng) {
        String s = Long.toString(lng.longValue(), 10);
        boolean neg = s.charAt(0) == '-';
        if (neg) {
            s = s.substring(1);
        }
        int max = s.length();
        if (max > 3) {
            StringBuilder sb = new StringBuilder();
            for (int i = max - 1; i >= 0; i--) {
                sb.insert(0, s.charAt(i));
                if (i != 0 && (i + 1) % 3 == 0) {
                    sb.insert(0, '_');
                }
            }
            if (neg) {
                sb.insert(0, '-');
            }
            return sb.toString();
        } else if (neg) {
            s = "-" + s;
        }
        return s;
    }

    static String friendlyInt(Number lng) {
        String s = Integer.toString(lng.intValue(), 10);
        boolean neg = s.charAt(0) == '-';
        if (neg) {
            s = s.substring(1);
        }
        int max = s.length();
        if (max > 3) {
            StringBuilder sb = new StringBuilder();
            for (int i = max - 1; i >= 0; i--) {
                sb.insert(0, s.charAt(i));
                if (i != 0 && (i + 1) % 3 == 0) {
                    sb.insert(0, '_');
                }
            }
            if (neg) {
                sb.insert(0, '-');
            }
            return sb.toString();
        } else if (neg) {
            s = "-" + s;
        }
        return s;
    }

    private static void writeDocComment(String docComment, LinesBuilder lb) {
        if (docComment == null) {
            return;
        }
        lb.onNewLine();
        lb.appendRaw("/**");
        lb.onNewLine().appendRaw(" * ");
        lb.withWrapPrefix(" * ", lb1 -> {
            boolean firstParam = true;
            boolean anyParagraphs = false;
            boolean lastWasPara = false;
            boolean inParams = false;
            String[] lines = docComment.split("\n");

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    String next = i == lines.length - 1 ? null : lines[i + 1].trim();
                    if (next.isEmpty()) {
                        continue;
                    } else if (next.startsWith("<")) {
                        lb.onNewLine();
                    } else if (next.startsWith("@")) {
                        if (anyParagraphs) {
                            lb.appendRaw("</p>");
                            lb.onNewLine();
                        }
                    } else {
                        lb.onNewLine();
                        lb.word("</p><p>");
                        lb.onNewLine();
                        lastWasPara = true;
                        anyParagraphs = true;
                    }
                    continue;
                }
                if (firstParam && (line.startsWith("@param") || line.startsWith("@return"))) {
                    if (anyParagraphs) {
                        anyParagraphs = false;
                        lb.backup().appendRaw("</p>");
                    }
                    inParams = true;
                    lb.onNewLine();
                    firstParam = false;
                }
                for (String word : line.split("\\s")) {
                    lb.word(word, false);
                }

                if (!lastWasPara && i != lines.length - 1) {
                    boolean nextIsTag = lines[i + 1].trim().startsWith("<")
                            || lines[i + 1].trim().startsWith("@");
                    if (!nextIsTag) {
                        if (anyParagraphs) {
                            lb.onNewLine();
                            lb.word("</p><p>");
                            lb.onNewLine();
                            anyParagraphs = true;
                            lastWasPara = true;
                        } else {
                            lb.onNewLine();
                            if (!inParams) {
                                lb.word("<p>");
                                anyParagraphs = true;
                                lb.onNewLine();
                                lastWasPara = true;
                            }
                        }
                    }
                } else {
                    lastWasPara = false;
                }
                if (i != lines.length - 1) {
                    lb.onNewLine();
                }
            }
            if (anyParagraphs) {
                lb.appendRaw("</p>");
            }
        });
        lb.onNewLine();
        lb.appendRaw(" **/");
    }

    /**
     * Create an instance of Variable, which simplifies composing mathematical
     * expressions, and can be used in any method that accepts an instance of
     * Value.
     *
     * @param varName The variable name
     * @return A variable
     */
    public static Variable variable(String varName) {
        return new Variable(notNull("varName", varName));
    }

    /**
     * Parenthesize an instance of Value, which simplifies composing
     * mathematical expressions, and can be used in any method that accepts an
     * instance of Value.
     *
     * @param value the value
     * @return A variable
     */
    public static Parenthesized parenthesize(Value value) {
        return new Parenthesized(notNull("value", value));
    }

    /**
     * Create an instance of NumberLiteral, which simplifies composing
     * mathematical expressions, and can be used in any method that accepts an
     * instance of Value.
     *
     * @param varName The variable name
     * @return A variable
     */
    public static NumberLiteral number(Number number) {
        return new NumberLiteral(notNull("number", number));
    }

    public static InvocationBuilder<Value> invocationOf(String what) {
        return new InvocationBuilder<>(ib -> {
            return new Wrapper(ib);
        }, what);
    }

    public interface Value extends CodeGenerator {

        default Value times(String expression) {
            return times(new Variable(expression));
        }

        default Value modulo(String expression) {
            return modulo(new Variable(expression));
        }

        default Value dividedBy(String expression) {
            return dividedBy(new Variable(expression));
        }

        default Value plus(String expression) {
            return plus(new Variable(expression));
        }

        default Value minus(String expression) {
            return minus(new Variable(expression));
        }

        default Value or(String expression) {
            return or(new Variable(expression));
        }

        default Value xor(String expression) {
            return xor(new Variable(expression));
        }

        default Value and(String expression) {
            return and(new Variable(expression));
        }

        default Value times(Number expression) {
            return times(new NumberLiteral(expression));
        }

        default Value modulo(Number expression) {
            return modulo(new NumberLiteral(expression));
        }

        default Value dividedBy(Number expression) {
            return dividedBy(new NumberLiteral(expression));
        }

        default Value plus(Number expression) {
            return plus(new NumberLiteral(expression));
        }

        default Value minus(Number expression) {
            return minus(new NumberLiteral(expression));
        }

        default Value or(Number expression) {
            return or(new NumberLiteral(expression));
        }

        default Value xor(Number expression) {
            return xor(new NumberLiteral(expression));
        }

        default Value and(Number expression) {
            return and(new NumberLiteral(expression));
        }

        default Value times(Value expression) {
            return new Operation(this, Operators.TIMES, expression);
        }

        default Value modulo(Value expression) {
            return new Operation(this, Operators.MODULO, expression);
        }

        default Value dividedBy(Value expression) {
            return new Operation(this, Operators.DIVIDED_BY, expression);
        }

        default Value plus(Value expression) {
            return new Operation(this, Operators.PLUS, expression);
        }

        default Value minus(Value expression) {
            return new Operation(this, Operators.MINUS, expression);
        }

        default Value or(Value expression) {
            return new Operation(this, BitwiseOperators.OR, expression);
        }

        default Value xor(Value expression) {
            return new Operation(this, BitwiseOperators.XOR, expression);
        }

        default Value and(Value expression) {
            return new Operation(this, BitwiseOperators.AND, expression);
        }

        default Value rotate(Value expression) {
            return new Operation(this, BitwiseOperators.ROTATE, expression);
        }

        default Value shiftLeft(Value expression) {
            return new Operation(this, BitwiseOperators.SHIFT_LEFT, expression);
        }

        default Value shiftRight(Value expression) {
            return new Operation(this, BitwiseOperators.SHIFT_RIGHT, expression);
        }

        default Value complement() {
            Value target = isCompound() ? parenthesized() : this;
            return new UnaryOp(target, Unaries.COMPLEMENT);
        }

        default Value parenthesized() {
            return this instanceof Parenthesized ? this : new Parenthesized(this);
        }

        default Value castToLong() {
            return new ValueWithCast(this, "long");
        }

        default Value castToInt() {
            return new ValueWithCast(this, "int");
        }

        default Value castToShort() {
            return new ValueWithCast(this, "short");
        }

        default Value castToByte() {
            return new ValueWithCast(this, "byte");
        }

        default Value castToFloat() {
            return new ValueWithCast(this, "float");
        }

        default Value castToDouble() {
            return new ValueWithCast(this, "double");
        }

        boolean isCompound();
    }

    private static class ValueWithCast extends AbstractValue {

        private final Value target;
        private final String type;

        ValueWithCast(Value target, String type) {
            this.target = target;
            this.type = type;
        }

        @Override
        public boolean isCompound() {
            return target.isCompound();
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.parens(lb -> {
                lb.appendRaw(type);
                lines.appendRaw(' ');
                if (target.isCompound()) {
                    target.parenthesized().generateInto(lines);
                } else {
                    target.generateInto(lines);
                }
            });
        }
    }

    static abstract class AbstractValue implements Value, CodeGenerator {

        @Override
        public String toString() {
            LinesBuilder lb = new LinesBuilder();
            generateInto(lb);
            return lb.toString();
        }
    }

    private static class Wrapper extends AbstractValue {

        private final CodeGenerator bb;

        Wrapper(CodeGenerator bb) {
            this.bb = bb;
        }

        @Override
        public boolean isCompound() {
            return true;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            bb.generateInto(lines);
        }
    }

    public static class Variable extends AbstractValue {

        private final String name;

        private Variable(String s) {
            this.name = checkIdentifier(s);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.word(name);
        }

        public Value preIncrement() {
            return new UnaryOp(this, Unaries.PREINCREMENT);
        }

        public Value postIncrement() {
            return new UnaryOp(this, Unaries.POSTINCREMENT);
        }

        public Value preDecrement() {
            return new UnaryOp(this, Unaries.PREDECREMENT);
        }

        public Value postDecrement() {
            return new UnaryOp(this, Unaries.POSTDECREMENT);
        }

        @Override
        public boolean isCompound() {
            return false;
        }
    }

    public static class NumberLiteral extends AbstractValue {

        private final Number num;

        NumberLiteral(Number num) {
            this.num = num;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            ClassBuilder.friendlyNumber(num).generateInto(lines);
        }

        @Override
        public boolean isCompound() {
            return false;
        }
    }

    static class Parenthesized extends AbstractValue {

        List<CodeGenerator> contents = new ArrayList<>(3);

        Parenthesized(Value cts) {
            this.contents.add(cts);
        }

        Parenthesized(Collection<? extends CodeGenerator> cts) {
            contents.addAll(cts);
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            if (contents.isEmpty()) {
                return;
            }
            lines.parens(lb -> {
                for (CodeGenerator bb : contents) {
                    bb.generateInto(lb);
                }
            });
        }

        @Override
        public boolean isCompound() {
            return false;
        }
    }

    static class UnaryOp extends AbstractValue {

        private final Value target;
        private final Unaries unary;

        UnaryOp(Value target, Unaries unary) {
            this.target = target;
            this.unary = unary;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            if (unary.isPre()) {
                unary.generateInto(lines);
                lines.backup();
                if (target instanceof Variable) {
                    lines.appendRaw(target.toString());
                } else {
                    target.generateInto(lines);
                }
            } else {
                if (target instanceof Variable) {
                    lines.appendRaw(target.toString().trim());
                } else {
                    target.generateInto(lines);
                }
                lines.backup();
                unary.generateInto(lines);
            }
        }

        @Override
        public boolean isCompound() {
            return false;
        }
    }

    private static enum Unaries implements CodeGenerator {
        PREINCREMENT("++"),
        POSTINCREMENT("++"),
        PREDECREMENT("--"),
        POSTDECREMENT("--"),
        COMPLEMENT("~");
        private final String text;

        Unaries(String text) {
            this.text = text;
        }

        boolean isMutative() {
            return this != COMPLEMENT;
        }

        boolean isPre() {
            switch (this) {
                case POSTDECREMENT:
                case POSTINCREMENT:
                    return false;
                default:
                    return true;
            }
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            lines.appendRaw(text);
        }
    }

    static class Operation extends AbstractValue {

        private final Value leftSide;
        private final CodeGenerator op;
        private final Value rightSide;

        Operation(Value leftSide, CodeGenerator op, Value rightSide) {
            this.op = op;
            this.leftSide = leftSide;
            this.rightSide = rightSide;
        }

        @Override
        public boolean isCompound() {
            return true;
        }

        @Override
        public void generateInto(LinesBuilder lines) {
            leftSide.generateInto(lines);
            op.generateInto(lines);
            rightSide.generateInto(lines);
        }
    }
}
