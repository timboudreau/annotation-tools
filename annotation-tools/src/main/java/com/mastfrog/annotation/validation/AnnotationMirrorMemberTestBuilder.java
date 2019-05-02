package com.mastfrog.annotation.validation;

import com.mastfrog.predicates.NamedPredicate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import com.mastfrog.annotation.AnnotationUtils;

/**
 *
 * @author Tim Boudreau
 */
public final class AnnotationMirrorMemberTestBuilder<T> extends AbstractPredicateBuilder<AnnotationMirror, AnnotationMirrorMemberTestBuilder<T>, T> {

    private final String memberName;

    AnnotationMirrorMemberTestBuilder(Function<AnnotationMirrorMemberTestBuilder<T>, T> converter, String memberName, AnnotationUtils utils) {
        super(utils, converter);
        this.memberName = memberName;
    }

    public String memberName() {
        return memberName;
    }

    public AnnotationMirrorMemberTestBuilder<T> validateStringValueAsMimeType() {
        return stringValueMustMatch(namedPredicate("valid-mime-type", this::validateMimeType));
    }

    boolean validateMimeType(String value) {
        if (value == null || value.length() < 3) {
            fail("Mime type unset or too short to be one");
            return false;
        }
        if (value.length() > 80) {
            fail("Mime type too long: " + value.length() + " (" + value + ")");
            return false;
        }
        int ix = value.indexOf('/');
        int lix = value.lastIndexOf('/');
        if (ix < 0) {
            fail("No / character in mime type");
            return false;
        }
        if (lix != ix) {
            fail("More than one / character in mime type");
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                fail("Whitespace at " + i + " in mime type '" + value + "'");
                return false;
            }
            if (Character.isUpperCase(value.charAt(i))) {
                fail("Mime type should not contain upper case letters");
            }
            if (';' == value.charAt(i)) {
                fail("Complex mime types unsupported");
                return false;
            }
        }
        return true;
    }

    public AnnotationMirrorMemberTestBuilder<T> stringValueMustNotContainWhitespace() {
        return addPredicate("no-whitespace-in-" + memberName, (am) -> {
            List<String> vals = utils.annotationValues(am, memberName, String.class);
            boolean result = true;
            for (String val : vals) {
                for (int i = 0; i < val.length(); i++) {
                    if (Character.isWhitespace(val.charAt(i))) {
                        fail("Value of " + memberName + " may not contain whitespace");
                        result = false;
                    }
                }
            }
            return result;
        });
    }

    public AnnotationMirrorMemberTestBuilder<T> stringValueMustNotContain(char... chars) {
        char[] nue = Arrays.copyOf(chars, chars.length);
        Arrays.sort(nue);
        return addPredicate("no-chars-" + new String(chars) + "-in-string-value-of-" + memberName, (am) -> {
            List<String> vals = utils.annotationValues(am, memberName, String.class);
            boolean result = true;
            for (String val : vals) {
                for (int i = 0; i < val.length(); i++) {
                    int ix = Arrays.binarySearch(nue, val.charAt(i));
                    if (ix >= 0) {
                        fail("Value of " + memberName + " may not contain '" + chars[ix] + "'");
                        result = false;
                    }
                }
            }
            return result;
        });
    }

    public AnnotationMirrorMemberTestBuilder<T> arrayValueSizeMustEqualOneOf(int... sizes) {
        return arrayValueSizeMustEqualOneOf("Array size for " + memberName + " must be "
                + "one of these sizes: " + Arrays.toString(sizes), sizes);
    }

    public AnnotationMirrorMemberTestBuilder<T> arrayValueSizeMustEqualOneOf(String msg, int... sizes) {
        return addPredicate(memberName + "-array-value-size-must-be-one-of-{" + Arrays.toString(sizes) + "}",
                (p) -> {
                    List<Object> l = utils.annotationValues(p, memberName, Object.class);
                    boolean found = false;
                    for (int i = 0; i < sizes.length; i++) {
                        int size = sizes[i];
                        if (l.size() == size) {
                            found = true;
                            break;
                        }
                    }
                    return maybeFail(found, msg);
                });
    }

    public AnnotationMirrorMemberTestBuilder<T> arrayValueSizeMustBeGreaterThan(int size) {
        return addPredicate(memberName + "-array-value-size-must-be->" + size,
                (p) -> {
                    List<Object> l = utils.annotationValues(p, memberName, Object.class);
                    return maybeFail(l.size() <= size, memberName + " must have an array "
                            + "size greater than " + size);
                });
    }

    public AnnotationMirrorMemberTestBuilder<T> arrayValueSizeMustBeLessThan(int size) {
        return addPredicate(memberName + "-array-value-size-must-be->" + size,
                (p) -> {
                    List<Object> l = utils.annotationValues(p, memberName, Object.class);
                    return maybeFail(l.size() >= size, memberName + " must have an array "
                            + "size greater than " + size);
                });
    }

    public AnnotationMirrorMemberTestBuilder<T> arrayValueMayNotBeEmpty() {
        return addPredicate(memberName + "-array-value-must-have-contents", am -> {
            List<?> objs = utils.annotationValues(am, memberName, Object.class);
            return maybeFail(!objs.isEmpty(), "Value of '" + memberName + "' may not be empty");
        });
    }

    public AnnotationMirrorMemberTestBuilder<T> enumValuesMayNotCombine(String a, String b) {
        return addPredicate(memberName + "-enum-values-may-not-combine-" + a + "-" + b, am -> {
            Set<String> all = utils.enumConstantValues(am, memberName);
            if (all.contains(a) && all.contains(b)) {
                fail(memberName + " may not combine '" + a + "' and '" + b + "'");
                return false;
            }
            return true;
        });
    }

    public AnnotationMirrorMemberTestBuilder<T> stringValueMustNotBeEmpty() {
        return stringValueMustMatch(namedPredicate(memberName + "-string-value-not-empty-in", (val) -> {
            boolean result = val != null && !val.isEmpty();
            if (!result) {
                fail("Value must not be empty");
            }
            return result;
        }));
    }

    public AnnotationMirrorMemberTestBuilder<T> intValueMustBeGreaterThanOrEqualTo(int val) {
        return intValueMustBeGreaterThan(val - 1, true);
    }

    public AnnotationMirrorMemberTestBuilder<T> intValueMustBeGreaterThan(int val) {
        return intValueMustBeGreaterThan(val, false);
    }

    private AnnotationMirrorMemberTestBuilder<T> intValueMustBeGreaterThan(int val, boolean e) {
        return addPredicate(memberName + "-int-value>" + (e ? "=" : "") + (e ? val+1 : val), mir -> {
            boolean result = true;
            String msg = !e ? "Value of " + memberName + " must be > " + val
                    : "Value of " + memberName + " must be >= " + (val + 1);
            List<Integer> l = utils.annotationValues(mir, memberName, Integer.class);
            for (int i = 0; i < l.size(); i++) {
                Integer v = l.get(i);
                if (v <= val) {
                    fail(msg + " but found " + v + " at index " + i);
                    result = false;
                }
            }
            return result;
        });
    }

    public AnnotationMirrorMemberTestBuilder<T> intValueMustBeLessThanOrEqualTo(int val) {
        return intValueMustBeLessThan(val + 1, true);
    }

    public AnnotationMirrorMemberTestBuilder<T> intValueMustBeLessThan(int val) {
        return intValueMustBeLessThan(val, false);
    }

    private AnnotationMirrorMemberTestBuilder<T> intValueMustBeLessThan(int val, boolean e) {
        return addPredicate(memberName + "-int-value<" + (e ? "=" : "") + (e ? val-1 : val), mir -> {
            boolean result = true;
            List<Integer> l = utils.annotationValues(mir, memberName, Integer.class);
            String msg = e ? "Value of " + memberName + " must be <= " + (val - 1)
                    : "Value of " + memberName + " must be < " + val;
            for (int i = 0; i < l.size(); i++) {
                Integer v = l.get(i);
                if (v >= val) {
                    fail(msg + " but found " + v + " at index " + i);
                    result = false;
                }
            }
            return result;
        });
    }

    public AnnotationMirrorMemberTestBuilder<T> stringValueMustBeValidJavaIdentifier() {
        return stringValueMustMatch(namedPredicate(memberName + "-must-be-valid-java-identifier", (val) -> {
            if (val == null || val.isEmpty()) {
                return true;
            }
            int max = val.length();
            for (int i = 0; i < max; i++) {
                char c = val.charAt(i);
                boolean result;
                if (i == 0) {
                    result = Character.isJavaIdentifierStart(c);
                } else {
                    result = Character.isJavaIdentifierPart(c);
                }
                return maybeFail(result, "Invalid identifier '" + val
                        + "' may not contain: "
                        + c + " - not a valid Java identifier.");
            }
            return true;
        }));
    }

    public AnnotationMirrorMemberTestBuilder<T> stringValueMustBeValidRegularExpression() {
        return stringValueMustMatch(namedPredicate(memberName + "-must-be-valid-reged", (val) -> {
            if (val == null || val.isEmpty()) {
                return true;
            }
            try {
                Pattern.compile(val);
            } catch (Exception e) {
                fail("Invalid regular expression for " + memberName + ": " + e.getMessage());
            }
            return true;
        }));
    }


    public TypeElementTestBuilder<AnnotationMirrorMemberTestBuilder<T>, ?> asTypeSpecifier() {
        return (TypeElementTestBuilder /* JDK 8 javac compatibility */) new TypeElementTestBuilder<>(utils, tetb -> {
            NamedPredicate<TypeElement> p = tetb._predicate();
            return addPredicate(memberName + "-as-type-element:" + p.name(), mir -> {
                List<TypeElement> l = utils.typeElements(mir, memberName, this::fail);
                return p.toListPredicate(true).test(l);
            });
        });
    }

    public AnnotationMirrorMemberTestBuilder<T> asTypeSpecifier(Consumer<TypeElementTestBuilder<?, ?>> c) {
        boolean[] built = new boolean[1];
        TypeElementTestBuilder<Void, ?> t = (TypeElementTestBuilder /* JDK 8 javac compatibility */) new TypeElementTestBuilder<>(utils, tetb -> {
            addPredicate(tetb._predicate().listTransform(
                    am -> utils.typeElements(am, memberName, this::fail)));
            built[0] = true;
            return null;
        });
        c.accept(t);
        if (!built[0]) {
            t.build();
        }
        return this;
    }

    private <X> boolean testAnnotationValueOrValues(AnnotationValue v, Class<X> type, Predicate<? super X> pred) {
        boolean result = true;
        if (type.isInstance(v.getValue())) {
            result = pred.test(type.cast(v.getValue()));
        } else if (v.getValue() instanceof List<?>) {
            for (Object o : ((List<?>) v.getValue())) {
                if (o instanceof AnnotationValue) {
                    AnnotationValue an = (AnnotationValue) o;
                    Object o1 = an.getValue();
                    if (type.isInstance(o1)) {
                        result &= pred.test(type.cast(o1));
                    }
                } else if (type.isInstance(v.getValue())) {
                    result &= pred.test(type.cast(v.getValue()));
                }
            }
        } else {
            result = false;
            fail("Not a " + type.getSimpleName() + ", subtype, or array "
                    + "thereof: " + v.getValue() + " ("
                    + v.getValue().getClass().getSimpleName() + ")");
        }
        return result;
    }

    public AnnotationMirrorMemberTestBuilder<T> stringValueMustMatch(Pattern pattern) {
        return stringValueMustMatch(new RegexPredicate(pattern));
    }

    static final class RegexPredicate implements NamedPredicate<String> {

        private final Pattern pattern;

        public RegexPredicate(Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public boolean test(String t) {
            return pattern.matcher(t).matches();
        }

        @Override
        public String name() {
            return pattern.pattern();
        }
    }

    public AnnotationMirrorMemberTestBuilder<T> stringValueMustEndWith(String... postfixes) {
        return stringValueMustMatch(namedPredicate("one-of-suffixes{" + AnnotationUtils.join(',', postfixes) + "}", sv -> {
            if (sv == null) {
                return true;
            }
            for (String postfix : postfixes) {
                if (sv.endsWith(postfix)) {
                    return true;
                }
            }
            fail(memberName + " must end with one of " + AnnotationUtils.join(',', postfixes));
            return false;
        }));
    }

    public AnnotationMirrorMemberTestBuilder<T> stringValueMustMatch(BiPredicate<String, Consumer<String>> bp) {
        return stringValueMustMatch(s -> {
            return bp.test(s, this::fail);
        });
    }

    public AnnotationMirrorMemberTestBuilder<T> stringValueMustMatch(Predicate<String> bp) {
        return addPredicate("string-value-of-" + memberName + "-must-match-" + bp, (mirror) -> {
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : mirror.getElementValues().entrySet()) {
                if (memberName.equals(e.getKey().getSimpleName().toString())) {
                    testAnnotationValueOrValues(e.getValue(), String.class, bp);
                }
            }
            return true;
        });
    }

    public AnnotationMirrorMemberTestBuilder<T> mustBeSubtypeOf(String... names) {
        return addPredicate("must-be-subtypes-of-" + Arrays.toString(names) + "-in-" + memberName, (mir) -> {
            List<TypeMirror> l = utils.typeValues(mir, memberName, this::fail, names);
            return maybeFail(!l.isEmpty(), memberName + " must be a subtype of " + AnnotationUtils.join(',', names));
        });
    }

    public AnnotationMirrorMemberTestBuilder<T> mustBeSubtypeOfIfPresent(String... names) {
        return addPredicate("if-present-must-be-subtype-of-" + AnnotationUtils.join(',', names) + "-in-" + memberName, (mir) -> {
            List<TypeMirror> l = utils.typeValues(mir, memberName, this::fail, names);
            if (l.isEmpty()) {
                return true;
            }
            Set<TypeMirror> expected = toTypeMirrors(Arrays.asList(names));
            Types t = utils.processingEnv().getTypeUtils();
            boolean allFound = true;
            outer:
            for (TypeMirror found : l) {
                boolean foundIt = true;
                for (TypeMirror e : expected) {
                    if (t.isSameType(e, found) || t.isSubtype(e, found)) {
                        foundIt = true;
                        break;
                    }
                }
                if (!foundIt) {
                    fail(found + " is not a subtype of " + AnnotationUtils.join(',', names));
                }
                allFound &= foundIt;
            }
            return allFound;
        });
    }

    private Set<TypeMirror> toTypeMirrors(Iterable<String> names) {
        Set<TypeMirror> result = new HashSet<>();
        for (String name : names) {
            TypeElement el = utils.processingEnv().getElementUtils().getTypeElement(name);
            if (el == null) {
                warn("Could not load " + name + " from classpath");
                continue;
            }
            result.add(el.asType());
        }
        return result;
    }

    public <R> AnnotationMirrorMemberTestBuilder<T> valueMustBeOfSimpleType(Class<R> type) {
        return addPredicate("value-must-be-" + type.getSimpleName() + "-in-" + memberName, (mir) -> {
            Object o = utils.annotationValue(mir, memberName, Object.class);
            if (o != null && !type.isInstance(o)) {
                fail("Expected instance of " + type.getSimpleName() + " but found " + o + " (" + o.getClass().getName() + ") for " + memberName);
                return false;
            }
            return true;
        });
    }

    private List<TypeMirror> typeMirrorsForMember(AnnotationMirror mir) {
        List<String> types = utils.typeList(mir, memberName);
        List<TypeMirror> els = new ArrayList<>(types.size());
        types.forEach((type) -> {
            TypeElement te = utils.processingEnv().getElementUtils().getTypeElement(type);
            if (te != null) {
                els.add(te.asType());
            } else {
                fail("Could not resolve " + type);
            }
        });
        return els;
    }

    public TypeMirrorTestBuilder<AnnotationMirrorMemberTestBuilder<T>> asType() {
        return new TypeMirrorTestBuilder<>(utils, tmtb -> {
            NamedPredicate<Iterable<TypeMirror>> pred = tmtb._predicate().toListPredicate(true);
            return addPredicate(this::typeMirrorsForMember, pred);
        });
    }

    public AnnotationMirrorMemberTestBuilder<T> asType(Consumer<TypeMirrorTestBuilder<?>> c) {
        boolean[] built = new boolean[1];
        TypeMirrorTestBuilder<Void> b = new TypeMirrorTestBuilder<>(utils, tmtb -> {
            addPredicate(this::typeMirrorsForMember, tmtb._predicate().toListPredicate(true));
            built[0] = true;
            return null;
        });
        c.accept(b);
        if (!built[0]) {
            b.build();
        }
        return this;
    }

    private TypeElement toTypeElement(TypeMirror mir) {
        if (mir instanceof DeclaredType) {
            return (TypeElement) ((DeclaredType) mir).asElement();
        } else {
            return utils.processingEnv().getElementUtils().getTypeElement(mir.toString());
        }
    }

    public TypeElementTestBuilder<AnnotationMirrorMemberTestBuilder<T>, ?> valueAsTypeElement() {
        return (TypeElementTestBuilder /* JDK 8 javac compatibility */) new TypeElementTestBuilder<>(utils, tmtb -> {
            return addPredicate(tmtb._predicate().name(), mir -> {
                boolean result = true;
                List<TypeMirror> l = utils.typeValues(mir, memberName, this::fail);
                for (TypeMirror t : l) {
                    result &= tmtb.predicate().test(toTypeElement(t));
                }
                return result;
            });
        });
    }

    public AnnotationMirrorMemberTestBuilder<T> valueAsTypeElement(Consumer<TypeElementTestBuilder<?, ?>> c) {
        boolean[] built = new boolean[1];
        TypeElementTestBuilder<Void, ?> b = (TypeElementTestBuilder /* JDK 8 javac compatibility */) new TypeElementTestBuilder<>(utils, tmtb -> {
            addPredicate(tmtb._predicate().name(), (AnnotationMirror mir) -> {
                List<TypeMirror> l = utils.typeValues(mir, memberName, this::fail);
                boolean result = true;
                for (TypeMirror tm : l) {
                    result &= tmtb.predicate().test(toTypeElement(tm));
                }
                return result;
            });
            built[0] = true;
            return null;
        });
        c.accept(b);
        if (!built[0]) {
            b.build();
        }
        return this;
    }
}
