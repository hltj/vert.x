/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.file.impl;

import io.netty.util.internal.PlatformDependent;
import io.vertx.core.VertxException;
import io.vertx.core.file.FileSystemOptions;
import io.vertx.core.impl.Utils;

import java.io.*;
import java.net.URL;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Enumeration;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static io.vertx.core.net.impl.URIDecoder.*;

/**
 * Sometimes the file resources of an application are bundled into jars, or are somewhere on the classpath but not
 * available on the file system, e.g. in the case of a Vert.x webapp bundled as a fat jar.
 *
 * In this case we want the application to access the resource from the classpath as if it was on the file system.
 *
 * We can do this by looking for the file on the classpath, and if found, copying it to a temporary cache directory
 * on disk and serving it from there.
 *
 * There is one cache dir per Vert.x instance and they are deleted on Vert.x shutdown.
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 * @author <a href="https://github.com/rworsnop/">Rob Worsnop</a>
 */
public class FileResolver {

  /**
   * Predicate for checking validity of cache path.
   */
  private static final IntPredicate CACHE_PATH_CHECKER;

  static {
    if (PlatformDependent.isWindows()) {
      CACHE_PATH_CHECKER = c -> {
        if (c < 33) {
          return false;
        } else {
          switch (c) {
            case 34:
            case 42:
            case 58:
            case 60:
            case 62:
            case 63:
            case 124:
              return false;
            default:
              return true;
          }
        }
      };
    } else {
      CACHE_PATH_CHECKER = c -> c != '\u0000';
    }
  }

  public static final String DISABLE_FILE_CACHING_PROP_NAME = "vertx.disableFileCaching";
  public static final String DISABLE_CP_RESOLVING_PROP_NAME = "vertx.disableFileCPResolving";
  public static final String CACHE_DIR_BASE_PROP_NAME = "vertx.cacheDirBase";
  private static final String FILE_SEP = System.getProperty("file.separator");
  private static final boolean NON_UNIX_FILE_SEP = !FILE_SEP.equals("/");
  private static final String JAR_URL_SEP = "!/";
  private static final Pattern JAR_URL_SEP_PATTERN = Pattern.compile(JAR_URL_SEP);

  private final File cwd;
  private final boolean enableCpResolving;
  private final boolean enableCaching;
  // mutable state
  private File cacheDir;
  private Thread shutdownHook;

  public FileResolver() {
    this(new FileSystemOptions());
  }

  public FileResolver(FileSystemOptions fileSystemOptions) {
    this.enableCaching = fileSystemOptions.isFileCachingEnabled();
    this.enableCpResolving = fileSystemOptions.isClassPathResolvingEnabled();

    String cwdOverride = System.getProperty("vertx.cwd");
    if (cwdOverride != null) {
      cwd = new File(cwdOverride).getAbsoluteFile();
    } else {
      cwd = null;
    }
    cacheDir = setupCacheDir(fileSystemOptions.getFileCacheDir());
  }

  /**
   * Close this file resolver, this is a blocking operation.
   */
  public void close() throws IOException {
    final Thread hook;
    synchronized (this) {
      hook = shutdownHook;
      // disable the shutdown hook thread
      shutdownHook = null;
    }
    if (hook != null) {
      // May throw IllegalStateException if called from other shutdown hook so ignore that
      try {
        Runtime.getRuntime().removeShutdownHook(hook);
      } catch (IllegalStateException ignore) {
        // ignore
      }
    }
    deleteCacheDir();
  }

  public File resolveFile(String fileName) {
    // First look for file with that name on disk
    File file = new File(fileName);
    if (cwd != null && !file.isAbsolute()) {
      file = new File(cwd, fileName);
    }
    if (!this.enableCpResolving) {
      return file;
    }
    // We need to synchronized here to avoid 2 different threads to copy the file to the cache directory and so
    // corrupting the content.
    synchronized (this) {
      if (!file.exists()) {
        // if cacheDir is null, the delete cache dir was already called.
        // only in this case the resolver is working in an unexpected state
        if (cacheDir == null) {
          throw new IllegalStateException("cacheDir is null");
        }
        // Look for it in local file cache
        File cacheFile = new File(cacheDir, fileName);
        if (this.enableCaching && cacheFile.exists()) {
          return cacheFile;
        }
        // Look for file on classpath
        ClassLoader cl = getClassLoader();
        if (NON_UNIX_FILE_SEP) {
          fileName = fileName.replace(FILE_SEP, "/");
        }

        //https://github.com/eclipse/vert.x/issues/2126
        //Cache all elements in the parent directory if it exists
        //this is so that listing the directory after an individual file has
        //been read works.
        String parentFileName = file.getParent();
        if (parentFileName != null) {
          URL directoryContents = getValidClassLoaderResource(cl, parentFileName);
          if (directoryContents != null) {
            unpackUrlResource(directoryContents, parentFileName, cl, true);
          }
        }

        URL url = getValidClassLoaderResource(cl, fileName);
        if (url != null) {
          return unpackUrlResource(url, fileName, cl, false);
        }
      }
    }
    return file;
  }

