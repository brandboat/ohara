/*
 * Copyright 2019 is-land
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.island.ohara.common.util;

import com.island.ohara.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CommonUtils {
  private static final Logger logger = LoggerFactory.getLogger(CommonUtils.class);

  // ------------------------------------[Time Helper]------------------------------------ //

  public static String timezone() {
    return Calendar.getInstance().getTimeZone().getID();
  }

  /** An interface used to represent current time. */
  @FunctionalInterface
  public interface Timer {
    /** @return current time in ms. */
    long current();
  }

  /** Wrap to {@link System#currentTimeMillis} */
  private static final Timer DEFAULT_TIMER = System::currentTimeMillis;

  private static volatile Timer TIMER = DEFAULT_TIMER;

  public static void inject(Timer newOne) {
    TIMER = newOne;
  }

  public static void reset() {
    TIMER = DEFAULT_TIMER;
  }

  public static long current() {
    return TIMER.current();
  }

  /**
   * convert the string to java.Duration. Apart from java.Duration string, the simple format "1
   * second", "3 seconds" and "10 minutes" are totally supported.
   *
   * @param value duration string
   * @return java.time.Duration
   */
  public static Duration toDuration(String value) {
    try {
      return Duration.parse(value);
    } catch (Exception e) {
      // ok, it is not based on java.Duration. Let's try the scala.Duration based on
      // <number><unit>
      String stringValue = value.replaceAll(" ", "");
      int indexOfUnit = -1;
      for (int index = 0; index != stringValue.length(); ++index) {
        if (!Character.isDigit(stringValue.charAt(index))) {
          indexOfUnit = index;
          break;
        }
      }
      if (indexOfUnit == -1)
        throw new IllegalArgumentException(
            "the value:"
                + value
                + " can't be converted to either java.time.Duration or scala.concurrent.duration.Duration type");
      long number = Long.valueOf(stringValue.substring(0, indexOfUnit));
      String unitString = stringValue.substring(indexOfUnit).toUpperCase();
      // all units in TimeUnit end with "S". However, it forbids the representation like "1 second",
      // "1 minute" and "1 day".
      // Hence, we give user a hand to add "S" to reduce the failure of converting string to
      // Duration.
      if (!unitString.endsWith("S")) unitString += "S";
      TimeUnit unit = TimeUnit.valueOf(unitString);
      return Duration.ofMillis(unit.toMillis(number));
    }
  }

  // ------------------------------------[Process Helper]------------------------------------ //

  /**
   * helper method. Loop the specified method until timeout or get true from method
   *
   * @param f function the action
   * @param timeout duration timeout
   * @return false if timeout and (useException = true). Otherwise, the return value is true
   */
  public static Boolean await(Supplier<Boolean> f, Duration timeout) {
    return await(f, timeout, Duration.ofMillis(1500), true);
  }

  /**
   * helper method. Loop the specified method until timeout or get true from method
   *
   * @param f function
   * @param d duration
   * @param freq frequency to call the method
   * @param useException true make this method throw exception after timeout.
   * @return false if timeout and (useException = true). Otherwise, the return value is true
   */
  public static Boolean await(
      Supplier<Boolean> f, Duration d, Duration freq, Boolean useException) {
    long startTs = current();
    while (d.toMillis() >= (current() - startTs)) {
      if (f.get()) return true;
      else {
        try {
          TimeUnit.MILLISECONDS.sleep(freq.toMillis());
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }
    if (useException) {
      logger.error(
          "Running test method time is "
              + (current() - startTs)
              + " seconds more than the timeout time "
              + d.getSeconds()
              + " seconds. Please turning your timeout time.");
      throw new IllegalStateException("timeout");
    } else return false;
  }

  // ------------------------------------[Collection Helper]------------------------------------ //

  public static <E1> boolean equals(Set<E1> s1, Object o) {
    if (s1 == o) return true;
    if (!(o instanceof Set)) return false;
    Set<?> s2 = ((Set<?>) o);
    // check empty
    if (s1.isEmpty() && s2.isEmpty()) return true;

    if (s1.size() != s2.size()) return false;

    try {
      return s1.containsAll(s2);
    } catch (ClassCastException | NullPointerException var4) {
      return false;
    }
  }

  private static <K, V> boolean mapEquals(
      Map<K, V> m1, Map<?, ?> m2, BiPredicate<V, Object> condition) {

    try {
      for (Map.Entry<K, V> e : m1.entrySet()) {
        K key = e.getKey();
        V value = e.getValue();
        // value null
        if (value == null) {
          // not have key or value is null
          if (m2.get(key) == null && m2.containsKey(key)) continue;
          return false;
        } else {
          if (!condition.test(value, m2.get(key))) return false;
        }
      }
    } catch (ClassCastException | NullPointerException unused) {
      return false;
    }
    return true;
  }

  public static <K, V> boolean equals(Map<K, V> m1, Object o) {

    if (m1 == o) return true;
    if (!(o instanceof Map)) return false;
    Map<?, ?> m2 = ((Map<?, ?>) o);
    // check empty
    if (m1.isEmpty() && m2.isEmpty()) return true;
    if (m1.size() != m2.size()) {
      return false;
    }
    V valueHead = m1.entrySet().iterator().next().getValue();

    // nested
    if (valueHead instanceof List) {
      return mapEquals(m1, m2, (a, b) -> equals((List<?>) a, b));
    } else if (valueHead instanceof Set) {
      return mapEquals(m1, m2, (a, b) -> equals((Set<?>) a, b));
    } else if (valueHead instanceof Map) {
      return mapEquals(m1, m2, (a, b) -> equals((Map<?, ?>) a, b));
    } else {
      return mapEquals(m1, m2, Objects::equals);
    }
  }

  private static <E1> boolean listEquals(
      List<E1> l1, List<?> l2, BiPredicate<E1, Object> condition) {
    Iterator<E1> e1 = l1.listIterator();
    Iterator<?> e2 = l2.listIterator();
    while (e1.hasNext() && e2.hasNext()) {
      E1 o1 = e1.next();
      Object o2 = e2.next();
      if (!condition.test(o1, o2)) return false;
    }
    return (!e1.hasNext()) && (!e2.hasNext());
  }

  public static <E1> boolean equals(List<E1> l1, Object o) {
    if (l1 == o) return true;
    if (!(o instanceof List)) return false;
    List<?> l2 = ((List<?>) o);
    // check empty
    if (l1.isEmpty() && l2.isEmpty()) return true;

    // nested
    E1 head = l1.get(0);
    if (head instanceof List) {
      return listEquals(l1, l2, (a, b) -> equals((List<?>) a, b));
    } else if (head instanceof Set) {
      return listEquals(l1, l2, (a, b) -> equals((Set<?>) a, b));
    } else if (head instanceof Map) {
      return listEquals(l1, l2, (a, b) -> equals((Map<?, ?>) a, b));
    } else {
      return listEquals(l1, l2, Objects::equals);
    }
  }

  // ------------------------------------[Network Helper]------------------------------------ //

  /**
   * Determines the IP address of a host, given the host's name.
   *
   * @param hostname host's name
   * @return the IP address string in textual presentation.
   */
  public static String address(String hostname) {
    try {
      return InetAddress.getByName(hostname).getHostAddress();
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static String anyLocalAddress() {
    return "0.0.0.0";
  }

  public static int resolvePort(int port) {
    if (port <= 0) return availablePort();
    else return port;
  }

  public static int availablePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      int port = socket.getLocalPort();
      // This method is frequently used by tests that the port generated by this method is assigned
      // to remote node.
      // However, our QA run in root so the available port can be smaller than 1024. The protected
      // port can break
      // the cluster services since all our services are NOT in root mode.
      return port > 1024 ? port : port + 1024;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ---------------------------------[Primitive Type Helper]--------------------------------- //

  /**
   * create a uuid. This uuid consists of "number" and [a-f]
   *
   * @return uuid
   */
  public static String uuid() {
    return java.util.UUID.randomUUID().toString();
  }

  /**
   * a random string based on uuid without "-"
   *
   * @return random string
   */
  public static String randomString() {
    return uuid().replaceAll("-", "");
  }

  /**
   * create a random string with specified length. This uuid consists of "number" and [a-f]
   *
   * @param len the length of uuid
   * @return uuid
   */
  public static String randomString(int len) {
    String string = randomString();
    if (string.length() < len)
      throw new IllegalArgumentException(
          "expected size:" + len + ", actual size:" + string.length());
    return string.substring(0, len);
  }

  public static int randomInteger() {
    return new Random().nextInt();
  }

  /**
   * @param s string
   * @return true if s is null or empty. otherwise false
   */
  public static boolean isEmpty(String s) {
    return s == null || s.isEmpty();
  }

  /**
   * @param s a collection
   * @return true if collection is null or empty. otherwise false
   */
  public static boolean isEmpty(Collection<?> s) {
    return s == null || s.isEmpty();
  }

  /**
   * @param s a map
   * @return true if map is null or empty. otherwise false
   */
  public static boolean isEmpty(Map<?, ?> s) {
    return s == null || s.isEmpty();
  }

  /**
   * throw exception if the input string is either null or empty.
   *
   * @param s input string
   * @param msg error message
   * @throws NullPointerException if {@code s} is {@code null}
   * @throws IllegalArgumentException if {@code s} is empty
   * @return input string
   */
  public static String requireNonEmpty(String s, Supplier<String> msg) {
    if (Objects.requireNonNull(s).isEmpty()) throw new IllegalArgumentException(msg.get());
    return s;
  }

  /**
   * throw exception if the input collection is either null or empty.
   *
   * @param s input collection
   * @param msg error message
   * @param <T> collection type
   * @throws NullPointerException if {@code s} is {@code null}
   * @throws IllegalArgumentException if {@code s} is empty
   * @return input collection
   */
  public static <T extends Collection<?>> T requireNonEmpty(T s, Supplier<String> msg) {
    if (Objects.requireNonNull(s).isEmpty()) throw new IllegalArgumentException(msg.get());
    return s;
  }

  /**
   * throw exception if the input map is either null or empty.
   *
   * @param s input map
   * @param msg error message
   * @param <T> collection type
   * @throws NullPointerException if {@code s} is {@code null}
   * @throws IllegalArgumentException if {@code s} is empty
   * @return input map
   */
  public static <T extends Map<?, ?>> T requireNonEmpty(T s, Supplier<String> msg) {
    if (Objects.requireNonNull(s).isEmpty()) throw new IllegalArgumentException(msg.get());
    return s;
  }

  public static String requireNonEmpty(String s) {
    return requireNonEmpty(s, () -> "");
  }

  public static <T extends Collection<?>> T requireNonEmpty(T s) {
    return requireNonEmpty(s, () -> "");
  }

  public static <T extends Map<?, ?>> T requireNonEmpty(T s) {
    return requireNonEmpty(s, () -> "");
  }

  /**
   * check the port to which you prepare to connect. The port must be bigger than zero and small
   * than 65536. The zero is illegal since you can't raise a connection to a zero port.
   *
   * @param port port
   * @return legal port
   */
  public static boolean isConnectionPort(int port) {
    return port >= 1 && port <= 65535;
  }

  /**
   * check the port to which you prepare to connect. The port must be bigger than zero and small
   * than 65536. The zero is illegal since you can't raise a connection to a zero port.
   *
   * @param port port
   * @return legal port
   */
  public static int requireConnectionPort(int port) {
    if (!isConnectionPort(port))
      throw new IllegalArgumentException("the legal port range is [1, 65535], actual:" + port);
    return port;
  }

  /**
   * check the port to which you prepare to bind. The port must be bigger than or equal with zero
   * and small than 65536. The zero is legal since OS will assign a random port to you.
   *
   * @param value port number
   * @return legal port
   */
  public static int requireBindPort(int value) {
    if (value < 0 || value > 65535)
      throw new IllegalArgumentException("the legal port range is 0 - 65535, actual:" + value);
    return value;
  }

  /**
   * throw exception if the input value is not larger than zero.
   *
   * @param value be validated value
   * @return passed value
   */
  public static short requirePositiveShort(short value) {
    return (short) requirePositiveLong(value);
  }

  /**
   * throw exception if the input value is not larger than zero.
   *
   * @param value be validated value
   * @return passed value
   */
  public static int requirePositiveInt(int value) {
    return (int) requirePositiveLong(value);
  }

  /**
   * throw exception if the input value is not larger than zero.
   *
   * @param value be validated value
   * @return passed value
   */
  public static long requirePositiveLong(long value) {
    if (value <= 0)
      throw new IllegalArgumentException("the value:" + value + " must be bigger than zero");
    return value;
  }

  /**
   * throw exception if the input value is small than zero.
   *
   * @param value be validated value
   * @return passed value
   */
  public static short requireNonNegativeShort(short value) {
    return (short) requireNonNegativeLong(value);
  }

  /**
   * throw exception if the input value is small than zero.
   *
   * @param value be validated value
   * @return passed value
   */
  public static int requireNonNegativeInt(int value) {
    return (int) requireNonNegativeLong(value);
  }

  /**
   * throw exception if the input value is small than zero.
   *
   * @param value be validated value
   * @return passed value
   */
  public static long requireNonNegativeLong(long value) {
    if (value < 0)
      throw new IllegalArgumentException(
          "the value:" + value + " must be bigger than or equal with zero");
    return value;
  }

  /**
   * We should all love simple string, shouldn't we?
   *
   * @param s string
   * @return true if the string consist of number and char. Otherwise, false
   */
  public static boolean onlyNumberAndChar(String s) {
    return s.matches("^[a-zA-Z0-9]*$");
  }

  /**
   * a helper method to "filter" the legal string.
   *
   * @param s string
   * @return origin string
   */
  public static String requireNumberAndCharset(String s) {
    if (onlyNumberAndChar(s)) return s;
    else throw new IllegalArgumentException("Only number and char are accepted!!! actual:" + s);
  }

  /**
   * Check word has uppercase
   *
   * @param str to check string
   * @return checked string
   */
  public static boolean hasUpperCase(String str) {
    return !str.equals(str.toLowerCase());
  }

  /**
   * Require lowercase function
   *
   * @param str to check string
   * @param msg exception message
   * @return checked string
   */
  public static String requireLowerCase(String str, Supplier<String> msg) {
    if (hasUpperCase(str)) throw new IllegalArgumentException(msg.get());
    return str;
  }

  // ------------------------------------[File Helper]------------------------------------ //

  /**
   * @param file file
   * @return the extension of file name. Or throw exception if the input file does not have
   *     extension
   */
  public static String extension(File file) {
    return extension(file.getName());
  }

  /**
   * @param filename file name
   * @return the extension of file name. Or throw exception if the input file does not have
   *     extension
   */
  public static String extension(String filename) {
    CommonUtils.requireNonEmpty(filename);
    int index = filename.lastIndexOf(".");
    if (index == -1 || index + 1 == filename.length())
      throw new IllegalArgumentException(filename + " does not have extension");
    return filename.substring(index + 1);
  }

  /**
   * @param filename file name
   * @return true if the input name ends with ".xxx"
   */
  public static boolean hasExtension(String filename) {
    CommonUtils.requireNonEmpty(filename);
    int index = filename.lastIndexOf(".");
    return index != -1 && index + 1 != filename.length();
  }

  /**
   * replace the path's parent path by new parent
   *
   * @param parent new parent
   * @param path original path
   * @return new path
   */
  public static String replaceParent(String parent, String path) {
    return path(parent, name(path));
  }

  /**
   * compose a full path based on parent (folder) and other paths string (file or more paths).
   *
   * @param parent parent folder
   * @param name additional strings to be added in the path string
   * @return path
   */
  public static String path(String parent, String... name) {
    return Paths.get(parent, name).toString();
  }

  /**
   * extract the file name from the path
   *
   * @param path path
   * @return the file name, throw exception if this was a root path
   */
  public static String name(String path) {
    if (Paths.get(path).getNameCount() == 0)
      throw new IllegalArgumentException("no file name for " + path);
    else return Paths.get(path).getFileName().toString();
  }

  /**
   * create a temp file with specified prefix name and suffix name.
   *
   * @param prefix prefix name
   * @param suffix suffix name
   * @return a temp file
   */
  public static File createTempFile(String prefix, String suffix) {
    try {
      Path t = Files.createTempFile(prefix, suffix);
      return t.toFile();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * create a temp jar file with specified prefix name.
   *
   * @param prefix prefix name
   * @return a temp file
   */
  public static File createTempJar(String prefix) {
    return createTempFile(prefix, ".jar");
  }

  /**
   * create a temp folder with specified prefix name.
   *
   * @param prefix prefix name
   * @return a temp folder
   */
  public static File createTempFolder(String prefix) {
    try {
      Path t = Files.createTempDirectory(prefix);
      return t.toFile();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Check the null and existence of input file
   *
   * @param file file
   * @return an non-null and existent file
   */
  public static File requireExist(File file) {
    if (!Objects.requireNonNull(file).exists())
      throw new IllegalArgumentException(file.getAbsolutePath() + " does not exist");
    return file;
  }

  /**
   * @param file input file
   * @throws IllegalArgumentException If the input is not file
   * @return input file
   */
  public static File requireFile(File file) {
    if (!requireExist(file).isFile())
      throw new IllegalArgumentException(file.getAbsolutePath() + " is not file");
    return file;
  }

  /**
   * @param file input file
   * @throws IllegalArgumentException If the input is not folder
   * @return input file
   */
  public static File requireFolder(File file) {
    if (!requireExist(file).isDirectory())
      throw new IllegalArgumentException(file.getAbsolutePath() + " is not folder");
    return file;
  }

  /**
   * Check the null and non-existence of input file
   *
   * @param file file
   * @return an non-null and existent file
   */
  public static File requireNotExist(File file) {
    if (Objects.requireNonNull(file).exists())
      throw new IllegalArgumentException(file.getAbsolutePath() + " exists");
    return file;
  }

  /**
   * Delete the file or folder
   *
   * @param path path to file or folder
   */
  public static void deleteFiles(File path) {
    try {
      FileUtils.forceDelete(path);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * copy total content of a file to an new file. The input file must be a existent file. And target
   * file must be not existent.
   *
   * @param file src file
   * @param newFile target file
   */
  public static void copyFile(File file, File newFile) {
    try {
      FileUtils.copyFile(requireFile(file), requireNotExist(newFile));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * move a file to another location. The input file must be a existent file. And target file must
   * be not existent.
   *
   * @param file src file
   * @param newFile target file
   */
  public static void moveFile(File file, File newFile) {
    try {
      FileUtils.moveFile(requireFile(file), requireNotExist(newFile));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Download the content from remote URL to local file. The local file must be not existent.
   *
   * @param source remote resource
   * @param destination local file
   * @param connectionTimeout connection timeout
   * @param readTimeout read timeout
   */
  public static void copyURLToFile(
      URL source, File destination, int connectionTimeout, int readTimeout) {
    try {
      FileUtils.copyURLToFile(
          Objects.requireNonNull(source),
          requireNotExist(destination),
          connectionTimeout,
          readTimeout);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * download file from url
   *
   * @param url where the file is download from
   * @param connectionTimeout the connection timeout
   * @param readTimeout the read timeout
   * @return the downloaded file
   */
  public static File downloadUrl(URL url, Duration connectionTimeout, Duration readTimeout) {

    File tmpFolder = CommonUtils.createTempFolder("tmp-");
    File outputFile = new File(tmpFolder, randomString(10));

    int ct = Math.toIntExact(connectionTimeout.toMillis());
    int rt = Math.toIntExact(readTimeout.toMillis());

    copyURLToFile(url, outputFile, ct, rt);
    return outputFile;
  }

  /** this is a specific string used to replace the quota in the env. */
  @VisibleForTesting static String INTERNAL_STRING_FOR_ENV = "\\\\\"";

  /**
   * remove the unsupported charset - quote - and replace it by slash
   *
   * @param string string
   * @return a string is accepted by env
   */
  public static String toEnvString(String string) {
    if (string.contains(INTERNAL_STRING_FOR_ENV))
      throw new IllegalArgumentException(
          string
              + " has internal string:"
              + INTERNAL_STRING_FOR_ENV
              + " so we can't convert it to env string");
    return string.replaceAll("\"", INTERNAL_STRING_FOR_ENV);
  }

  /**
   * replace the cryptic and internal charset from the env string.
   *
   * @param string string from env
   * @return a absolutely normal string
   */
  public static String fromEnvString(String string) {
    return string.replaceAll(INTERNAL_STRING_FOR_ENV, "\"");
  }

  /** disable to instantiate CommonUtils. */
  private CommonUtils() {}
}
