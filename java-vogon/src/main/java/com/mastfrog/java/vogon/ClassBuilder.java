package com.mastfrog.java.vogon;

import static com.mastfrog.java.vogon.ClassBuilder.BitwiseOperators.COMPLEMENT;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
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
public final class ClassBuilder<T> implements BodyBuilder {

    private final String name;
    private final String pkg;
    private final List<BodyBuilder> members = new LinkedList<>();
    private final Set<String> imports = new TreeSet<>();
    private final Set<Modifier> modifiers = new TreeSet<>();
    private final Function<ClassBuilder<T>, T> converter;
    private String extendsType;
    private final Set<String> implementsTypes = new LinkedHashSet<>();
    private final Set<BodyBuilder> annotations = new LinkedHashSet<>();
    private String docComment;
    private String classType = "class";
    private boolean loggerField;
    private static ThreadLocal<ClassBuilder<?>> CONTEXT = new ThreadLocal<>();
    private ClassBuilder<?> prev;
    private boolean generateDebugCode;
    private final Set<String> typeParams = new LinkedHashSet<>();

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

    String methodSource(String name) { // for tests
        for (BodyBuilder bb : members) {
            if (bb instanceof MethodBuilder<?>) {
                MethodBuilder<?> mb = (MethodBuilder<?>) bb;
                if (name.equals(mb.name)) {
                    LinesBuilder lb = new LinesBuilder();
                    mb.buildInto(lb);
                    return lb.toString();
                }
            }
        }
        return "<did not find method '" + name + "'>";
    }

    public AnnotationMethodBuilder<ClassBuilder<T>> annotationMethod(String method) {
        return new AnnotationMethodBuilder<>(amb -> {
            members.add(amb);
            return this;
        }, method);
    }

