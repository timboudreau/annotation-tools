/*
 * The MIT License
 *
 * Copyright 2019 Mastfrog Technologies.
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

import com.mastfrog.function.throwing.ThrowingConsumer;
import static com.mastfrog.java.vogon.ClassBuilder.invocationOf;
import static com.mastfrog.java.vogon.ClassBuilder.variable;
import com.mastfrog.util.file.FileUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import javax.lang.model.element.Modifier;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 *
 * @author Tim Boudreau
 */
public class GenerateClassesTest {

    private static final String packageName = "com.mastfrog.java.vogon.test";
    static Path dir;
    static Path pkgDir;
    static URLClassLoader ldr;

    @Test
    public void testSimple() throws Throwable {
        ISimpleBean proxy = loadAndCreateProxy("SimpleBean", ISimpleBean.class);
        assertNotNull(proxy);
        assertEquals("Hello", proxy.getFoo());
        proxy.setFoo("Goodbye");
        assertEquals("Goodbye", proxy.getFoo());
    }

    @Test
    public void testLambdasAndAnnotations() throws Throwable {
        try {
            IAnnotationsAndLambdas proxy = loadAndCreateProxy("AnnotationsAndLambdas", IAnnotationsAndLambdas.class);
            assertNotNull(proxy);
            assertNotNull(lastProxied);
            Annotation[] annos = lastProxied.getClass().getAnnotations();
            assertNotEquals(0, annos.length, "No annotations found");
            assertEquals(1, annos.length);
            String annoString = annos[0].toString();
            // JDK in continuous build apparently doesn't quote strings in annotations?
            assertEquals("@com.mastfrog.java.vogon.test.SomeAnnotation(value=Hoober, thing=56)",
                    annoString.replace("\"", ""));
            assertEquals(0, proxy.lastValue());

            assertTrue(proxy.time() <= System.currentTimeMillis());
            assertTrue(proxy.time2() <= System.currentTimeMillis());

            proxy.doSomething(val -> {
                assertTrue(val instanceof Integer);
                assertEquals(-1932, val);
            });
            Consumer<Integer> c = proxy.getDoerOfSomething((short) 7397);
            assertEquals(0, proxy.lastValue());
            c.accept(100);
            assertEquals(-621348, proxy.lastValue());
            Object[] stuff = proxy.getStuff();

            assertFalse(proxy.notBool(), srcMessage("If statement was not negated", "AnnotationsAndLambdas",
                    "notBool", "privvy"));

            for (int i = 0; i < stuff.length; i++) {
//            System.out.println(i + ".\t" + stuff[i].getClass().getName() + "\t" + stuff[i]);
                Object o = stuff[i];
                switch (i) {
                    case 0:
                        assertTrue(o instanceof Long);
                        assertTrue(System.currentTimeMillis() >= ((Long) o));
                        break;
                    case 1:
                        assertEquals(Double.valueOf(1), o);
                        break;
                    case 2:
                        assertTrue(o instanceof StringBuilder);
                        assertEquals("Hello world", o.toString());
                        break;
                    case 3:
                        assertEquals(Short.valueOf((short) 23), o);
                        break;
                    case 4:
                        assertEquals(Integer.valueOf(70), o);
                        break;
                    case 5:
                        assertEquals("Hello-" + Locale.getDefault(), o);
                        break;
                    case 6:
                        assertEquals(Character.valueOf('x'), o);
                        break;
                    case 7:
                        Assertions.assertArrayEquals(new int[]{1}, (int[]) o);
                        break;
                    default:
                        fail("Too many elements");
                }
            }
        } catch (UndeclaredThrowableException ute) {
            throw ute.getCause();
        }
    }

    static Map<String, ClassBuilder<String>> sources = new HashMap<>();

    static Supplier<String> srcMessage(String message, String type, String... methd) {
        return () -> {
            ClassBuilder<String> cb = sources.get(type);
            if (cb != null) {
                StringBuilder sb = new StringBuilder(message).append(" - sources in ").append(type).append(" \n\n");
                for (String m : methd) {
                    sb.append(cb.methodSource(m));
                    sb.append('\n');
                }
                return sb.toString();
            }
            return message + " (no class '" + type + "' in " + sources.keySet() + ")";
        };
    }

