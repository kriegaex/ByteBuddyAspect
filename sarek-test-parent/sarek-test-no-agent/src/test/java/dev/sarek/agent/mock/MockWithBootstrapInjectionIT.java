package dev.sarek.agent.mock;

import dev.sarek.junit4.SarekRunner;
import dev.sarek.test.util.SeparateJVM;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import javax.swing.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Year;
import java.util.Random;
import java.util.UUID;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.junit.Assert.*;

@SuppressWarnings(
  { "ConstantConditions", "StringBufferReplaceableByString", "StringBufferMayBeStringBuilder", "unused" }
)
@Category(SeparateJVM.class)
@RunWith(SarekRunner.class)
public class MockWithBootstrapInjectionIT {
  @Test
  public void canMockBootstrapClass_UUID() {
    // Try with resources works for Mock because it implements AutoCloseable
    try (
      MockFactory<UUID> mockFactory = MockFactory
        .forClass(UUID.class)
        .mockStaticMethods(true)  // include static methods, too
        .mockConstructors()
        .addGlobalInstance()
        .build()
    )
    {
      assertNull(new UUID(0xABBA, 0xCAFE).toString());
      assertNull(UUID.randomUUID());
    }

    // After auto-close, class transformations have been reverted
    assertTrue(new UUID(0xABBA, 0xCAFE).toString().contains("abba"));
    assertTrue(UUID.randomUUID().toString().matches("\\p{XDigit}+(-\\p{XDigit}+){4}"));
  }

  @Test
  public void canMockBootstrapClass_Year() {
    // Try with resources works for Mock because it implements AutoCloseable
    try (
      MockFactory<Year> mockFactory = MockFactory
        .forClass(Year.class)
        .spy()
        .addGlobalInstance()
        .mockStatic(
          named("now"),
          (method, args) -> false,
          (method, args, proceedMode, returnValue, throwable) -> Year.of(1971)
        )
        .mock(
          named("isLeap"),
          (target, method, args) -> false,
          (target, method, args, proceedMode, returnValue, throwable) -> false
        )
        .build()
    )
    {
      assertFalse(Year.of(1971).isLeap());
      assertFalse(Year.of(1972).isLeap());
      assertFalse(Year.of(1973).isLeap());
      assertFalse(Year.of(1974).isLeap());
      assertEquals(1971, Year.now().getValue());
    }
  }

  /**
   * Do not mock File and FileInputStream at the same time because it causes ByteBuddy/ASM exceptions during
   * FileInputStream transformation:
   * <p>
   * <pre>{@code [Byte Buddy] ERROR java.io.FileInputStream [null, module java.base, loaded=true]
   * java.lang.IllegalStateException: Could not locate class file for dev.sarek.agent.aspect.HashCodeAspect
   * 	at net.bytebuddy.dynamic.ClassFileLocator$Resolution$Illegal.resolve(ClassFileLocator.java:118)
   * 	at net.bytebuddy.asm.Advice.to(Advice.java:351)}</pre>
   * <p>
   * If we mock them separately, we do not hit this problem, but this test might still fail in the future because
   * file-related classes like the ones under test are being used by ByteBuddy in order to do class file location.
   * Keep the test as a show case for more difficult situations and to document known edge cases.
   *
   * @throws IOException
   */
  @Test
  public void canMockBootstrapClass_FileInputStream() throws IOException {
    // Try with resources works for Mock because it implements AutoCloseable
    try (
      MockFactory<File> mockFactory1 = MockFactory.forClass(File.class).mockConstructors().addGlobalInstance().build()
    )
    {
      File file = new File("CTeWTxRxRTmdf8JtvzmC");
      // Check that HashCodeAspect was applied
      assertEquals(System.identityHashCode(file), file.hashCode());
      assertNull(file.getName());
    }

    // Try with resources works for Mock because it implements AutoCloseable
    try (
      MockFactory<FileInputStream> mockFactory2 = MockFactory.forClass(FileInputStream.class).mockConstructors().addGlobalInstance().build()
    )
    {
      File file = new File("CTeWTxRxRTmdf8JtvzmC");
      FileInputStream fileInputStream = new FileInputStream(file);
      assertEquals(0, fileInputStream.read());
      assertNull(fileInputStream.getFD());
    }

    // After auto-close, class transformations have been reverted
    File file = new File("CTeWTxRxRTmdf8JtvzmC");
    assertNotEquals(System.identityHashCode(file), file.hashCode());
    assertNotNull(file.getName());
    assertThrows(FileNotFoundException.class, () -> new FileInputStream(file));
  }