  private static boolean isValidCachePath(String fileName) {
    int len = fileName.length();
    for (int i = 0;i < len;i++) {
      char c = fileName.charAt(i);
      if (!CACHE_PATH_CHECKER.test(c)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Get a class loader resource that can unpack to a valid cache path.
   *
   * Some valid entries are avoided purposely when we cannot create the corresponding file in the file cache.
   */
  private static URL getValidClassLoaderResource(ClassLoader cl, String fileName) {
    URL resource = cl.getResource(fileName);
    if (resource != null && !isValidCachePath(fileName)) {
      return null;
    }
    return resource;
  }

  private File unpackUrlResource(URL url, String fileName, ClassLoader cl, boolean isDir) {
    String prot = url.getProtocol();
    switch (prot) {
      case "file":
        return unpackFromFileURL(url, fileName, cl);
      case "jar":
        return unpackFromJarURL(url, fileName, cl);
      case "bundle": // Apache Felix, Knopflerfish
      case "bundleentry": // Equinox
      case "bundleresource": // Equinox
      case "resource":  // substratevm (graal native image)
        return unpackFromBundleURL(url, isDir);
      default:
        throw new IllegalStateException("Invalid url protocol: " + prot);
    }
  }


  private synchronized File unpackFromFileURL(URL url, String fileName, ClassLoader cl) {
    final File resource = new File(decodeURIComponent(url.getPath(), false));
    boolean isDirectory = resource.isDirectory();
    File cacheFile = new File(cacheDir, fileName);
    if (!isDirectory) {
      cacheFile.getParentFile().mkdirs();
      try {
        if (this.enableCaching) {
          Files.copy(resource.toPath(), cacheFile.toPath());
        } else {
          Files.copy(resource.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (FileAlreadyExistsException ignore) {
      } catch (IOException e) {
        throw new VertxException(e);
      }
    } else {
      cacheFile.mkdirs();
      String[] listing = resource.list();
      if (listing != null) {
        for (String file: listing) {
          String subResource = fileName + "/" + file;
          URL url2 = getValidClassLoaderResource(cl, subResource);
          if (url2 == null) {
            throw new VertxException("Invalid resource: " + subResource);
          }
          unpackFromFileURL(url2, subResource, cl);
        }
      }
    }
    return cacheFile;
  }

  private synchronized File unpackFromJarURL(URL url, String fileName, ClassLoader cl) {
    ZipFile zip = null;
    try {
      String path = url.getPath();
      int idx1 = path.lastIndexOf(".jar!");
      if (idx1 == -1) {
        idx1 = path.lastIndexOf(".zip!");
      }
      int idx2 = path.lastIndexOf(".jar!", idx1 - 1);
      if (idx2 == -1) {
        idx2 = path.lastIndexOf(".zip!", idx1 - 1);
      }
      if (idx2 == -1) {
        File file = new File(decodeURIComponent(path.substring(5, idx1 + 4), false));
        zip = new ZipFile(file);
      } else {
        String s = path.substring(idx2 + 6, idx1 + 4);
        File file = resolveFile(s);
        zip = new ZipFile(file);
      }

      String inJarPath = path.substring(idx1 + 6);
      String[] parts = JAR_URL_SEP_PATTERN.split(inJarPath);
      StringBuilder prefixBuilder = new StringBuilder();
      for (int i = 0; i < parts.length - 1; i++) {
        prefixBuilder.append(parts[i]).append("/");
      }
      String prefix = prefixBuilder.toString();

      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        if (name.startsWith(prefix.isEmpty() ? fileName : prefix + fileName)) {
          File file = new File(cacheDir, prefix.isEmpty() ? name : name.substring(prefix.length()));
          if (name.endsWith("/")) {
            // Directory
            file.mkdirs();
          } else {
            file.getParentFile().mkdirs();
            try (InputStream is = zip.getInputStream(entry)) {
              if (this.enableCaching) {
                Files.copy(is, file.toPath());
              } else {
                Files.copy(is, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
              }
            } catch (FileAlreadyExistsException ignore) {
            }
          }
        }
      }
    } catch (IOException e) {
      throw new VertxException(e);
    } finally {
      closeQuietly(zip);
    }

    return new File(cacheDir, fileName);
  }

  private void closeQuietly(Closeable zip) {
    if (zip != null) {
      try {
        zip.close();
      } catch (IOException e) {
        // Ignored.
      }
    }
  }

  /**
   * It is possible to determine if a resource from a bundle is a directory based on whether or not the ClassLoader
   * returns null for a path (which does not already contain a trailing '/') *and* that path with an added trailing '/'
   *
   * @param url      the url
   * @return if the bundle resource represented by the bundle URL is a directory
   */
  private boolean isBundleUrlDirectory(URL url) {
    return url.toExternalForm().endsWith("/") ||
      getValidClassLoaderResource(getClassLoader(), url.getPath().substring(1) + "/") != null;
  }

  /**
   * bundle:// urls are used by OSGi implementations to refer to a file contained in a bundle, or in a fragment. There
   * is not much we can do to get the file from it, except reading it from the url. This method copies the files by
   * reading it from the url.
   *
   * @param url      the url
   * @return the extracted file
   */
  private synchronized File unpackFromBundleURL(URL url, boolean isDir) {
    try {
      File file = new File(cacheDir, url.getHost() + File.separator + url.getFile());
      file.getParentFile().mkdirs();
      if ((getClassLoader() != null && isBundleUrlDirectory(url))  || isDir) {
        // Directory
        file.mkdirs();
      } else {
        file.getParentFile().mkdirs();
        try (InputStream is = url.openStream()) {
          if (this.enableCaching) {
            Files.copy(is, file.toPath());
          } else {
            Files.copy(is, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
          }
        } catch (FileAlreadyExistsException ignore) {
        }
      }
    } catch (IOException e) {
      throw new VertxException(e);
    }
    return new File(cacheDir, url.getHost() + File.separator + url.getFile());
  }


  private ClassLoader getClassLoader() {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) {
      cl = getClass().getClassLoader();
    }
    // when running on substratevm (graal) the access to class loaders
    // is very limited and might be only available from compile time
    // known classes. (Object is always known, so we do a final attempt
    // to get it here).
    if (cl == null) {
      cl = Object.class.getClassLoader();
    }
    return cl;
  }

  /**
   * Will prepare the cache directory to be used in the application or return null if classpath resolving is disabled.
   */
  private File setupCacheDir(String fileCacheDir) {
    if (!this.enableCpResolving) {
      return null;
    }

    // ensure that the argument doesn't end with separator
    if (fileCacheDir.endsWith(File.separator)) {
      fileCacheDir = fileCacheDir.substring(0, fileCacheDir.length() - File.separator.length());
    }

    // the cacheDir will be suffixed a unique id to avoid eavesdropping from other processes/users
    // also this ensures that if process A deletes cacheDir, it won't affect process B
    String cacheDirName = fileCacheDir + "-" + UUID.randomUUID().toString();
    File cacheDir = new File(cacheDirName);
    // Create the cache directory
    try {
      if (Utils.isWindows()) {
        Files.createDirectories(cacheDir.toPath());
      } else {
        // for security reasons, cache directory should not be readable/writable from other users
        // just like "POSIX mkdtemp(3)", the created directory should have 0700 permission
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------");
        Files.createDirectories(cacheDir.toPath(), PosixFilePermissions.asFileAttribute(perms));
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to create cache dir: " + cacheDirName, e);
    }
    // Add shutdown hook to delete on exit
    shutdownHook = new Thread(() -> {
      synchronized (this) {
        // no-op if cache dir has been set to null
        if (this.cacheDir == null) {
          return;
        }
      }

      final Thread deleteCacheDirThread = new Thread(() -> {
        try {
          deleteCacheDir();
        } catch (IOException ignore) {
        }
      });
      // start the thread
      deleteCacheDirThread.start();
      try {
        deleteCacheDirThread.join(10 * 1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    Runtime.getRuntime().addShutdownHook(shutdownHook);
    return cacheDir;
  }

  private void deleteCacheDir() throws IOException {
    final File dir;
    synchronized (this) {
      if (cacheDir == null) {
        return;
      }
      // save the state before we force a flip
      dir = cacheDir;
      // disable the cache dir
      cacheDir = null;
    }
    // threads will only enter here once, as the resolving flag is flipped above
    if (dir.exists()) {
      FileSystemImpl.delete(dir.toPath(), true);
    }
  }
}