    @BeforeAll
    public static void setup() throws Exception {
        dir = FileUtils.newTempDir();
        pkgDir = dir.resolve(packageName.replace('.', '/'));
        Files.createDirectories(pkgDir);
        Set<Path> javaSources = new HashSet<>();
        JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager mgr = compiler.getStandardFileManager(new DL(), Locale.US, UTF_8);
        mgr.setLocation(StandardLocation.SOURCE_PATH, Collections.singleton(dir.toFile()));
        mgr.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(dir.toFile()));
        // JDK 11:
//        mgr.setLocationFromPaths(StandardLocation.SOURCE_PATH, Collections.singleton(dir));
//        mgr.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, Collections.singleton(dir));
        Set<JavaFileObject> fos = new HashSet<>();
        generateClasses(cb -> {
            Path file = pkgDir.resolve(cb.className() + ".java");
            String body = cb.build();
            Files.write(file, body.getBytes(UTF_8));
            javaSources.add(file);
            JavaFileObject fo = mgr.getJavaFileForInput(StandardLocation.SOURCE_PATH, cb.fqn(), Kind.SOURCE);
            sources.put(cb.className(), cb);
            fos.add(fo);
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamWriter w = new OutputStreamWriter(out);
        JavaCompiler.CompilationTask task = compiler.getTask(w, mgr, new DL(), Collections.emptySet(), Collections.emptyList(), fos);
        task.call();
        ldr = createClassLoader();
    }

    private static URLClassLoader createClassLoader() throws MalformedURLException {
        URL url = dir.toUri().toURL();
        return new URLClassLoader(new URL[]{url}, Thread.currentThread().getContextClassLoader());
    }

    @AfterAll
    public static void teardown() throws IOException {
        FileUtils.deltree(dir);
    }

    @Test
    public void testNumbers() throws Exception {
        Class<?> nums = ldr.loadClass(packageName + "." + "Numbers");

        assertEquals(Byte.valueOf(Byte.MIN_VALUE), findStaticField(Byte.TYPE, "BYTE_MIN", nums));
        assertEquals(Byte.valueOf(Byte.MAX_VALUE), findStaticField(Byte.TYPE, "BYTE_MAX", nums));
        assertEquals(Short.valueOf(Short.MIN_VALUE), findStaticField(Short.TYPE, "SHORT_MIN", nums));
        assertEquals(Short.valueOf(Short.MAX_VALUE), findStaticField(Short.TYPE, "SHORT_MAX", nums));
        assertEquals(Integer.valueOf(Integer.MIN_VALUE), findStaticField(Integer.TYPE, "INT_MIN", nums));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), findStaticField(Integer.TYPE, "INT_MAX", nums));
        assertEquals(Long.valueOf(Long.MIN_VALUE), findStaticField(Long.TYPE, "LONG_MIN", nums));
        assertEquals(Long.valueOf(Long.MAX_VALUE), findStaticField(Long.TYPE, "LONG_MAX", nums));
        assertEquals(Character.valueOf(Character.MIN_VALUE), findStaticField(Character.TYPE, "CHAR_MIN", nums));
        assertEquals(Character.valueOf(Character.MAX_VALUE), findStaticField(Character.TYPE, "CHAR_MAX", nums));

