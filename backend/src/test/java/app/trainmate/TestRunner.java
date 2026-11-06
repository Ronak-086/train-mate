package app.trainmate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Robust JUnit test runner for running tests directly without Maven Surefire plugin internet resolution.
 */
public final class TestRunner {
    public static void main(String[] args) {
        String[] classNames = {
            "app.trainmate.user.DisplayNamesTest",
            "app.trainmate.store.InMemoryKeyValueStoreTest",
            "app.trainmate.train.StatusMathTest",
            "app.trainmate.train.ResilienceTest",
            "app.trainmate.auth.FirebaseTokenVerifierTest",
            "app.trainmate.http.ProductionSafetyTest",
            "app.trainmate.train.RapidApiProviderAdapterTest",
            "app.trainmate.train.RailRadarProviderAdapterTest",
            "app.trainmate.auth.RestFirebaseAuthDeleterTest",
            "app.trainmate.post.PostServiceLogicTest"
        };

        int total = 0;
        int passed = 0;
        int skipped = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();

        System.out.println("=================================================");
        System.out.println("  TrainMate Backend Test Suite");
        System.out.println("=================================================");

        for (String cname : classNames) {
            try {
                Class<?> tc = Class.forName(cname);
                List<Method> beforeAlls = new ArrayList<>();
                List<Method> befores = new ArrayList<>();
                List<Method> tests = new ArrayList<>();

                for (Method m : tc.getDeclaredMethods()) {
                    if (m.isAnnotationPresent(BeforeAll.class)) beforeAlls.add(m);
                    if (m.isAnnotationPresent(BeforeEach.class)) befores.add(m);
                    if (m.isAnnotationPresent(Test.class)) tests.add(m);
                }

                // Run @BeforeAll
                for (Method ba : beforeAlls) {
                    ba.setAccessible(true);
                    try {
                        ba.invoke(null);
                    } catch (Throwable t) {
                        System.err.println("Failed @BeforeAll in " + cname + ": " + t);
                    }
                }

                for (Method testMethod : tests) {
                    total++;
                    String testName = tc.getSimpleName() + "." + testMethod.getName();
                    try {
                        Constructor<?> ctor = tc.getDeclaredConstructor();
                        ctor.setAccessible(true);
                        Object instance = ctor.newInstance();
                        for (Method b : befores) {
                            b.setAccessible(true);
                            b.invoke(instance);
                        }
                        testMethod.setAccessible(true);
                        testMethod.invoke(instance);
                        passed++;
                        System.out.println("  [PASS] " + testName);
                    } catch (Throwable t) {
                        Throwable cause = t.getCause() != null ? t.getCause() : t;
                        if (cause.getClass().getName().contains("Assumption") || cause.getClass().getName().contains("Aborted")) {
                            skipped++;
                            System.out.println("  [SKIP] " + testName + " (" + cause.getMessage() + ")");
                        } else {
                            failed++;
                            System.out.println("  [FAIL] " + testName + ": " + cause);
                            failures.add(testName + ": " + cause.getMessage());
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                System.err.println("Could not load test class: " + cname);
            }
        }

        System.out.println("=================================================");
        System.out.printf("Results: %d total, %d passed, %d skipped, %d failed%n", total, passed, skipped, failed);
        System.out.println("=================================================");

        if (failed > 0) {
            System.err.println("Failures:");
            for (String f : failures) System.err.println("  - " + f);
            System.exit(1);
        }
    }
}
