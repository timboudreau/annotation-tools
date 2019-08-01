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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.lang.model.element.Modifier;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PUBLIC;
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
        IAnnotationsAndLambdas proxy = loadAndCreateProxy("AnnotationsAndLambdas", IAnnotationsAndLambdas.class);
        assertNotNull(proxy);
        assertNotNull(lastProxied);
        Annotation[] annos = lastProxied.getClass().getAnnotations();
        assertNotEquals(0, annos.length, "No annotations found");
        for (Annotation a : annos) {
            System.out.println("A: " + a);
        }
        assertEquals(1, annos.length);
        String annoString = annos[0].toString();
        assertEquals("@com.mastfrog.java.vogon.test.SomeAnnotation(value=\"Hoober\", thing=56)", annoString);
        assertEquals(0, proxy.lastValue());

        assertTrue(proxy.time() <= System.currentTimeMillis());
        assertTrue(proxy.time2() <= System.currentTimeMillis());

        proxy.doSomething(val -> {
            assertTrue(val instanceof Integer);
            assertEquals(-3036, val);
        });
        Consumer<Integer> c = proxy.getDoerOfSomething((short) 7397);
        assertEquals(0, proxy.lastValue());
        c.accept(100);
        assertEquals(-976404, proxy.lastValue());
        Object[] stuff = proxy.getStuff();
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
    }

    @BeforeAll
    public static void setup() throws Exception {
        dir = FileUtils.newTempDir();
        pkgDir = dir.resolve(packageName.replace('.', '/'));
        Files.createDirectories(pkgDir);
        Set<Path> javaSources = new HashSet<>();
        JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager mgr = compiler.getStandardFileManager(new DL(), Locale.US, UTF_8);
        mgr.setLocationFromPaths(StandardLocation.SOURCE_PATH, Collections.singleton(dir));
        mgr.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, Collections.singleton(dir));
        Set<JavaFileObject> fos = new HashSet<>();
        generateClasses(cb -> {
            Path file = pkgDir.resolve(cb.className() + ".java");
            String body = cb.build();
            Files.write(file, body.getBytes(UTF_8));
            javaSources.add(file);
            JavaFileObject fo = mgr.getJavaFileForInput(StandardLocation.SOURCE_PATH, cb.fqn(), Kind.SOURCE);
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

    private static void generateClasses(ThrowingConsumer<ClassBuilder<String>> c) throws Exception {
        generateSimpleBean(c);
        generateAnnotation(c);
        generateAnnotationsAndLambdas(c);
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
            System.out.println("created instance " + instance + " of type " + instance.getClass().getName());
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