        assertEquals(Short.valueOf((short) (Short.MIN_VALUE + 1)), findStaticField(Short.TYPE, "SHORT_MIN_1", nums));
        assertEquals(Short.valueOf((short) (Short.MAX_VALUE - 1)), findStaticField(Short.TYPE, "SHORT_MAX_1", nums));
        assertEquals(Integer.valueOf(Integer.MIN_VALUE + 1), findStaticField(Integer.TYPE, "INT_MIN_1", nums));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE - 1), findStaticField(Integer.TYPE, "INT_MAX_1", nums));
        assertEquals(Long.valueOf(Long.MIN_VALUE + 1), findStaticField(Long.TYPE, "LONG_MIN_1", nums));
        assertEquals(Long.valueOf(Long.MAX_VALUE - 1), findStaticField(Long.TYPE, "LONG_MAX_1", nums));

        assertEquals(Short.valueOf((short) (Short.MIN_VALUE / 2)), findStaticField(Short.TYPE, "SHORT_MIN_2", nums));
        assertEquals(Short.valueOf((short) (Short.MAX_VALUE / 2)), findStaticField(Short.TYPE, "SHORT_MAX_2", nums));
        assertEquals(Integer.valueOf(Integer.MIN_VALUE / 2), findStaticField(Integer.TYPE, "INT_MIN_2", nums));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE / 2), findStaticField(Integer.TYPE, "INT_MAX_2", nums));
        assertEquals(Long.valueOf(Long.MIN_VALUE / 2), findStaticField(Long.TYPE, "LONG_MIN_2", nums));
        assertEquals(Long.valueOf(Long.MAX_VALUE / 2), findStaticField(Long.TYPE, "LONG_MAX_2", nums));

        byte[] bytes = findStaticField(byte[].class, "bytes", nums);

        short[] shorts = findStaticField(short[].class, "shorts", nums);
        int[] ints = findStaticField(int[].class, "ints", nums);
        long[] longs = findStaticField(long[].class, "longs", nums);
        int start = (((int) Byte.MIN_VALUE) - 10);

        int bmv = -Byte.MIN_VALUE;
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            assertEquals(i, (int) bytes[i + bmv], "Wrong byte value at " + (i + -Byte.MIN_VALUE));
        }
        int end = ((int) Byte.MAX_VALUE + 10);

        for (int i = start; i < end; i++) {
            int val = shorts[i - start];
            assertEquals(i, val, "Wrong short value at " + (i + -start));
        }
        for (int i = start; i < end; i++) {
            int val = ints[i - start];
            assertEquals(i, val, "Wrong int value at " + (i + -start));
        }
        for (int i = start; i < end; i++) {
            int val = ints[i - start];
            assertEquals(i, val, "Wrong long value at " + (i + -start));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T findStaticField(Class<T> type, String name, Class<?> on) throws Exception {
        Object o = null;
        try {
            Field f = on.getField(name);
            o = f.get(null);
            assertNotNull(o, "Field value for " + name + " is null");
            if (!type.isPrimitive()) {
                assertTrue(type.isInstance(o), "Not an instance of " + type.getSimpleName() + ": " + o);
            }
            // This has to be an unsafe cast because of Integer.TYPE vs Integer.class and similar
            return (T) o;
        } catch (NoSuchFieldException nsfe) {
            throw new AssertionError("No field " + name + " of type " + type.getSimpleName() + " on " + on.getName(), nsfe);
        }
    }

    private static void generateClasses(ThrowingConsumer<ClassBuilder<String>> c) throws Exception {
        generateSimpleBean(c);
        generateAnnotation(c);
        generateAnnotationsAndLambdas(c);
        generateNumbersTest(c);
        generateSwitchImpl(c);
    }

    private static void generateNumbersTest(ThrowingConsumer<ClassBuilder<String>> c) throws Exception {
        ClassBuilder<String> cb = ClassBuilder.forPackage(packageName).named("Numbers")
                .withModifier(PUBLIC);

        cb.field("BYTE_MIN").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Byte.MIN_VALUE);
        cb.field("BYTE_MAX").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Byte.MAX_VALUE);
        cb.field("SHORT_MIN").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Short.MIN_VALUE);
        cb.field("SHORT_MAX").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Short.MAX_VALUE);
        cb.field("INT_MIN").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Integer.MIN_VALUE);
        cb.field("INT_MAX").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Integer.MAX_VALUE);
        cb.field("LONG_MIN").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Long.MIN_VALUE);
        cb.field("LONG_MAX").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Long.MAX_VALUE);
        cb.field("CHAR_MIN").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Character.MIN_VALUE);
        cb.field("CHAR_MAX").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Character.MAX_VALUE);

        cb.field("SHORT_MIN_1").withModifier(PUBLIC, STATIC, FINAL).initializedWith((short) (Short.MIN_VALUE + 1));
        cb.field("SHORT_MAX_1").withModifier(PUBLIC, STATIC, FINAL).initializedWith((short) (Short.MAX_VALUE - 1));
        cb.field("INT_MIN_1").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Integer.MIN_VALUE + 1);
        cb.field("INT_MAX_1").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Integer.MAX_VALUE - 1);
        cb.field("LONG_MIN_1").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Long.MIN_VALUE + 1);
        cb.field("LONG_MAX_1").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Long.MAX_VALUE - 1);

        cb.field("SHORT_MIN_2").withModifier(PUBLIC, STATIC, FINAL).initializedWith((short) (Short.MIN_VALUE / 2));
        cb.field("SHORT_MAX_2").withModifier(PUBLIC, STATIC, FINAL).initializedWith((short) (Short.MAX_VALUE / 2));
        cb.field("INT_MIN_2").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Integer.MIN_VALUE / 2);
        cb.field("INT_MAX_2").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Integer.MAX_VALUE / 2);
        cb.field("LONG_MIN_2").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Long.MIN_VALUE / 2);
        cb.field("LONG_MAX_2").withModifier(PUBLIC, STATIC, FINAL).initializedWith(Long.MAX_VALUE / 2);

        cb.field("bytes").withModifier(PUBLIC, STATIC, FINAL).initializedAsArrayLiteral("byte", alb -> {
            byte start = Byte.MIN_VALUE;
            byte end = Byte.MAX_VALUE;
            for (byte b = start; b <= end; b++) {
                alb.literal(b);
                if (b == end) {
                    // adding one will circle back around to Byte.MIN_VALUE
                    // and we will never exit
                    break;
                }
            }
        });

        cb.field("shorts").withModifier(PUBLIC, STATIC, FINAL).initializedAsArrayLiteral("short", alb -> {
            short start = (short) (((int) Byte.MIN_VALUE) - 10);
            short end = (short) ((int) Byte.MAX_VALUE + 10);
            for (short s = start; s <= end; s++) {
                alb.literal(s);
            }
        });

        cb.field("ints").withModifier(PUBLIC, STATIC, FINAL).initializedAsArrayLiteral("int", alb -> {
            int start = (((int) Byte.MIN_VALUE) - 10);
            int end = ((int) Byte.MAX_VALUE + 10);
            for (int b = start; b <= end; b++) {
                alb.literal(b);
            }
        });
        cb.field("longs").withModifier(PUBLIC, STATIC, FINAL).initializedAsArrayLiteral("long", alb -> {
            long start = (((int) Byte.MIN_VALUE) - 10);
            long end = ((int) Byte.MAX_VALUE + 10);
            for (long b = start; b <= end; b++) {
                alb.literal(b);
            }
        });
        c.accept(cb);
    }

    private static void generateSimpleBean(ThrowingConsumer<ClassBuilder<String>> c) throws Exception {
        ClassBuilder<String> cb = ClassBuilder.forPackage(packageName).named("SimpleBean")
                .withModifier(PUBLIC);
        cb.field("foo").withModifier(Modifier.PRIVATE).initializedWith("Hello");
        cb.constructor().setModifier(Modifier.PUBLIC).emptyBody();
        cb.publicMethod("getFoo").returning("String").body(bb -> {
            bb.returningField("foo").of("this");
        });
        cb.publicMethod("setFoo", mb -> {

            mb.withModifier(Modifier.FINAL, Modifier.SYNCHRONIZED).addArgument("String", "newFoo")
                    .body().assign("foo").toExpression("newFoo")
                    .log(Level.INFO).argument("foo").logging("Assign foo to {0}")
                    .endBlock();
        });
        cb.overridePublic("toString").returning("String").body(bb -> {
//            bb.returning("\"SimpleBean(\" + getFoo() + \")\"");
            bb.returningValue().concatenate("SimpleBean(").with().invoke("getFoo").inScope().with().literal(")").endConcatenation();
        });
        c.accept(cb);
    }

    private static void generateAnnotation(ThrowingConsumer<ClassBuilder<String>> c) throws Exception {
        ClassBuilder<String> cb = ClassBuilder.forPackage(packageName).named("SomeAnnotation").toAnnotationType()
                .withModifier(Modifier.PUBLIC).staticImport(ElementType.class.getName() + ".TYPE")
                .staticImport(RetentionPolicy.class.getName() + ".RUNTIME");
        cb.annotatedWith(Retention.class.getName(), ab -> {
            ab.addExpressionArgument("value", "RUNTIME");
        });
        cb.annotatedWith(Target.class.getName()).addExpressionArgument("value",
                ElementType.TYPE.name()).closeAnnotation();
        cb.annotationMethod("value").withDefault("foo!");
        cb.annotationMethod("thing").ofInt();
        c.accept(cb);
    }

    private static void generateAnnotationsAndLambdas(ThrowingConsumer<ClassBuilder<String>> c) throws Exception {
        ClassBuilder<String> cb = ClassBuilder.forPackage(packageName).named("AnnotationsAndLambdas")
                .importing(Consumer.class.getName())
                .withModifier(Modifier.PUBLIC);

        cb.annotatedWith("SomeAnnotation").addArgument("value", "Hoober").addArgument("thing", 56).closeAnnotation();

        cb.field("shortValue").initializedWith((short) 23);
        cb.field("lastValue").ofType("int");

        cb.method("doSomething").returning(cb.className()).withModifier(PUBLIC)
                .addArgument("Consumer<Integer>", "c")
                .body(bb -> {
                    bb.invoke("accept")
                            .withNumericExpressionArgument((byte) 42)
                            .parenthesized()
                            .castTo(ClassBuilder.NumericCast.INTEGER)
                            .parenthesized()
                            .times().field("shortValue").of("this").parenthesized()
                            .minus().literal(1).parenthesized().shiftLeft(1).complement()
                            .endNumericExpression()
                            .on("c");
                    bb.returning("this");
                });

        cb.publicMethod("getDoerOfSomething").addArgument("short", "val")
                .returning("Consumer<Integer>").makeFinal()
                .withModifier(PUBLIC).body(bb -> {
            bb.assign("shortValue").toExpression("val");
            bb.returningLambda(lb -> {
                lb.withArgument("Integer", "value")
                        .body(lbb -> {
                            lbb.invoke("doSomething").withLambdaArgument(lb2 -> {
                                lb2.withArgument("Integer", "actualValue")
                                        .body(lbb2 -> {
                                            lbb2.assign("lastValue").toExpression("actualValue");
                                        });
                            }).onThis();
                            lbb.endBlock();
                        });
            });
        });
        cb.publicMethod("lastValue").returning("int").body().returning("lastValue").endBlock();

        cb.field("stuff").initializedAsArrayLiteral("Object", alb -> {
            alb.invoke("currentTimeMillis").on("System");
            alb.ternary().booleanExpression("1 == 1").literal(1D).literal(0L);
            alb.newInstance().withStringLiteral("Hello world").ofType("StringBuilder");
            alb.field("shortValue", frb -> {
                frb.ofThis();
            });
            alb.value().numeric(7).parenthesized().times().literal(10).plus(2).dividedBy().literal((short) 9).modulo((byte) 2).endNumericExpression();
            alb.value().stringConcatenation().literal("Hello-").with().invoke("getDefault").on(Locale.class.getName()).endConcatenation();
            alb.literal('x');
            alb.arrayLiteral("int", al -> {
                al.add("1");
            });
        });

        cb.publicMethod("getStuff").returning("Object[]").body("return this.stuff;");

        cb.publicMethod("time").returning("long").body().returningInvocationOf("currentTimeMillis").on("System").endBlock();

        cb.publicMethod("time2", mb -> {
            mb.withModifier(FINAL).returning("long")
                    .body().returningInvocationOf("currentTimeMillis").on("System").endBlock();
        });

        cb.privateMethod("privvy").returning("boolean").addArgument("int", "ignored")
                .body().statement("return true").endBlock();

        cb.publicMethod("notBool", mb -> {
            mb.returning("boolean").body(bb -> {
                bb.iff().not().invokeAsBoolean("privvy").withArgument("7").inScope()
                        .statement("return true").endIf();
                bb.returning("false");
            });
        });

        c.accept(cb);
    }

    static final class DL implements DiagnosticListener<JavaFileObject> {

        @Override
        public void report(Diagnostic diagnostic) {
            System.out.println(diagnostic);
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                throw new AssertionError("Compile failed.\n" + diagnostic);
            }
        }
    }

    public interface ISimpleBean {

        String getFoo();

        void setFoo(String val);
    }

    public interface IAnnotationsAndLambdas {

        IAnnotationsAndLambdas doSomething(Consumer<Integer> c);

        Consumer<Integer> getDoerOfSomething(short val);

        int lastValue();

        Object[] getStuff();

        long time();

        long time2();

        boolean notBool();
    }

    public interface ISwitch {

        String switchit(int val, char c, String msg);
    }

    @Test
    public void testSwitch() throws Throwable {
        ISwitch proxy = loadAndCreateProxy("SwitchImpl", ISwitch.class);
        ISwitch impl = new SwitchIt();

        ISwitch tester = (v, c, m) -> {
            return assertMatch(impl, v, c, m, proxy);
        };

        for (char c = 'a'; c < 'f'; c++) {
            for (int i = 200; i < 270; i++) {
                tester.switchit(i, c, "item." + i + ".test");
            }
        }
    }

    private String assertMatch(ISwitch expect, int val, char c, String msg, ISwitch proxy) {
        String result = expect.switchit(val, c, msg);
        String got = proxy.switchit(val, c, msg);
        assertEquals(result, got, "Not same result for " + val + ", '" + c + "', \"" + msg + "\"");
        return result;
    }

    private static void generateSwitchImpl(ThrowingConsumer<ClassBuilder<String>> c) throws Exception {
        ClassBuilder<String> cb = ClassBuilder.forPackage(packageName).named("SwitchImpl")
                .makePublic().autoToString()
                .method("switchit", mb -> {
                    mb.withModifier(PUBLIC)
                            .returning("String")
                            .addArgument("int", "val")
                            .addArgument("char", "c")
                            .addArgument("String", "msg")
                            .body(bb -> {
                                bb.iff().literal(0).equals().expression("val").endCondition()
                                        .iff(ib -> {
                                            ib.variable("c").equals().literal('e').endCondition()
                                                    .returningStringConcatenation("ZERO-e-", scb -> {
                                                        scb.appendExpression("msg")
                                                                .append("-")
                                                                .append((short) 5338)
                                                                .appendExpression("val");
                                                    });
                                        }).endIf();
                                bb.switchingOn("c", sw -> {
                                    sw.inCase('a', caseA -> {
                                        caseA.declare("v").initializedTo().
                                                numeric(veb -> {
                                                    veb.expression("val")
                                                            .times().expression("c")
                                                            .endNumericExpression();
                                                }).as("int");
                                        caseA.returningStringConcatenationExpression("msg", cat -> {
                                            cat.append("-")
                                                    .appendExpression("v");

                                        });
                                    });
                                    sw.inCase('b', caseB -> {
                                        /*
                    return Integer.toString(val + (c % 2) * msg.hashCode() ^ 23)
                            + msg;
                                         */
                                        caseB.iff().compare(variable("val").modulo(2))
                                                .equals(ClassBuilder.number(0))
                                                .endCondition()
                                                .returningStringConcatenationExpression("msg", scb -> {
                                                    scb.append("-")
                                                            .appendExpression("val");
                                                })
                                                .orElse(ecb -> {
                                                    ecb.returningInvocationOf("toString")
                                                            .withArgument("c")
                                                            .on("Character");
                                                });

//                                        caseB.iff().numericExpression("val")
//                                                .modulo(2)
//                                                .endNumericExpression()
//                                                .equals()
//                                                .literal(0)
//                                                .endCondition()
//                                                .returningStringConcatenationExpression("msg", scb -> {
//                                                    scb.append("-")
//                                                            .appendExpression("val");
//                                                })
//                                                .orElse(ecb -> {
//                                                    ecb.returningInvocationOf("toString")
//                                                            .withArgument("c")
//                                                            .on("Character");
//                                                });
                                    });
                                    sw.inCase('c', caseC -> {
                                        caseC.returningInvocationOf("toString")
                                                .withArgument()
                                                .numeric().expression("val")
                                                .plus()
                                                .expression("c")
                                                .endNumericExpression()
                                                .on("Integer");
                                    });
                                    sw.inCase('d').returningStringLiteral("blork").endBlock();

                                    sw.inCase('e').returningStringConcatenation("", scb -> {
//                                        return Integer.toString(val + (c % 2) * msg.hashCode() ^ 23)
//                                                + msg;

                                        scb.with(veb -> {
                                            veb.invoke("toString", ivb -> {

                                                ivb.withArgument(
                                                        variable("val")
                                                                .plus(variable("c").modulo(2).parenthesized())
                                                                .times(invocationOf("hashCode").on("msg")
                                                                        .xor(23))
                                                ).on("Integer");

//                                                ivb.withArgument(subV -> {
//                                                    subV.numeric().expression("val")
//                                                            .plus()
//                                                            .parenthesized()
//                                                            .numeric()
//                                                            .expression("c")
//                                                            .modulo(2)
//                                                            .endNumericExpression()
//                                                            .times()
//                                                            .invoke("hashCode")
//                                                            .on("msg")
//                                                            .xor(23)
//                                                            .endNumericExpression();
//                                                }).on("Integer");
                                            });
                                        }).appendExpression("msg");

//                                        scb.with(veb -> {
//                                            veb.invoke("toString", ivb -> {
//                                                ivb.withArgument(subV -> {
//                                                    subV.numeric().expression("val")
//                                                            .plus()
//                                                            .parenthesized()
//                                                            .numeric()
//                                                            .expression("c")
//                                                            .modulo(2)
//                                                            .endNumericExpression()
//                                                            .times()
//                                                            .invoke("hashCode")
//                                                            .on("msg")
//                                                            .xor(23)
//                                                            .endNumericExpression();
//                                                }).on("Integer");
//                                            });
//                                        }).appendExpression("msg");
                                    }).endBlock();
                                    sw.inDefaultCase().andThrow()
                                            .ofType("IllegalArgumentException");
                                });
                            });
                });
        c.accept(cb);
    }

    public static class SwitchIt implements ISwitch {

        @Override
        public String switchit(int val, char c, String msg) {
            if (val == 0) {
                if (c == 'e') {
                    return "ZERO-e-" + msg + "-" + (short) 5338 + val;
                }
            }
            switch (c) {
                case 'a':
                    int v = val * c;
                    return msg + "-" + v;
                case 'b':
                    if (val % 2 == 0) {
                        return msg + "-" + val;
                    } else {
                        return Character.toString(c);
                    }
                case 'c':
                    return Integer.toString(val + c);
                case 'd':
                    return "blork";
                case 'e':
                    return Integer.toString(val + (c % 2) * msg.hashCode() ^ 23)
                            + msg;
                default:
                    throw new IllegalArgumentException();
            }
        }

    }

    private Object lastProxied;

    @SuppressWarnings({"deprecation", "unchecked"})
    private <T> T loadAndCreateProxy(String className, Class<T> iface, Object... constructorArgs) throws ClassNotFoundException, InstantiationException, IllegalAccessException, NoSuchMethodException, IllegalArgumentException, InvocationTargetException {
        String realType = packageName + "." + className;
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(ldr);
            Class<?> type = ldr.loadClass(realType);
            Object instance;
            if (false && constructorArgs.length == 0) {
                instance = type.newInstance();
            } else {
                Class<?>[] types = new Class<?>[constructorArgs.length];
                for (int i = 0; i < constructorArgs.length; i++) {
                    types[i] = constructorArgs[i].getClass();
                }
                Constructor con = type.getConstructor(types);
                con.setAccessible(true);
                instance = con.newInstance(constructorArgs);
            }
            lastProxied = instance;
            return (T) Proxy.newProxyInstance(ldr, new Class<?>[]{iface}, new IH(instance));
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    static final class IH implements InvocationHandler {

        private final Object instance;

        private IH(Object instance) {
            this.instance = instance;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Class<?> c = instance.getClass();
            Method m = c.getMethod(method.getName(), method.getParameterTypes());
            m.setAccessible(true);

            Object result = m.invoke(instance, args);
            if (result == instance) {
                return proxy;
            }
            return result;
        }
    }
}