  @Test
  public void canMockBootstrapClass_StringBuffer() {
    // Try with resources works for Mock because it implements AutoCloseable
    try (
      MockFactory<StringBuffer> mockFactory1 = MockFactory
        .forClass(StringBuffer.class)
        .mockConstructors()
        // Exclude super type which leads to JVM errors triggered by ByteBuddy:
        //   [Byte Buddy] REDEFINE COMPLETE *** java.lang.instrument ASSERTION FAILED ***:
        //     "!errorOutstanding" with message transform method call failed at
        //     ./open/src/java.instrument/share/native/libinstrument/JPLISAgent.c line: 873
        //   [Byte Buddy] ERROR
        //   Exception: java.lang.StackOverflowError thrown from the UncaughtExceptionHandler in thread "main"
        //
        // Note: We use a class name string here because AbstractStringBuilder is package-scoped and we cannot directly
        // refer to it via AbstractStringBuilder.class
        .excludeSuperTypes(named("java.lang.AbstractStringBuilder"))
        .build()
    )
    {
      StringBuffer stringBuffer = new StringBuffer("dummy");
      mockFactory1.addTarget(stringBuffer);
      stringBuffer.append(42);
      stringBuffer.append("foo");
      assertNull(stringBuffer.toString());
    }

    // Do not try mocking StringBuilder here because that only works if all libraries are loaded from the bootstrap
    // class loader. (See 'MockIT' in 'sarek-test-use-agent' module for how to do that.) Otherwise the result would be
    // LinkageErrors caused by the same test framework classes loaded by both the application and the bootstrap class
    // loaders, making them incompatible:
    //   LinkageError: loader constraint violation: when resolving method
    //   'MockFactory$Builder MockFactory$Builder.addAdvice(...)'
    //   the class loader 'app' of the current class, MockWithBootstrapInjectionTest,
    //   and the class loader 'bootstrap' for the method's defining class, MockFactory$Builder,
    //   have different Class objects for the type AroundAdvice (...)
  }

  /**
   * Do not mock URL and URI at the same time because they are used inside ByteBuddy in order to locate class files,
   * which leads to strange exceptions thrown by ByteBuddy's class file locator such as:
   * <p>
   * {@code IllegalStateException: Could not locate class file for dev.sarek.agent.aspect.HashCodeAspect}
   * <p>
   * If we mock them separately, we do not hit this problem, but this test might still fail in the future. Keep it as
   * a show case for more difficult situations and to document known edge cases.
   *
   * @throws IOException
   * @throws URISyntaxException
   */
  // TODO: There is a chance that this problem does not occur when running in Java agent mode and the mock classes are
  //       also injected into the bootstrap class loader. Check if this is true.
  @Test
  public void canMockBootstrapClass_URL() throws IOException, URISyntaxException {
    // Try with resources works for Mock because it implements AutoCloseable
    try (
      MockFactory<URL> mockFactory = MockFactory.forClass(URL.class).mockConstructors().addGlobalInstance().build();
    )
    {
      URL url = new URL("invalid URL, no problem");
      assertNull(url.getHost());
      assertNull(url.getContent());
    }

    // Try with resources works for Mock because it implements AutoCloseable
    try (
      MockFactory<URI> mockFactory = MockFactory.forClass(URI.class).addGlobalInstance().mockConstructors().build()
    )
    {
      URI uri = new URI("invalid URI, no problem");
      assertNull(uri.getHost());
      assertNull(uri.getQuery());
    }
  }

  @Test
  public void canMockBootstrapClass_Random() {
    // Try with resources works for Mock because it implements AutoCloseable
    try (
      MockFactory<Random> mockFactory = MockFactory.forClass(Random.class).mockConstructors().addGlobalInstance().build()
    )
    {
      Random random = new Random();
      assertEquals(0, random.nextInt());
      assertEquals(0, random.nextDouble(), 1e-6);
    }
  }

  @Test
  public void canMockBootstrapClasses_Swing() {
    // Try with resources works for Mock because it implements AutoCloseable
    try (
      MockFactory<JTable> mockFactory1 = MockFactory.forClass(JTable.class).mockConstructors().addGlobalInstance().build();
      MockFactory<GroupLayout> mockFactory2 = MockFactory.forClass(GroupLayout.class).mockConstructors().addGlobalInstance().build();
      MockFactory<JTextField> mockFactory3 = MockFactory.forClass(JTextField.class).mockConstructors().addGlobalInstance().build()
    )
    {
      JTable jTable = new JTable(3, 3);
      assertEquals(0, jTable.getRowCount());
      assertEquals(0, jTable.getColumnCount());
      assertNull(new GroupLayout(null).getLayoutStyle());
      assertNull(new JTextField().getSelectedTextColor());
    }
  }

  @Test
  public void createInstance() {
    // Try with resources works for Mock because it implements AutoCloseable
    try (
      MockFactory<UUID> mockFactory = MockFactory
        .forClass(UUID.class)
        .mockStaticMethods(true)  // include static methods, too
        .build()
    )
    {
      // Static method is mocked
      assertNull(UUID.randomUUID());

      // Create mock and automatically register it as an active target
      assertNull(mockFactory.createInstance().toString());

      // Create mock and manually (de-)register it as an active target
      UUID uuid = mockFactory.createInstance(false);
      assertNotNull(uuid.toString());
      mockFactory.addTarget(uuid);
      assertNull(uuid.toString());
      mockFactory.removeTarget(uuid);
      assertNotNull(uuid.toString());
    }

    // After auto-close, class transformations have been reverted
    assertTrue(new UUID(0xABBA, 0xCAFE).toString().contains("abba"));
    assertTrue(UUID.randomUUID().toString().matches("\\p{XDigit}+(-\\p{XDigit}+){4}"));
  }

}