    public ClassBuilder<T> withTypeParameters(String first, String... more) {
        typeParams.add(first);
        typeParams.addAll(Arrays.asList(more));
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

    public ClassBuilder<ClassBuilder<T>> innerClass(String name) {
        return new ClassBuilder<>(null, name, cb -> {
            members.add(cb);
            return ClassBuilder.this;
        });
    }

    public ClassBuilder<T> innerClass(String name, Consumer<? super ClassBuilder<?>> c) {
        boolean[] built = new boolean[1];
        ClassBuilder<Void> bldr = new ClassBuilder<>(null, name, cb -> {
            members.add(cb);
            built[0] = true;
            return null;
        });
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

    private final List<ConstructorBuilder<?>> constructors = new LinkedList<>();

    /**
     * Creates a private constructor which simply throws an assertion error.
     *
     * @return
     */
    public ClassBuilder<T> utilityClassConstructor() {
        return constructor().setModifier(PRIVATE).body()
                .statement("throw new AssertionError()").endBlock();
    }

    public ClassBuilder<T> constructor(Consumer<? super ConstructorBuilder<?>> c) {
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
        return new ConstructorBuilder<>(cb -> {
            if (contains(cb)) {
                throw new IllegalStateException("Already have a constructor with arguments (" + cb.sig() + ")");
            }
            constructors.add(cb);
            built[0] = true;
            return ClassBuilder.this;
        });
    }

    private EnumConstantBuilder<ClassBuilder<T>> constants;

    public EnumConstantBuilder<ClassBuilder<T>> enumConstants() {
        if (constants != null) {
            return constants;
        }
        return new EnumConstantBuilder<>(ecb -> {
            constants = ecb;
            return ClassBuilder.this;
        });
    }

    public BlockBuilder<ClassBuilder<T>> staticBlock() {
        return staticBlock(new boolean[1]);
    }

    public ClassBuilder<T> staticBlock(Consumer<? super BlockBuilder<?>> c) {
        boolean[] built = new boolean[1];
        BlockBuilder<ClassBuilder<T>> block = staticBlock(built);
        c.accept(block);
        if (!built[0]) {
            throw new IllegalStateException("endBlock not called on static block - will not be added");
        }
        return this;
    }

    private BlockBuilder<ClassBuilder<T>> staticBlock(boolean[] built) {
        return new BlockBuilder<>(bb -> {
            members.add(new Composite(new Adhoc("static"), bb, new Dnl()));
            built[0] = true;
            return ClassBuilder.this;
        }, true);
    }

    public BlockBuilder<ClassBuilder<T>> block() {
        return block(new boolean[1]);
    }

    public ClassBuilder<T> block(Consumer<? super BlockBuilder<?>> c) {
        boolean[] built = new boolean[1];
        BlockBuilder<?> bldr = block(built);
        c.accept(bldr);
        if (!built[0]) {
            throw new IllegalStateException("Block not closed");
        }
        return this;
    }

    private BlockBuilder<ClassBuilder<T>> block(boolean[] built) {
        return new BlockBuilder<>(bb -> {
            members.add(bb);
            built[0] = true;
            return this;
        }, true);
    }

    static final class Composite implements BodyBuilder {

        private final BodyBuilder[] contents;

        Composite(BodyBuilder... all) {
            this.contents = all;
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            for (int i = 0; i < contents.length; i++) {
                contents[i].buildInto(lines);
            }
        }
    }

    static final class Dnl implements BodyBuilder {

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.doubleNewline();
        }
    }

    public static final class EnumConstantBuilder<T> implements BodyBuilder {

        private final Function<EnumConstantBuilder<T>, T> converter;
        private final Set<BodyBuilder> constants = new LinkedHashSet<>();

        EnumConstantBuilder(Function<EnumConstantBuilder<T>, T> converter) {
            this.converter = converter;
        }

        public EnumConstantBuilder<T> add(String name) {
            constants.add(new Adhoc(name));
            return this;
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
        public void buildInto(LinesBuilder lines) {
            Iterator<BodyBuilder> it = constants.iterator();
            while (it.hasNext()) {
                BodyBuilder bb = it.next();
                bb.buildInto(lines);
                if (it.hasNext()) {
                    lines.appendRaw(",");
                }
            }
            lines.appendRaw(";");
        }
    }

    public static final class ConstructorBuilder<T> implements BodyBuilder {

        private final Function<ConstructorBuilder<T>, T> converter;
        private BlockBuilder<?> body;
        private final Set<AnnotationBuilder<?>> annotations = new LinkedHashSet<>();
        private final Map<String, BodyBuilder> arguments = new LinkedHashMap<>();
        private final Set<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
        private final Set<String> throwing = new TreeSet<>();

        ConstructorBuilder(Function<ConstructorBuilder<T>, T> converter) {
            this.converter = converter;
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
            arguments.put(checkIdentifier(notNull("name", name)), parseTypeName(type));
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
        public void buildInto(LinesBuilder lines) {
            lines.parens(lb -> {
                for (Iterator<Map.Entry<String, BodyBuilder>> it = arguments.entrySet().iterator(); it.hasNext();) {
                    Map.Entry<String, BodyBuilder> e = it.next();
                    e.getValue().buildInto(lb);
                    lb.word(e.getKey());
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
                body.buildInto(lines);
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
            for (AnnotationBuilder<?> ab : annotations) {
                ab.buildInto(lb);
                lb.onNewLine();
            }
            for (Modifier m : modifiers) {
                lb.word(m.toString());
            }
            lb.word(name);
            buildInto(lb);
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
            if (!Objects.equals(this.arguments, other.arguments)) {
                return false;
            }
            return true;
        }

        private String sig() {
            LinesBuilder lb = new LinesBuilder();
            for (Iterator<Map.Entry<String, BodyBuilder>> it = arguments.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, BodyBuilder> e = it.next();
                e.getValue().buildInto(lb);
                lb.word(e.getKey());
                if (it.hasNext()) {
                    lb.appendRaw(',');
                }
            }
            return lb.toString();
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

    public ClassBuilder<T> importing(Iterable<String> types) {
        for (String type : types) {
            importing(type);
        }
        return this;
    }

    public ClassBuilder<T> importing(String className, String... more) {
        if (!(converter.getClass() == ClassBuilderStringFunction.class)) {
            throw new IllegalStateException("Imports may only be added to top level classes");
        }
        imports.add(className);
        for (String s : more) {
            imports.add(s);
        }
        return this;
    }

    public ClassBuilder<T> staticImport(String... more) {
        if (!(converter.getClass() == ClassBuilderStringFunction.class)) {
            throw new IllegalStateException("Imports may only be added to top level classes");
        }
        for (String s : more) {
            imports.add("static " + s);
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
                if (converter.getClass() == ClassBuilderStringFunction.class) {
                    throw new IllegalStateException("Top level classes may not be declared static");
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
    public void buildInto(LinesBuilder lines) {
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
            for (BodyBuilder anno : annotations) {
                lines.onNewLine();
                anno.buildInto(lines);
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
                lines.word(it.next());
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
                constants.buildInto(lines);
            }
            if (loggerField && converter.getClass() == ClassBuilderStringFunction.class) {
                FieldBuilder<Void> fb = new FieldBuilder<>(f -> {
                    return null;
                }, "LOGGER");
                fb.withModifier(PRIVATE).withModifier(STATIC).withModifier(FINAL)
                        .initializedTo("Logger.getLogger(" + LinesBuilder.stringLiteral(fqn()) + ")")
                        .ofType("Logger");
                fb.buildInto(lines);
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
            for (BodyBuilder bb : this.members) {
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
                        st.buildInto(lb);
                    }
                    lb.doubleNewline();
                }
                for (FieldBuilder<?> i : instanceFields) {
                    i.buildInto(lb);
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
            for (BodyBuilder bb : this.members) {
                if (!(bb instanceof FieldBuilder<?>)) {
                    bb.buildInto(lines);
                }
            }
        });
    }

    public String text() {
        LinesBuilder lb = new LinesBuilder();
        buildInto(lb);
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
                throw new IllegalStateException("Already have a field " + fb.name + " in " + name);
            }
            members.add(fb);
            built[0] = true;
            return ClassBuilder.this;
        }, name);
    }

    public ClassBuilder<T> method(String name, Consumer<? super MethodBuilder<?>> c) {
        boolean[] built = new boolean[1];
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

    private boolean contains(BodyBuilder bb) {
        // Usualy, repeated add bugs will be on the most recent element
        // so faster toExpression start from the last
        int max = members.size();
        for (int i = max - 1; i >= 0; i--) {
            BodyBuilder b = members.get(i);
            if (b == bb || b.equals(bb)) {
                return true;
            }
        }
        return false;
    }

    private MethodBuilder<ClassBuilder<T>> method(String name, boolean[] built, Modifier... modifiers) {
        Exception[] prev = new Exception[1];
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

    public ClassBuilder<T> conditionally(boolean test, Consumer<? super ClassBuilder<?>> c) {
        if (test) {
            c.accept(this);
        }
        return this;
    }

    public static final class FieldReferenceBuilder<T> implements BodyBuilder {

        private final String name;
        private BodyBuilder referent;
        private final Function<FieldReferenceBuilder<T>, T> converter;
        private final List<Integer> arrayElements = new ArrayList<>();

        FieldReferenceBuilder(String name, Function<FieldReferenceBuilder<T>, T> converter) {
            this.name = name;
            this.converter = converter;
        }

        private T setReferent(BodyBuilder bb) {
            this.referent = bb;
            return converter.apply(this);
        }

        public T of(String what) {
            return setReferent(new Adhoc(what));
        }

        public T ofThis() {
            return of("this");
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
            arrayElements.add(index);
            return this;
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            referent.buildInto(lines);
            lines.backup().appendRaw('.').backup();
            lines.appendRaw(name);
            for (Integer index : arrayElements) {
                lines.backup().appendRaw("[" + index + "]");
            }
        }
    }

    public static final class AnnotationMethodBuilder<T> implements BodyBuilder {

        private final Function<? super AnnotationMethodBuilder<T>, T> converter;
        private final String name;
        private BodyBuilder defaultValue;
        private BodyBuilder type;

        public AnnotationMethodBuilder(Function<? super AnnotationMethodBuilder<T>, T> converter, String name) {
            this.converter = converter;
            this.name = checkIdentifier(notNull("name", name));
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.onNewLine();
            type.buildInto(lines);
            lines.word(name);
            lines.appendRaw("()");
            if (defaultValue != null) {
                lines.word("default");
                defaultValue.buildInto(lines);
            }
            lines.backup().appendRaw(';');
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

    public static final class MethodBuilder<T> implements BodyBuilder {

        private final Function<MethodBuilder<T>, T> converter;
        private final Set<Modifier> modifiers = new TreeSet<>();
        private final Set<String> typeParams = new LinkedHashSet<>();
        private final Set<BodyBuilder> annotations = new LinkedHashSet<>();
        private final Set<BodyBuilder> throwing = new LinkedHashSet<>();
        private BodyBuilder block;
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

        public MethodBuilder<T> docComment(String cmt) {
            if (this.docComment != null) {
                this.docComment += "\n" + cmt;
            } else {
                this.docComment = cmt;
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
            if (typeParams.contains(tp)) {
                throw new IllegalStateException("Already have a type parameter '" + tp + " - cannot be added twice");
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

        private List<BodyBuilder[]> args = new LinkedList<>();

        public MethodBuilder<T> addArgument(String type, String name) {
            args.add(new BodyBuilder[]{parseTypeName(notNull("type", type)),
                new Adhoc(checkIdentifier(notNull("name", name)), true)});
            return this;
        }

        public T emptyBody() {
            return body().lineComment("do nothing").endBlock();
        }

        public MethodBuilder<T> annotatedWith(String annotationType, Consumer<? super AnnotationBuilder<?>> c) {
            boolean[] built = new boolean[1];
            AnnotationBuilder<MethodBuilder<T>> bldr = annotatedWith(annotationType, built);
            c.accept(bldr);
            if (!built[0]) {
                throw new IllegalStateException("closeAnnotation() not called");
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

        public MethodBuilder<T> conditionally(boolean test, Consumer<? super MethodBuilder<?>> c) {
            if (test) {
                c.accept(this);
            }
            return this;
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
            return body(new boolean[1]).returning(body).endBlock();
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
        public void buildInto(LinesBuilder lines) {
            lines.doubleNewline();
            if (docComment != null) {
                writeDocComment(docComment, lines);
                lines.onNewLine();
            }
            if (!annotations.isEmpty()) {
                for (BodyBuilder bb : annotations) {
                    bb.buildInto(lines);
                }
            }
            for (Modifier m : modifiers) {
                lines.word(m.toString());
            }
            if (!typeParams.isEmpty()) {
                lines.delimit('<', '>', lb -> {
                    lb.commaDelimit(typeParams.toArray(new String[typeParams.size()]));
                });
            }
            lines.word(type, true);
            lines.word(name, true);
            lines.hangingWrap(ll -> {
                lines.wrappable(lb1 -> {
                    lines.parens(lb -> {
                        for (Iterator<BodyBuilder[]> it = args.iterator(); it.hasNext();) {
                            BodyBuilder[] curr = it.next();
                            assert curr.length == 2;
                            curr[0].buildInto(lb);
                            curr[1].buildInto(lines);
                            if (it.hasNext()) {
                                lb.appendRaw(',');
                            }
                        }
                    });
                    if (!throwing.isEmpty()) {
                        lb1.word("throws");
                        for (Iterator<BodyBuilder> it = throwing.iterator(); it.hasNext();) {
                            BodyBuilder th = it.next();
                            th.buildInto(lb1);
                            if (it.hasNext()) {
                                lb1.appendRaw(",");
                            }
                        }
                    }
                });
            });
            if (block != null) {
                lines.block(lb -> {
                    block.buildInto(lb);
                });
            } else {
                lines.appendRaw(";");
            }
            lines.doubleNewline();
        }

        public String sig() {
            LinesBuilder lb = new LinesBuilder();
            for (Iterator<BodyBuilder[]> it = args.iterator(); it.hasNext();) {
                BodyBuilder[] curr = it.next();
                assert curr.length == 2;
                curr[0].buildInto(lb);
                curr[1].buildInto(lb);
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

    static BodyBuilder parseTypeName(String typeName) {
        Matcher arrM = ARRAY.matcher(notNull("typeName", typeName));
        if (arrM.find()) {
            String tn = arrM.group(1);
            String arrDecl = arrM.group(2);
            BodyBuilder result = parseTypeName(tn);
            return new Composite(result, new BackupRaw(arrDecl));
        }
        arrM = VARARG.matcher(typeName);
        if (arrM.find()) {
            String tn = arrM.group(1);
            BodyBuilder result = parseTypeName(tn);
            return new Composite(result, new BackupRaw("..."));
        }
        if (checkIdentifier(typeName).indexOf('<') >= 0) {
            GenericTypeVisitor gtv = new GenericTypeVisitor();
            visitGenericTypes(typeName, 0, gtv);
            return gtv.result();
        } else {
            return new TypeNameItem(typeName);
        }
    }

    public static void main(String[] args) {
//        String nm = "ThrowingTriPredicate<? super Envelope, ? super Channel, ThrowingBiConsumer<? super Throwable, Supplier<Acknowledgement>>>";
//        String nm = "int[]";
        String nm = "ThrowingTriFunction<MessageId, ? super T, ThrowingBiConsumer<? super Throwable, Acknowledgement>, Acknowledgement>";
        visitGenericTypes(nm, 0, (depth, part) -> {
            System.out.println(depth + ". " + part);
        });

        GenericTypeVisitor gen = new GenericTypeVisitor();
        visitGenericTypes(nm, 0, gen);
        System.out.println("\nresult:\n" + gen);
        LinesBuilder lb = new LinesBuilder();
        gen.result().buildInto(lb);
        System.out.println("GOT\n" + lb.toString());
        System.out.println(nm);

//        System.out.println("\n type names \n\n");
//        visitTypeNames(nm, tn -> {
//            System.out.println("NAME:\t" + tn);
//            System.out.println(nm);
//        });
//
//        visitTypeNames("int[]", tb -> {
//            System.out.println("GOTIT \n" + tb);
//        });
    }

    static class TypeNameItem implements BodyBuilder {

        private final String name;

        public TypeNameItem(String name) {
            this.name = name;
        }

        @Override
        public void buildInto(LinesBuilder lines) {
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
            if (!Objects.equals(this.name, other.name)) {
                return false;
            }
            return true;
        }

    }

    static class Punctuation implements BodyBuilder {

        private final char txt;

        public Punctuation(char txt) {
            this.txt = txt;
        }

        public String toString() {
            return Character.toString(txt);
        }

        @Override
        public void buildInto(LinesBuilder lines) {
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
            if (this.txt != other.txt) {
                return false;
            }
            return true;
        }
    }

    static class GenericTypeVisitor implements BiConsumer<Integer, String> {

        private int lastDepth;
        private final StringBuilder sb = new StringBuilder();
        private final Map<Integer, Integer> depthCounts = new HashMap<>();
        private static final Punctuation OPEN_ANGLE = new Punctuation('<');
        private static final Punctuation CLOSE_ANGLE = new Punctuation('>');
        private static final Punctuation COMMA = new Punctuation(',');

        private final List<BodyBuilder> items = new ArrayList<>();

        private int countForDepth(int depth) {
            return depthCounts.getOrDefault(depth, 0);
        }

        public BodyBuilder result() {
            done();
            return new Composite(items.toArray(new BodyBuilder[items.size()]));
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
                sb.append('>');
                items.add(CLOSE_ANGLE);
                closeDepth(lastDepth);
            }
            lastDepth = t;
            add(t, u);
        }
    }

    static void visitGenericTypes(String typeName, int depth, BiConsumer<Integer, String> c) {
        typeName = typeName.trim();
        int start = typeName.indexOf('<');
        int end = typeName.lastIndexOf('>');
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

    public static final class AssignmentBuilder<T> implements BodyBuilder {

        private final Function<AssignmentBuilder<T>, T> converter;
        private BodyBuilder type;
        private BodyBuilder assignment;
        private final BodyBuilder varName;

        AssignmentBuilder(Function<AssignmentBuilder<T>, T> converter, BodyBuilder varName) {
            this.converter = converter;
            this.varName = varName;
        }

        public AssignmentBuilder<T> withType(String type) {
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
            return toExpression(LinesBuilder.escapeCharLiteral(c));
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

        public ArrayLiteralBuilder<T> toArrayLiteral(String type) {
            return new ArrayLiteralBuilder<>(alb -> {
                this.assignment = alb;
                this.type = new Composite(alb.type, new BackupRaw("[]"));
                return converter.apply(this);
            }, type);
        }

        public T toArrayLiteral(String type, Consumer<ArrayLiteralBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            ArrayLiteralBuilder<Void> res = new ArrayLiteralBuilder<>(alb -> {
                this.assignment = alb;
                holder.set(converter.apply(this));
                this.type = new Composite(alb.type, new BackupRaw("[]"));
                return null;
            }, type);
            c.accept(res);
            if (!holder.isSet()) {
                res.closeArrayLiteral();
            }
            return holder.get("Array literal not completed");
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.onNewLine();
            lines.wrappable(lb -> {
                if (type != null && !(this.assignment instanceof ArrayLiteralBuilder)) {
                    type.buildInto(lb);
                }
                if (varName != null) {
                    varName.buildInto(lb);
                }
                lb.word("=");
                assignment.buildInto(lb);
            });
        }
    }

    public static final class ArrayLiteralBuilder<T> implements BodyBuilder {

        private final List<BodyBuilder> all = new LinkedList<>();
        private final Function<ArrayLiteralBuilder<T>, T> converter;
        private final BodyBuilder type;

        ArrayLiteralBuilder(Function<ArrayLiteralBuilder<T>, T> converter, String type) {
            this.converter = converter;
            this.type = type == null ? null : parseTypeName(type);
        }

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
        public void buildInto(LinesBuilder lines) {
            lines.word("new");
            type.buildInto(lines);
            lines.backup();
            lines.appendRaw("[]");
            lines.word("{");
            lines.wrappable(lb -> {
                for (Iterator<BodyBuilder> it = all.iterator(); it.hasNext();) {
                    BodyBuilder bb = it.next();
                    bb.buildInto(lb);
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

        public T on(String what) {
            this.on = new Adhoc(what);
            return converter.apply(cast());
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

    public static abstract class InvocationBuilderBase<T, B extends InvocationBuilderBase<T, B>> implements BodyBuilder {

        final Function<B, T> converter;
        String name;
        BodyBuilder on;
        List<BodyBuilder> arguments = new LinkedList<>();
        boolean isNew;

        InvocationBuilderBase(Function<B, T> converter, String name) {
            this(converter, name, false);
        }

        InvocationBuilderBase(Function<B, T> converter, String name, boolean isNew) {
            this.converter = converter;
            this.name = name;
            this.isNew = isNew;
        }

        @SuppressWarnings("unchecked")
        B cast() {
            return (B) this;
        }

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

        public B withArrayArgument(Consumer<? super ArrayValueBuilder<?>> c) {
            boolean[] built = new boolean[1];
            ArrayValueBuilder<?> av = withArrayArgument(built);
            c.accept(av);
            if (!built[0]) {
                av.closeArray();
            }
            return cast();
        }

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

        public NewBuilder<B> withNewInstanceArgument() {
            return new NewBuilder<>(nb -> {
                arguments.add(nb);
                return cast();
            });
        }

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

        public B withNewArrayArgument(String arrayType, Consumer<? super ArrayValueBuilder<?>> c) {
            boolean[] built = new boolean[1];
            ArrayValueBuilder<?> av = withNewArrayArgument(arrayType, built);
            c.accept(av);
            if (!built[0]) {
                av.closeArray();
            }
            return cast();
        }

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

        public B withArgument(int arg) {
            arguments.add(new Adhoc(Integer.toString(arg)));
            return cast();
        }

        public B withArgument(String arg) {
            arguments.add(new Adhoc(arg));
            return cast();
        }

        public B withArgument(boolean arg) {
            arguments.add(new Adhoc(Boolean.toString(arg)));
            return cast();
        }

        public B withStringLiteral(String arg) {
            return withArgument(LinesBuilder.stringLiteral(arg));
        }

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

        public B withArgumentFromInvoking(String name, Consumer<? super InvocationBuilder<?>> c) {
            Holder<B> holder = new Holder<>();
            InvocationBuilder<Void> result = new InvocationBuilder<>(ib -> {
                arguments.add(ib);
                holder.set(cast());
                return null;
            }, name);
            c.accept(result);
            if (!holder.isSet()) {
                result.inScope();
            }
            return holder.get("Invocation builder not completed");
        }

        public NumericOrBitwiseExpressionBuilder<B> withNumericExpressionArgument(int literal) {
            return new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(literal), fnobeb -> {
                arguments.add(fnobeb);
                return cast();
            });
        }

        public NumericOrBitwiseExpressionBuilder<B> withNumericExpressionArgument(long literal) {
            return new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(literal), fnobeb -> {
                arguments.add(fnobeb);
                return cast();
            });
        }

        public NumericOrBitwiseExpressionBuilder<B> withNumericExpressionArgument(short literal) {
            return new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(literal), fnobeb -> {
                arguments.add(fnobeb);
                return cast();
            });
        }

        public NumericOrBitwiseExpressionBuilder<B> withNumericExpressionArgument(byte literal) {
            return new NumericOrBitwiseExpressionBuilder<>(friendlyNumber(literal), fnobeb -> {
                arguments.add(fnobeb);
                return cast();
            });
        }

        public NumericExpressionBuilder<B> withNumericExpressionArgument(double literal) {
            return new NumericExpressionBuilder<>(friendlyNumber(literal), fnobeb -> {
                arguments.add(fnobeb);
                return cast();
            });
        }

        public NumericExpressionBuilder<B> withNumericExpressionArgument(float literal) {
            return new NumericExpressionBuilder<>(friendlyNumber(literal), fnobeb -> {
                arguments.add(fnobeb);
                return cast();
            });
        }

        public ValueExpressionBuilder<NumericOrBitwiseExpressionBuilder<B>> withNumericExpressionArgument() {
            return new ValueExpressionBuilder<>(veb -> {
                return new NumericOrBitwiseExpressionBuilder<>(veb, fnobeb -> {
                    arguments.add(fnobeb);
                    return cast();
                });
            });
        }

        public InvocationBuilder<B> withArgumentFromInvoking(String name) {
            return withArgumentFromInvoking(new boolean[1], name);
        }

        public FieldReferenceBuilder<B> withArgumentFromField(String name) {
            return new FieldReferenceBuilder<>(name, frb -> {
                arguments.add(frb);
                return cast();
            });
        }

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
        public void buildInto(LinesBuilder lines) {
            lines.newlineIfNewStatement();
            lines.wrappable(lbb -> {
                if (on != null) {
                    on.buildInto(lbb);
                    lbb.appendRaw(".");
                    lbb.appendRaw(name);
                } else {
                    if (isNew) {
                        lbb.word("new");
                        parseTypeName(name).buildInto(lines);
                    } else {
                        lbb.word(name);
                    }
                }
                lbb.parens(lb -> {
                    for (Iterator<BodyBuilder> it = arguments.iterator(); it.hasNext();) {
                        BodyBuilder arg = it.next();
                        arg.buildInto(lb);
                        if (it.hasNext()) {
                            lb.appendRaw(",");
                        }
                    }
                });
            });
        }
    }

    public static final class SimpleLoopBuilder<T> implements BodyBuilder {

        private final Function<SimpleLoopBuilder<T>, T> converter;
        private final String loopVar;
        private String type;
        private BodyBuilder from;
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
                h.set(new BlockBuilder<T>(bb -> {
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
            if (!holder.isSet()) {
                result.closeArrayLiteral();
            }
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
        public void buildInto(LinesBuilder lines) {
            lines.onNewLine();
            lines.word("for ");
            lines.parens(lb -> {
                lb.word(type);
                lb.word(loopVar);
                lb.word(":");
                from.buildInto(lines);
            });
            body.buildInto(lines);
        }
    }

    public static final class ForVarBuilder<T> implements BodyBuilder {

        private final Function<ForVarBuilder<T>, T> converter;
        private String loopVarType = "int";
        private BodyBuilder initializedWith = new Adhoc("0");
        private boolean increment = true;
        private BodyBuilder condition;
        private final String loopVar;
        private BodyBuilder body;

        ForVarBuilder(Function<ForVarBuilder<T>, T> converter, String loopVar) {
            this.converter = converter;
            this.loopVar = loopVar;
        }

        public ForVarBuilder<T> decrement() {
            increment = false;
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

        public BlockBuilder<T> running() {
            return new BlockBuilder<>(bb -> {
                this.body = bb;
                return converter.apply(this);
            }, true);
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.onNewLine();
            lines.word("for");
            lines.parens(lb -> {
                lb.word(loopVarType).word(loopVar).word("=");
                initializedWith.buildInto(lines);
                lb.appendRaw(";");
                condition.buildInto(lines);
                lb.appendRaw(";");
                if (increment) {
                    lb.word(loopVar);
                    lb.appendRaw("++");
                } else {
                    lb.word(loopVar);
                    lb.appendRaw("--");
                }
            });
            body.buildInto(lines);
        }
    }

    public static final class TryBuilder<T> extends BlockBuilderBase<T, TryBuilder<T>> {

        final List<CatchBuilder<?>> catches = new ArrayList<>();
        private BodyBuilder finallyBlock;

        public TryBuilder(Function<? super BodyBuilder, T> converter) {
            super(converter, true);
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.onNewLine();
            lines.word("try ");
            super.buildInto(lines);
            for (CatchBuilder<?> cb : catches) {
                lines.backup();
                cb.buildInto(lines);
            }
            if (finallyBlock != null) {
                lines.word("finally ");
                finallyBlock.buildInto(lines);
            }
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
            BodyBuilder ct = catchTypes(type, moreTypes);
            CatchBuilder<T> result = new CatchBuilder<>(this, ct, cb -> {
                catches.add(cb);
                return endBlock();
            });
            return result;
        }

        public T fynalli(Consumer<? super BlockBuilder<?>> bb) {
            Holder<T> h = new Holder<>();
            BlockBuilder<Void> result = new BlockBuilder<>(b -> {
                h.set(endBlock());
                return null;
            }, true);
            bb.accept(result);
            if (!h.isSet()) {
                h.set(endBlock());
            }
            return h.get();
        }

        public BlockBuilder<T> fynalli() {
            return new BlockBuilder<>(bb -> {
                finallyBlock = bb;
                return endBlock();
            }, true);
        }
    }

    public static final class CatchBuilder<T> extends BlockBuilderBase<T, CatchBuilder<T>> {

        private static final String DEFAULT_EX_NAME = "thrown";
        private String exceptionName = DEFAULT_EX_NAME;
        private final TryBuilder<T> parent;
        private final BodyBuilder types;

        CatchBuilder(TryBuilder<T> parent, BodyBuilder types, Function<? super CatchBuilder<T>, T> convert) {
            super(convert, true);
            this.parent = parent;
            this.types = types;
        }

        public T endTryCatch() {
            return endBlock();
        }

        public BlockBuilder<T> fynalli() {
            parent.catches.add(this);
            return parent.fynalli();
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
        public void buildInto(LinesBuilder lines) {
            lines.word("catch ");
            lines.parens(lb -> {
                types.buildInto(lb);
                lb.word(exceptionName);
            });
            super.buildInto(lines);
        }
    }

    private static BodyBuilder catchTypes(String type, String... more) {
        List<BodyBuilder> all = new ArrayList<>();
        all.add(parseTypeName(notNull("type", type)));
        if (more.length > 0) {
            for (int i = 0; i < more.length; i++) {
                all.add(new Adhoc("|"));
                all.add(new Adhoc(notNull("more[" + i + "]", more[i])));
            }
        }
        BodyBuilder types = (new Composite(all.toArray(new BodyBuilder[all.size()])));
        return types;

    }

    private static final class SynchronizedBlockBuilder<T> implements BodyBuilder {

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
        public void buildInto(LinesBuilder lines) {
            lines.onNewLine();
            lines.word("synchronized");
            lines.parens(lb -> {
                lb.word(on);
            });
            body.buildInto(lines);
        }
    }

    static final BodyBuilder EMPTY = new BodyBuilder() {
        @Override
        public void buildInto(LinesBuilder lines
        ) {
            // do nothing
        }
    };

    public static final class LambdaBuilder<T> implements BodyBuilder {

        private final Function<LambdaBuilder<T>, T> converter;
        private BlockBuilder<?> body;
        private final LinkedHashMap<String, BodyBuilder> arguments = new LinkedHashMap<>();
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
                bb.buildInto(lb);
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
        public void buildInto(LinesBuilder lines) {
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
                    for (Iterator<Map.Entry<String, BodyBuilder>> it = arguments.entrySet().iterator(); it.hasNext();) {
                        Map.Entry<String, BodyBuilder> e = it.next();
                        BodyBuilder type = e.getValue();
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
                            type.buildInto(lb);
                            lb.word(name);
                        }
                        if (it.hasNext()) {
                            lb.appendRaw(",");
                        }
                    }
                });
            }
            lines.word("->");
            body.buildInto(lines);
            lines.backup();
        }
    }

    interface Operator extends BodyBuilder {

        boolean applicableTo(Number num);
    }

    public static class StringConcatenationBuilder<T> implements BodyBuilder {

        private final BodyBuilder leftSide;
        private final Function<? super StringConcatenationBuilder<T>, T> converter;

        public StringConcatenationBuilder(BodyBuilder leftSide, Function<? super StringConcatenationBuilder<T>, T> converter) {
            this.leftSide = leftSide;
            this.converter = converter;
        }

        public ValueExpressionBuilder<StringConcatenationBuilder<T>> with() {
            return new ValueExpressionBuilder<>(veb -> {
                BodyBuilder newLeftSide = new Composite(leftSide, Operators.PLUS, veb);
                return new StringConcatenationBuilder<>(newLeftSide, converter);
            });
        }

        public T endConcatenation() {
            return converter.apply(this);
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            leftSide.buildInto(lines);
        }
    }

    public enum NumericCast implements BodyBuilder {
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
        public void buildInto(LinesBuilder lines) {
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

        final BodyBuilder leftSide;
        final Function<F, T> converter;
        boolean parenthesized;
        NumericCast castTo;

        NumericExpressionBuilderBase(BodyBuilder leftSide, Function<F, T> converter) {
            this.leftSide = leftSide;
            this.converter = converter;
        }

        abstract F newFinishable(BodyBuilder rightSide, Operator op);

        @SuppressWarnings("unchecked")
        private N cast() {
            return (N) this;
        }

        public N parenthesized() {
            this.parenthesized = true;
            return cast();
        }

        public N castTo(NumericCast cast) {
            this.castTo = cast;
            return cast();
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

        NumericOrBitwiseExpressionBuilderBase(BodyBuilder leftSide, Function<F, T> converter) {
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

        NumericExpressionBuilder(BodyBuilder leftSide, Function<FinishableNumericExpressionBuilder<T>, T> converter) {
            super(leftSide, converter);
        }

        FinishableNumericExpressionBuilder<T> newFinishable(BodyBuilder rightSide, Operator op) {
            FinishableNumericExpressionBuilder<T> result = new FinishableNumericExpressionBuilder<>(rightSide, leftSide, op, parenthesized, converter);
            if (castTo != null) {
                result.castTo = castTo;
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

        NumericOrBitwiseExpressionBuilder(BodyBuilder leftSide, Function<FinishableNumericOrBitwiseExpressionBuilder<T>, T> converter) {
            super(leftSide, converter);
        }

        @Override
        FinishableNumericOrBitwiseExpressionBuilder<T> newFinishable(BodyBuilder rightSide, Operator op) {
            return new FinishableNumericOrBitwiseExpressionBuilder<>(rightSide, leftSide, op, parenthesized, converter);
        }
    }

    /**
     * Numeric expression which has a left side and a right side and an
     * operation, and can be added to or closed.
     *
     * @param <T> The type
     */
    public static final class FinishableNumericExpressionBuilder<T> extends NumericExpressionBuilderBase<T, FinishableNumericExpressionBuilder<T>, FinishableNumericExpressionBuilder<T>> implements BodyBuilder {

        private final BodyBuilder rightSide;
        private final Operator op;

        FinishableNumericExpressionBuilder(BodyBuilder rightSide, BodyBuilder leftSide, Operator op, boolean parenthesized, Function<FinishableNumericExpressionBuilder<T>, T> converter) {
            super(leftSide, converter);
            this.rightSide = rightSide;
            this.op = op;
            this.parenthesized = parenthesized;
        }

        @Override
        FinishableNumericExpressionBuilder<T> newFinishable(BodyBuilder rightSide, Operator op) {
            return new FinishableNumericExpressionBuilder<>(rightSide, this, op, false, converter);
        }

        public T endNumericExpression() {
            return converter.apply(this);
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            if (parenthesized) {
                lines.parens(this::reallyBuildInto);
            } else {
                reallyBuildInto(lines);
            }
        }

        private void reallyBuildInto(LinesBuilder lines) {
            if (castTo != null) {
                castTo.buildInto(lines);
            }
            leftSide.buildInto(lines);
            op.buildInto(lines);
            rightSide.buildInto(lines);
        }
    }

    public static final class FinishableNumericOrBitwiseExpressionBuilder<T> extends NumericOrBitwiseExpressionBuilderBase<T, FinishableNumericOrBitwiseExpressionBuilder<T>, FinishableNumericOrBitwiseExpressionBuilder<T>> implements BodyBuilder {

        private final BodyBuilder rightSide;
        private final Operator op;

        FinishableNumericOrBitwiseExpressionBuilder(BodyBuilder rightSide, BodyBuilder leftSide, Operator op, boolean parenthesized, Function<FinishableNumericOrBitwiseExpressionBuilder<T>, T> converter) {
            super(leftSide, converter);
            this.rightSide = rightSide;
            this.op = op;
            this.parenthesized = parenthesized;
        }

        @Override
        FinishableNumericOrBitwiseExpressionBuilder<T> newFinishable(BodyBuilder rightSide, Operator op) {
            return new FinishableNumericOrBitwiseExpressionBuilder<>(rightSide, this, op, false, converter);
        }

        public T endNumericExpression() {
            return converter.apply(this);
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            if (parenthesized) {
                lines.parens(this::reallyBuildInto);
            } else {
                reallyBuildInto(lines);
            }
        }

        private void reallyBuildInto(LinesBuilder lines) {
            if (op == COMPLEMENT) {
                op.buildInto(lines);
                leftSide.buildInto(lines);
                assert EMPTY == rightSide;
            } else {
                leftSide.buildInto(lines);
                op.buildInto(lines);
                rightSide.buildInto(lines);
            }
        }
    }

    enum BitwiseOperators implements Operator {
        OR("|"),
        AND("&"),
        XOR("^"),
        COMPLEMENT("~"),
        SHIFT_LEFT("<<"),
        SHIFT_RIGHT(">>"),
        ROTATE("<<<");
        private final String stringRep;

        private BitwiseOperators(String stringRep) {
            this.stringRep = stringRep;
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.word(toString() + " ");
        }

        @Override
        public String toString() {
            return stringRep;
        }

        @Override
        public boolean applicableTo(Number num) {
            return num instanceof Integer || num instanceof Long;
        }
    }

    enum Operators implements Operator {
        PLUS("+"),
        MINUS("-"),
        TIMES("*"),
        DIVIDED_BY("/"),
        MODULO("%"),;

        private final String stringRep;

        private Operators(String stringRep) {
            this.stringRep = stringRep;
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.word(toString() + " ");
        }

        @Override
        public String toString() {
            return stringRep;
        }

        @Override
        public boolean applicableTo(Number num) {
            return true;
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
    public static class ValueExpressionBuilder<T> implements BodyBuilder {

        private BodyBuilder value;
        private final Function<ValueExpressionBuilder<T>, T> converter;

        ValueExpressionBuilder(Function<ValueExpressionBuilder<T>, T> converter) {
            this.converter = converter;
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
            if (!holder.isSet()) {
                ib.inScope();
            }
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
            value = new Adhoc(LinesBuilder.escapeCharLiteral(ch));
            return converter.apply(this);
        }

        public T literal(String s) {
            value = new Adhoc(LinesBuilder.stringLiteral(s));
            return converter.apply(this);
        }

        public ValueExpressionBuilder<StringConcatenationBuilder<T>> stringConcatenation() {
            return new ValueExpressionBuilder<>(veb -> {
                return new StringConcatenationBuilder<>(veb, scb -> {
                    value = scb;
                    return converter.apply(this);
                });
            });
        }

        public StringConcatenationBuilder<T> concatenate(String stringLiteral) {
            BodyBuilder base = new Adhoc(LinesBuilder.stringLiteral(stringLiteral));
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
            if (!holder.isSet()) {
                res.closeArrayLiteral();
            }
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
            if (!holder.isSet()) {
                bldr.closeAnnotation();
            }
            return holder.get("AnnotationBuilder was not completed");
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            value.buildInto(lines);
        }
    }

    static class TernaryBuilder<T> implements BodyBuilder {

        private final Function<TernaryBuilder<T>, T> converter;

        private final BodyBuilder condition;
        private BodyBuilder trueSide;
        private BodyBuilder falseSide;

        TernaryBuilder(Function<TernaryBuilder<T>, T> converter, BodyBuilder condition) {
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
        public void buildInto(LinesBuilder lines) {
            lines.wrappable(lb -> {
                condition.buildInto(lb);
                lb.word("?");
                trueSide.buildInto(lb);
                lb.word(":");
                falseSide.buildInto(lb);
            });
        }
    }

    public static class BlockBuilder<T> extends BlockBuilderBase<T, BlockBuilder<T>> {

        BlockBuilder(Function<? super BlockBuilder<T>, T> converter, boolean openBlock) {
            super(converter, openBlock);
        }

        public T endBlock() {
            return super.endBlock();
        }
    }

    public static class BlockBuilderBase<T, B extends BlockBuilderBase<T, B>> implements BodyBuilder {

        final List<BodyBuilder> statements = new LinkedList<>();
        final Function<? super B, T> converter;
        private final boolean openBlock;

        BlockBuilderBase(Function<? super B, T> converter, boolean openBlock) {
            this.converter = converter;
            this.openBlock = openBlock;
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

        public ValueExpressionBuilder<T> returningValue() {
            return new ValueExpressionBuilder<>(veb -> {
                statements.add(new ReturnStatement(veb));
                return endBlock();
            });
        }

        public T returningValue(Consumer<ValueExpressionBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            ValueExpressionBuilder<Void> result = new ValueExpressionBuilder<>(veb -> {
                statements.add(new ReturnStatement(veb));
                holder.set(endBlock());
                return null;
            });
            c.accept(result);
            return holder.get("Value expression builder not completed");
        }

        public NewBuilder<T> returningNew() {
            return new NewBuilder<>(nb -> {
                statements.add(new ReturnStatement(nb));
                return endBlock();
            });
        }

        public T returningNew(Consumer<? super NewBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            NewBuilder<Void> result = new NewBuilder<>(nb -> {
                statements.add(new ReturnStatement(nb));
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
                statements.add(new ReturnStatement(lb));
                done[0] = true;
                h.set(endBlock());
                return null;
            });
            c.accept(result);
            if (!done[0]) {
//                h.set(endBlock());
//                result.
            }
            return h.get("Lambda builder not completed");
        }

        public LambdaBuilder<T> returningLambda() {
            LambdaBuilder<T> result = new LambdaBuilder<T>(lb -> {
                statements.add(new ReturnStatement(lb));
                return endBlock();
            });
            return result;
        }

        public NewBuilder<MethodBuilder<T>> invokeConstructor() {
            NewBuilder<MethodBuilder<T>> result = new NewBuilder<>(nb -> {
                statements.add(new StatementWrapper(nb));
                return null;
            });
            return result;
        }

        public B invokeConstructor(Consumer<? super NewBuilder<?>> c) {
            Holder<B> h = new Holder<>();
            NewBuilder<?> result = new NewBuilder<>(nb -> {
                statements.add(new StatementWrapper(nb));
                h.set(cast());
                return null;
            });
            c.accept(result);
            return h.get("New builder not completed - call onType()");
        }

        public IfBuilder<B> ifNotNull(String expression) {
            return BlockBuilderBase.this.iff().variable(expression).notEquals().expression("null").endCondition();
        }

        public B ifNotNull(String expression, Consumer<? super IfBuilder<?>> c) {
            return iff(cb -> {
                cb.variable(expression).notEquals().expression("null").endCondition(ib2 -> {
                    c.accept(ib2);
                });
            });
        }

        public IfBuilder<B> ifNull(String expression) {
            return BlockBuilderBase.this.iff().variable(expression).equals().expression("null").endCondition();
        }

        public B ifNull(String expression, Consumer<? super IfBuilder<?>> c) {
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
                    statements.add(ib);
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
            ConditionBuilder<IfBuilder<B>> result = new ConditionBuilder<>(fcb -> {
                IfBuilder<B> ib2 = new IfBuilder<>(ib -> {
                    statements.add(ib);
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

        public B conditionally(boolean test, Consumer<? super BlockBuilderBase<?, ?>> cb) {
            if (test) {
                cb.accept(this);
            }
            return cast();
        }

        private static String stripPackage(StackTraceElement el) {
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

        public B debugLog(String line) {
            if ((CONTEXT.get() != null && CONTEXT.get().generateDebugCode)) {
                invoke("println").withStringLiteral(line).on("System.out");
            }
            return cast();
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
                statements.add(llb);
                return cast();
            }, level);
        }

        public static final class LogLineBuilder<T> implements BodyBuilder {

            private final Function<LogLineBuilder<T>, T> converter;
            private String line;
            private List<BodyBuilder> arguments = new ArrayList<>(5);
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
            public void buildInto(LinesBuilder lines) {
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
                                arguments.get(0).buildInto(llb);
                                break;
                            default:
                                llb.appendRaw(",");
                                llb.word("new").word("Object[]");
                                llb.delimit('{', '}', db -> {
                                    for (Iterator<BodyBuilder> it = arguments.iterator(); it.hasNext();) {
                                        it.next().buildInto(db);
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
            statements.add(new NL());
            return cast();
        }

        static final class NL implements BodyBuilder {

            @Override
            public void buildInto(LinesBuilder lines) {
                lines.doubleNewline();
            }

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
                statements.add(lb);
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
                statements.add(sb);
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
                    statements.add(slb);
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
                statements.add(fvb);
                return cast();
            }, name);
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            if (openBlock) {
                lines.block(lb -> {
                    for (BodyBuilder bb : statements) {
                        bb.buildInto(lb);
                    }
                });
            } else {
                for (BodyBuilder bb : statements) {
                    bb.buildInto(lines);
                }
            }
        }

        public B lineComment(String cmt) {
            return lineComment(cmt, false);
        }

        public B lineComment(String cmt, boolean trailing) {
            statements.add(new LineComment(cmt, trailing));
            return cast();
        }

        public TryBuilder<B> trying() {
            TryBuilder<B> tb = new TryBuilder<B>(b -> {
                statements.add(b);
                return cast();
            });
            return tb;
        }

        public B trying(Consumer<? super TryBuilder<?>> cb) {
            boolean[] done = new boolean[1];
            TryBuilder<Void> tb = new TryBuilder<>(b -> {
                statements.add(b);
                done[0] = true;
                return null;
            });
            cb.accept(tb);
            if (!done[0]) {
                tb.fynalli().lineComment("do nothing").endBlock();
                assert done[0];
            }
            return cast();
        }

        static class ONL implements BodyBuilder {

            @Override
            public void buildInto(LinesBuilder lines) {
                lines.onNewLine();
            }

        }

        public B invoke(String method, Consumer<? super InvocationBuilder<?>> c) {
            boolean[] closed = new boolean[1];
            InvocationBuilder<Void> ib = new InvocationBuilder<>(b -> {
                closed[0] = true;
                statements.add(b);
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
            statements.add(
                    new StatementWrapper(new Composite(new Adhoc("throw"), new Adhoc(checkIdentifier(notNull("what", what))))));
            return endBlock();
        }

        public NewBuilder<T> andThrow() {
            NewBuilder<T> result = new NewBuilder<>(nb -> {
                statements.add(new StatementWrapper(new Composite(new Adhoc("throw"), nb)));
                return endBlock();
            });
            return result;
        }

        public T andThrow(Consumer<? super NewBuilder<?>> c) {
            Holder<T> h = new Holder<>();
            NewBuilder<Void> result = new NewBuilder<>(nb -> {
                statements.add(new StatementWrapper(new Composite(new Adhoc("throw"), nb)));
                h.set(endBlock());
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
                statements.add(new StatementWrapper(b));
                return cast();
            }, new Adhoc(variable));
        }

        public B assign(String variable, Consumer<? super AssignmentBuilder<?>> c) {
            boolean[] closed = new boolean[1];
            AssignmentBuilder<Void> ab = new AssignmentBuilder<>(b -> {
                closed[0] = true;
                statements.add(new Composite(b, new Adhoc(";")));
                return null;
            }, new Adhoc(variable));
            c.accept(ab);
            if (!closed[0]) {
                throw new IllegalStateException(".to() not called on AssignmentBuilder - "
                        + "statement not complete");
            }
            return cast();
        }

        public InvocationBuilder<B> invoke(String method) {
            return new InvocationBuilder<>(ib -> {
                statements.add(new WrappedStatement(ib));
                return cast();
            }, method);
        }

        public BlockBuilder<B> block() {
            return new BlockBuilder<>(bk -> {
                statements.add(bk);
                return cast();
            }, true);
        }

        public B statement(String stmt) {
            if (stmt.endsWith(";")) {
                stmt = stmt.substring(0, stmt.length() - 1);
            }
            statements.add(new OneStatement(stmt));
            return cast();
        }

        public FieldReferenceBuilder<B> returningField(String name) {
            return new FieldReferenceBuilder<>(name, fb -> {
                statements.add(new ReturnStatement(fb));
                return cast();
            });
        }

        public B returningField(String name, Consumer<FieldReferenceBuilder<?>> c) {
            Holder<B> holder = new Holder<>();
            FieldReferenceBuilder<Void> result = new FieldReferenceBuilder<>(name, frb -> {
                statements.add(new ReturnStatement(frb));
                holder.set(cast());
                return null;
            });
            return holder.get("Field reference not completed");
        }

        public B returning(String s) {
            statements.add(new ReturnStatement(new Adhoc(s)));
            return cast();
        }

        public B returning(Number num) {
            statements.add(new ReturnStatement(friendlyNumber(num)));
            return cast();
        }

        public B returningStringLiteral(String s) {
            statements.add(new ReturnStatement(new Adhoc(LinesBuilder.stringLiteral(s))));
            return cast();
        }

        public InvocationBuilder<B> returningInvocationOf(String method) {
            return new InvocationBuilder<>(ib -> {
                statements.add(new ReturnStatement(ib));
                return cast();
            }, method);
        }

        public T returningInvocationOf(String method, Consumer<? super InvocationBuilder<?>> c) {
            boolean[] built = new boolean[1];
            Holder<T> holder = new Holder<>();
            InvocationBuilder<T> b = new InvocationBuilder<>(ib -> {
                statements.add(new ReturnStatement(ib));
                built[0] = true;
                T result = this.converter.apply(cast());
                holder.set(result);
                return result;
            }, method);
            c.accept(b);
            if (!built[0]) {
                b.inScope();
            }
            T result = holder.get();
            if (result == null) {
                throw new IllegalStateException("Invocation builder not completed - call"
                        + "inScope() or on()");
            }
            return result;
        }

        public B incrementVariable(String var) {
            statements.add(new OneStatement(var + "++"));
            return cast();
        }

        public B decrementVariable(String var) {
            statements.add(new OneStatement(var + "--"));
            return cast();
        }

        public DeclarationBuilder<B> declare(String what) {
            return new DeclarationBuilder<>(db -> {
                statements.add(db);
                return cast();
            }, what);
        }

        public B declare(String what, Consumer<DeclarationBuilder<?>> c) {
            Holder<B> h = new Holder<>();
            DeclarationBuilder<Void> result = new DeclarationBuilder<>(db -> {
                statements.add(db);
                h.set(cast());
                return null;
            }, what);
            return h.get("Declaration not completed");
        }

        public SwitchBuilder<B> switchingOn(String on) {
            return new SwitchBuilder<>(sb -> {
                statements.add(sb);
                return cast();
            }, on);
        }

        public B switchingOn(String on, Consumer<? super SwitchBuilder<?>> c) {
            boolean[] built = new boolean[1];
            SwitchBuilder<Void> sw = new SwitchBuilder<>(sb -> {
                statements.add(sb);
                built[0] = true;
                return null;
            }, on);
            c.accept(sw);
            if (!built[0]) {
                if (!sw.isEmpty()) {
                    statements.add(sw);
                } else {
                    throw new IllegalStateException("Switch builder never used");
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

        static class WrappedStatement implements BodyBuilder {

            private final BodyBuilder wrapped;

            WrappedStatement(BodyBuilder wrapped) {
                this.wrapped = wrapped;
            }

            @Override
            public void buildInto(LinesBuilder lines) {
                lines.statement(lb1 -> {
                    wrapped.buildInto(lb1);
                });
            }
        }

        static class OneStatement implements BodyBuilder {

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
            public void buildInto(LinesBuilder lines) {
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
                            lb.appendRaw(';');
                        }
                    });
                } else {
                    if (initialNewline) {
                        lines.statement(text.trim());
                    } else {
                        lines.word(text.trim()).appendRaw(';').onNewLine();
                    }
                }
            }
        }
    }

    static final class LineComment implements BodyBuilder {

        private final String line;
        private final boolean trailing;

        LineComment(String what, boolean trailing) {
            this.line = what;
            this.trailing = trailing;
        }

        public void buildInto(LinesBuilder lines) {
            if (trailing) {
                lines.backup().appendRaw(" ");
            } else {
                lines.onNewLine();
            }
            for (String part : line.split("\n")) {
                part = part.trim();
                if (part.isEmpty()) {
                    lines.doubleNewline();
                } else {
                    lines.appendRaw("// " + part);
                    lines.onNewLine();
                }
            }
        }
    }

    public static final class TypeAssignment<T> implements BodyBuilder {

        private BodyBuilder type;
        private final Function<TypeAssignment<T>, T> converter;

        public TypeAssignment(Function<TypeAssignment<T>, T> converter) {
            this.converter = converter;
        }

        public T as(String type) {
            this.type = parseTypeName(checkIdentifier(notNull("type", type)));
            return converter.apply(this);
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            type.buildInto(lines);
        }
    }

    public static final class DeclarationBuilder<T> implements BodyBuilder {

        private final Function<DeclarationBuilder<T>, T> converter;
        private final String name;
        private BodyBuilder as;
        private BodyBuilder initializer;

        DeclarationBuilder(Function<DeclarationBuilder<T>, T> converter, String name) {
            this.converter = converter;
            this.name = name;
        }

        public T as(String type) {
            this.as = parseTypeName(checkIdentifier(notNull("type", type)));
            LinesBuilder lb = new LinesBuilder();
            as.buildInto(lb);
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
                return new TypeAssignment<T>(ta -> {
                    this.as = ta.type;
                    return converter.apply(this);
                });
            });
        }

        public TypeAssignment<T> initializedWithNew(Consumer<NewBuilder<?>> c) {
            Holder<TypeAssignment<T>> h = new Holder<>();
            NewBuilder<Void> frb = new NewBuilder<>(b -> {
                initializer = b;
                h.set(new TypeAssignment<T>(ta -> {
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
                h.set(new TypeAssignment<T>(ta -> {
                    this.as = ta.type;
                    return converter.apply(this);
                }));
                return null;
            }, name);
            if (!h.isSet()) {
                result.inScope();
            }
            return h.get("Invocation builder not completed");
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.onNewLine();
            lines.statement(l -> {
                as.buildInto(lines);
                l.word(name);
                if (initializer != null) {
                    l.word("=");
                    initializer.buildInto(l);
                }
            });
        }

        public ArrayLiteralBuilder<T> initializedAsNewArray(String type) {
            return new ArrayLiteralBuilder<>(alb -> {
                as = new Composite(alb.type, new BackupRaw("[]"));
                initializer = alb;
                return converter.apply(this);
            }, type);
        }

        public T initializedAsNewArray(String action, Consumer<? super ArrayLiteralBuilder<?>> c) {
            Holder<T> holder = new Holder<>();
            ArrayLiteralBuilder<Void> bldr = new ArrayLiteralBuilder<>(alb -> {
                as = new Composite(alb.type, new BackupRaw("[]"));
                initializer = alb;
                holder.set(converter.apply(this));
                return null;
            }, action);
            c.accept(bldr);
            if (!holder.isSet()) {
                throw new IllegalStateException("Array not closed");
            }
            return holder.get();
        }
    }

    public static final class ElseClauseBuilder<T> extends BlockBuilderBase<T, ElseClauseBuilder<T>> {

        ElseClauseBuilder(Function<? super ElseClauseBuilder<T>, T> converter, boolean openBlock) {
            super(converter, openBlock);
        }

        public T endIf() {
            return endBlock();
        }
    }

    public static final class IfBuilder<T> extends BlockBuilderBase<T, IfBuilder<T>> {

        private BlockBuilderBase<?, ?> finalElse;
        private final List<Pair<BodyBuilder, IfBuilder<?>>> clausePairs = new ArrayList<>();

        IfBuilder(Function<? super IfBuilder<T>, T> converter, BodyBuilder condition) {
            super(converter, true);
            clausePairs.add(new Pair<>(condition, this));
        }

        IfBuilder(IfBuilder<T> orig, BodyBuilder condition) {
            super(orig.converter, true);
            clausePairs.addAll(orig.clausePairs);
            clausePairs.add(new Pair<>(condition, this));
        }

        IfBuilder(IfBuilder<?> orig, Function<? super IfBuilder<T>, T> converter, BodyBuilder condition) {
            super(converter, true);
            clausePairs.addAll(orig.clausePairs);
            clausePairs.add(new Pair<>(condition, this));
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
            super.buildInto(lines);
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.backup().onNewLine(); // XXX
            assert !clausePairs.isEmpty();
            for (int i = 0; i < clausePairs.size(); i++) {
                Pair<BodyBuilder, IfBuilder<?>> pair = clausePairs.get(i);
                if (i == 0) {
                    lines.onNewLine();
                    lines.word("if ");
                }
                lines.parens(lb -> {
                    pair.a.buildInto(lb);
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
                finalElse.buildInto(lines);
            }
        }
    }

    static final class Empty implements BodyBuilder {

        @Override
        public void buildInto(LinesBuilder lines) {
            // do nothing
        }

        public boolean equals(Object o) {
            return o instanceof Empty;
        }

        public int hashCode() {
            return 0;
        }
    }

    public static final class ConditionBuilder<T> implements BodyBuilder {

        private final Function<BodyBuilder, T> converter;
        private boolean negated;
        private boolean parenthesized;
        private BodyBuilder prev;
        private LogicalOperation op;
        private BodyBuilder clause;

        ConditionBuilder(Function<BodyBuilder, T> converter) {
            this.converter = converter;
        }

        ConditionBuilder(Function<BodyBuilder, T> converter, BodyBuilder prev, LogicalOperation op) {
            this.converter = converter;
            this.prev = prev;
            this.op = op;
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
            clause = new Adhoc(LinesBuilder.escapeCharLiteral(val));
            return new ComparisonBuilder<>(this);
        }

        public FinishableConditionBuilder<T> literal(boolean val) {
            clause = new Adhoc(Boolean.toString(val));
            FinishableConditionBuilder<T> result = new FinishableConditionBuilder<>(fcb -> {
                return converter.apply(fcb);
            }, new ConditionRightSideBuilder<>(null, this, null), null);
            return result;
        }

        @Override
        public void buildInto(LinesBuilder lines) {
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
            if (parenthesized) {
                lines.parens(cb);
            } else {
                cb.accept(lines);
            }
        }

        private void doBuildInto(LinesBuilder lines) {
            if (prev != null) {
                prev.buildInto(lines);
                op.buildInto(lines);
            }
            clause.buildInto(lines);
        }
    }

    static final class Adhoc implements BodyBuilder {

        private final String what;
        private boolean hangingWrap;

        Adhoc(String what) {
            this(what, true);
        }

        Adhoc(String what, boolean hangingWrap) {
            this.what = what;
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.word(what, hangingWrap);
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 67 * hash + Objects.hashCode(this.what);
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
            final Adhoc other = (Adhoc) obj;
            return Objects.equals(this.what, other.what);
        }
    }

    public static final class ComparisonBuilder<T> implements BodyBuilder {

        private final ConditionBuilder<T> leftSide;

        ComparisonBuilder(ConditionBuilder<T> leftSide) {
            this.leftSide = leftSide;
        }

        public ConditionRightSideBuilder<T> equals() {
            return new ConditionRightSideBuilder<>(leftSide.converter, leftSide, ComparisonOperation.EQ);
        }

        public ConditionRightSideBuilder<T> notEquals() {
            return new ConditionRightSideBuilder<>(leftSide.converter, leftSide, ComparisonOperation.NE);
        }

        public ConditionRightSideBuilder<T> greaterThan() {
            return new ConditionRightSideBuilder<>(leftSide.converter, leftSide, ComparisonOperation.GT);
        }

        public ConditionRightSideBuilder<T> lessThan() {
            return new ConditionRightSideBuilder<>(leftSide.converter, leftSide, ComparisonOperation.LT);
        }

        public ConditionRightSideBuilder<T> greaterThanOrEqualto() {
            return new ConditionRightSideBuilder<>(leftSide.converter, leftSide, ComparisonOperation.GTE);
        }

        public ConditionRightSideBuilder<T> lessThanOrEqualto() {
            return new ConditionRightSideBuilder<>(leftSide.converter, leftSide, ComparisonOperation.LTE);
        }

        public ConditionRightSideBuilder<T> instanceOf() {
            return new ConditionRightSideBuilder<>(leftSide.converter, leftSide, ComparisonOperation.INSTANCEOF);
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            leftSide.buildInto(lines);
        }
    }

    public static final class ConditionRightSideBuilder<T> implements BodyBuilder {

        private final Function<BodyBuilder, T> converter;
        boolean negated;
        final BodyBuilder leftSide;
        final ComparisonOperation op;

        ConditionRightSideBuilder(Function<BodyBuilder, T> converter, BodyBuilder leftSide, ComparisonOperation op) {
            this.converter = converter;
            this.leftSide = leftSide;
            if (leftSide instanceof ConditionBuilder<?>) {
                negated = ((ConditionBuilder<?>) ((ConditionBuilder<?>) leftSide)).negated;
            }
            this.op = op;
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
        public void buildInto(LinesBuilder lines) {
            if (negated) {
                lines.word("!");
                lines.parens(this::doBuildInto);
            } else {
                doBuildInto(lines);
            }
        }

        private void doBuildInto(LinesBuilder lines) {
            leftSide.buildInto(lines);
            if (op != null) {
                op.buildInto(lines);
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

        ValueExpressionBuilder<FinishableConditionBuilder<T>> veb() {
            return new ValueExpressionBuilder<>(veb -> {
                return new FinishableConditionBuilder<>(converter, this, veb.value);
            });
        }
    }

    static final class NegatedWrapper implements BodyBuilder {

        private final BodyBuilder bb;

        NegatedWrapper(BodyBuilder bb) {
            this.bb = bb;
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.appendRaw('!');
            if (bb instanceof InvocationBuilder<?> || bb instanceof ValueExpressionBuilder<?> || bb instanceof AssignmentBuilder<?>) {
                bb.buildInto(lines);
            } else {
                lines.parens(bb::buildInto);
            }
        }
    }

    public static final class FinishableConditionBuilder<T> implements BodyBuilder {

        private final Function<BodyBuilder, T> converter;
        final ConditionRightSideBuilder<T> leftSideAndOp;
        private final BodyBuilder rightSide;

        FinishableConditionBuilder(Function<BodyBuilder, T> converter, ConditionRightSideBuilder<T> leftSideAndOp, BodyBuilder rightSide) {
            this.converter = converter;
            this.leftSideAndOp = leftSideAndOp;
            this.rightSide = rightSide;
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
        public void buildInto(LinesBuilder lines) {
            leftSideAndOp.buildInto(lines);
            if (rightSide != null) {
                rightSide.buildInto(lines);
            }
        }
    }

    private static enum UnaryOperator implements BodyBuilder {
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
        public void buildInto(LinesBuilder lines) {
            lines.word(s);
        }

    }

    private static enum ArithmenticOperator implements BodyBuilder {
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
        public void buildInto(LinesBuilder lines) {
            lines.word(s);
        }
    }

    private static enum AssignmentOperator implements BodyBuilder {
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
        public void buildInto(LinesBuilder lines) {
            lines.word(s);
        }
    ;

    }

    private static enum ComparisonOperation implements BodyBuilder {
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
        public void buildInto(LinesBuilder lines) {
            lines.word(s);
        }
    }

    private static enum LogicalOperation implements BodyBuilder {
        OR,
        AND,;

        @Override
        public void buildInto(LinesBuilder lines) {
            switch (this) {
                case OR:
                    lines.word("||");
                    break;
                case AND:
                    lines.word("&&");
                    break;
                default:
                    throw new AssertionError(this);
            }
        }
    }

    public static final class SwitchBuilder<T> implements BodyBuilder {

        private final Function<SwitchBuilder<T>, T> converter;
        private final Map<String, BodyBuilder> cases = new LinkedHashMap<>();
        private final String what;

        SwitchBuilder(Function<SwitchBuilder<T>, T> converter, String on) {
            this.converter = converter;
            this.what = on;
        }

        private boolean isEmpty() {
            return cases.isEmpty();
        }

        public BlockBuilder<SwitchBuilder<T>> inStringLiteralCase(String what) {
            return _case(LinesBuilder.stringLiteral(what));
        }

        public BlockBuilder<SwitchBuilder<T>> inDefaultCase() {
            return _case("*");
        }

        public BlockBuilder<SwitchBuilder<T>> inCase(char c) {
            return _case(LinesBuilder.escapeCharLiteral(c));
        }

        public BlockBuilder<SwitchBuilder<T>> inCase(Number num) {
            return _case(num.toString());
        }

        public BlockBuilder<SwitchBuilder<T>> inCase(String what) {
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
            String value = what.toString();
            if (cases.containsKey(value)) {
                throw new IllegalStateException("Case for '" + value
                        + "' already added");
            }
            cases.put(value, bldr);
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

        public T build() {
            return converter.apply(this);
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.word("switch");
            lines.parens(lb -> {
                lb.word(what);
            });
            lines.block(lb -> {
                for (Map.Entry<String, BodyBuilder> e : cases.entrySet()) {
                    if ("*".equals(e.getKey())) {
                        lb.backup().onNewLine();
                        lb.switchCase(null, (lb1) -> {
                            e.getValue().buildInto(lb1);
                        });
                    } else {
                        lb.backup().onNewLine();
                        lb.switchCase(e.getKey(), (lb1) -> {
                            e.getValue().buildInto(lb1);
                        });
                    }
                }
            });
        }
    }

    public static final class ArrayValueBuilder<T> implements BodyBuilder {

        private final char end;

        private final Function<ArrayValueBuilder<T>, T> converter;
        private final List<BodyBuilder> values = new LinkedList<>();
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
            values.add(new Adhoc(LinesBuilder.escapeCharLiteral(c), true));
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
            boolean[] built = new boolean[1];
            AnnotationBuilder<?> bldr = annotation(type, built);
            c.accept(bldr);
            if (built[0] == false) {
                throw new IllegalStateException("Annotation was not closed - call closeAnnotation()");
            }
            return this;
        }

        private AnnotationBuilder<ArrayValueBuilder<T>> annotation(String type, boolean[] built) {
            return new AnnotationBuilder<>(ab -> {
                values.add(ab);
                return this;
            }, type);
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.delimit(start, end, lb -> {
                for (Iterator<BodyBuilder> it = values.iterator(); it.hasNext();) {
                    it.next().buildInto(lb);
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
            if (!Objects.equals(this.values, other.values)) {
                return false;
            }
            return true;
        }

    }

    public static final class AnnotationBuilder<T> implements BodyBuilder {

        private final Function<AnnotationBuilder<T>, T> converter;
        private final String annotationType;
        private final Map<String, BodyBuilder> arguments = new LinkedHashMap<>();

        AnnotationBuilder(Function<AnnotationBuilder<T>, T> converter, String annotationType) {
            this.converter = converter;
            this.annotationType = annotationType;
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
                throw new IllegalStateException("closeAnnotation not called");
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
        public void buildInto(LinesBuilder lines) {
            lines.wrappable(l -> {
                lines.onNewLine().word("@" + annotationType);
                if (!arguments.isEmpty()) {
                    lines.parens(lb -> {
                        if (arguments.size() == 1 && arguments.containsKey("value")) {
                            arguments.get("value").buildInto(lb);
                        } else {
                            lb.wrappable(wb -> {
                                for (Iterator<Map.Entry<String, BodyBuilder>> it = arguments.entrySet().iterator(); it.hasNext();) {
                                    Map.Entry<String, BodyBuilder> e = it.next();
                                    wb.word(e.getKey()).word("=");
                                    e.getValue().buildInto(lines);
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
            if (!Objects.equals(this.arguments, other.arguments)) {
                return false;
            }
            return true;
        }

    }

    public static final class FieldBuilder<T> implements BodyBuilder {

        private final Function<FieldBuilder<T>, T> converter;
        private BodyBuilder type;
        private BodyBuilder initializer;
        private final Set<Modifier> modifiers = new TreeSet<>();
        private final String name;
        private Set<AnnotationBuilder> annotations = new LinkedHashSet<>();
        private String docComment;

        FieldBuilder(Function<FieldBuilder<T>, T> converter, String name) {
            this.converter = converter;
            this.name = name;
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
            if (!Objects.equals(this.name, other.name)) {
                return false;
            }
            return true;
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
                this.type = new Composite(alb.type, new BackupRaw("[]"));
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
                this.type = new Composite(alb.type, new BackupRaw("[]"));
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
                throw new IllegalStateException("closeAnnotation() not called");
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
            type = new TypeNameItem(num.getClass().getSimpleName().toLowerCase());
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
        public void buildInto(LinesBuilder lines) {
            lines.onNewLine();
            if (docComment != null) {
                writeDocComment(docComment, lines);
                lines.onNewLine();
            }
            for (AnnotationBuilder<?> ab : annotations) {
                ab.buildInto(lines);
            }
            lines.statement(lb -> {
                for (Modifier m : modifiers) {
                    lb.word(m.toString());
                }
                if (type != null) {
                    type.buildInto(lb);
                }
                lb.word(name);
                if (initializer != null) {
                    lb.wrappable(lbb -> {
                        if (!(initializer instanceof AssignmentBuilder<?>)) {
                            lbb.word("=");
                        }
                        initializer.buildInto(lbb);
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

    static class StatementWrapper implements BodyBuilder {

        private final BodyBuilder bb;

        public StatementWrapper(BodyBuilder bb) {
            this.bb = bb;
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.onNewLine();
            bb.buildInto(lines);
            lines.backup();
            lines.appendRaw(';');
        }
    }

    private static final class Holder<T> {

        T obj;

        boolean wasSetCalled;

        void set(T obj) {
            wasSetCalled = true;
            this.obj = obj;
        }

        boolean isSet() {
            return obj != null || wasSetCalled;
        }

        T get(String nullMessage) {
            if (!isSet()) {
                throw new IllegalStateException(nullMessage);
            }
            return obj;
        }

        T get() {
            return obj;
        }
    }

    static final class Pair<A, B> {

        final A a;
        final B b;

        public Pair(A a, B b) {
            this.a = a;
            this.b = b;
        }
    }

    static final class BackupRaw implements BodyBuilder {

        private final String what;

        public BackupRaw(String what) {
            this.what = what;
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.backup();
            lines.appendRaw(what);
        }
    }

    static final class ReturnStatement implements BodyBuilder {

        private final BodyBuilder what;

        public ReturnStatement(BodyBuilder what) {
            this.what = what;
        }

        @Override
        public void buildInto(LinesBuilder lines) {
            lines.statement(lb -> {
                lb.onNewLine();
                lb.word("return ");
                what.buildInto(lb);
                lb.backup();
            });
        }
    }

    private static final Pattern ARR = Pattern.compile("^\\s*?(\\S+)\\s*?\\[\\s*?\\]\\s*$");

    private static String checkIdentifier(String name) {
        Matcher arrM = ARR.matcher(name);
        if (arrM.find()) {
            checkIdentifier(arrM.group(1));
            return name;
        }
        if (name.indexOf('<') > 0) {
            visitTypeNames(name, ClassBuilder::checkIdentifier);
            return name;
        } else if (name.indexOf('.') > 0) {
            for (String nm : name.split("\\.")) {
                checkIdentifier(nm);
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

    private static <T> T notNull(String what, T obj) {
        if (obj == null) {
            throw new IllegalArgumentException(what + " may not be null");
        }
        return obj;
    }

    private static BodyBuilder friendlyNumber(Number num) {
        BodyBuilder result;
        if (num instanceof Long) {
            result = new Adhoc(friendlyLong((Long) num) + "L");
        } else if (num instanceof Double) {
            result = new Adhoc(num.toString() + "D");
        } else if (num instanceof Float) {
            result = new Adhoc(num.toString() + "F");
        } else if (num instanceof Short) {
            result = new Adhoc("(short)" + Short.toString(num.shortValue()));
        } else if (num instanceof Byte) {
            String s = Byte.toString(num.byteValue());
            if (s.length() == 1) {
                s = "0" + s;
            }
            s = "0x" + s;
            result = new Adhoc(s);
        } else {
            result = new Adhoc(num.toString());
        }
        return result;
    }

    private static String friendlyLong(Long lng) {
        String s = Long.toString(lng);
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
            boolean anyParagraphs = false;
            boolean lastWasPara = false;
            String[] lines = docComment.split("\n");

            for (int i = 0; i < lines.length; i++) {
                // XXX if any paragraphs, close paragraph before a line
                // starting with @
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
                            lb.word("<p>");
                            lb.onNewLine();
                            anyParagraphs = true;
                            lastWasPara = true;
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
}
