package dev.javacosmofy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A small ZIP32 APE bundler. The resulting program needs no host zip tool. */
public final class Main {
  private static final String META = ".__javacosmofy__/base.properties";
  private static final String VERSION = "0.1.0";

  public static void main(String[] argv) {
    try {
      if (argv.length == 1 && (argv[0].equals("--version") || argv[0].equals("-V"))) {
        System.out.println("javacosmofy " + VERSION + " (Java " + System.getProperty("java.version") + ")");
        return;
      }

      if (argv.length == 0 || argv[0].equals("--help") || argv[0].equals("-h")) {
        usage();
        return;
      }

      if (!argv[0].equals("bundle")) {
        throw new IllegalArgumentException("unknown command: " + argv[0]);
      }

      bundle(parse(argv));
    } catch (Exception e) {
      System.err.println("javacosmofy: " + e.getMessage());
      System.exit(1);
    }
  }

  private static void usage() {
    System.out.println("""
        javacosmofy - package Java applications into a Cosmopolitan Java APE

        Usage:
          javacosmofy bundle CLASSES_DIR --main CLASS [options]
          javacosmofy bundle APP.jar --jar [--classpath LIB_DIR --main CLASS] [options]

        Options:
          -o, --output FILE    output APE (default: ./app.com)
          --main CLASS         fully-qualified Java main class (required)
          --jar                package the input executable JAR
          --classpath LIB_DIR  embed dependency JARs/resources beside APP.jar
          --modules LIST      append modules from the module repository (comma-separated, or all)
          --module-repository FILE  matching java-modules.zip (default: ../modules/jdk25-cosmo.zip)
          --runtime FILE       plain java.com, or a prior javacosmofy.com
          -h, --help           show this help

        A classpath JAR starts with -cp and requires --main; otherwise -jar is used.
        The ... marker forwards the caller's arguments to the application.
        """);
  }

  private record Options(Path source, Path output, Path runtime, String mainClass, Path classpath,
                         Path moduleRepository, Set<String> modules, boolean jar) {}

  private static Options parse(String[] argv) {
    Path source = null, output = Path.of("app.com"), runtime = null, classpath = null, repository = null;
    String main = null;
    boolean jar = false;
    Set<String> modules = new LinkedHashSet<>();

    for (int i = 1; i < argv.length; i++) {
      String a = argv[i];
      switch (a) {
        case "-o", "--output" -> output = Path.of(value(argv, ++i, a));
        case "--runtime" -> runtime = Path.of(value(argv, ++i, a));
        case "--main" -> main = value(argv, ++i, a);
        case "--classpath" -> classpath = Path.of(value(argv, ++i, a));
        case "--module-repository" -> repository = Path.of(value(argv, ++i, a));
        case "--modules" -> modules.addAll(moduleNames(value(argv, ++i, a)));
        case "--jar" -> jar = true;
        default -> {
          if (a.startsWith("-")) {
            throw new IllegalArgumentException("unknown option: " + a);
          }

          if (source != null) {
            throw new IllegalArgumentException("only one application input is accepted");
          }
          source = Path.of(a);
        }
      }
    }

    if (source == null) {
      throw new IllegalArgumentException("an application input is required");
    }

    if (jar) {
      if (!Files.isRegularFile(source)) {
        throw new IllegalArgumentException("APP.jar must be a regular file");
      }

      if (classpath != null && !Files.isDirectory(classpath)) {
        throw new IllegalArgumentException("--classpath must be a directory");
      }

      if (main != null && !main.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
        throw new IllegalArgumentException("invalid --main CLASS");
      }
    } else {
      if (classpath != null) {
        throw new IllegalArgumentException("--classpath requires --jar");
      }

      if (!Files.isDirectory(source)) {
        throw new IllegalArgumentException("CLASSES_DIR must be a directory");
      }

      if (main == null || !main.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
        throw new IllegalArgumentException("--main CLASS is required unless --jar is used");
      }
    }

    return new Options(source.toAbsolutePath().normalize(), output.toAbsolutePath().normalize(), runtime, main,
        classpath == null ? null : classpath.toAbsolutePath().normalize(),
        repository == null ? null : repository.toAbsolutePath().normalize(), modules, jar);
  }

  private static Set<String> moduleNames(String value) {
    Set<String> result = new LinkedHashSet<>();
    for (String name : value.split(",")) {
      if (!name.equals("all") && !name.matches("[A-Za-z0-9_.]+")) {
        throw new IllegalArgumentException("invalid module name: " + name);
      }

      result.add(name);
    }

    if (result.isEmpty()) {
      throw new IllegalArgumentException("--modules requires at least one module");
    }

    return result;
  }

  private static String value(String[] argv, int i, String option) {
    if (i >= argv.length || argv[i].isEmpty()) {
      throw new IllegalArgumentException(option + " requires a value");
    }
    return argv[i];
  }

  private static void bundle(Options opt) throws IOException {
    Path runtime = opt.runtime() == null ? currentRuntime() : opt.runtime().toAbsolutePath().normalize();

    if (!Files.isRegularFile(runtime)) {
      throw new IllegalArgumentException("runtime is not a regular file: " + runtime);
    }

    if (Files.exists(opt.output()) && Files.isSameFile(runtime, opt.output())) {
      throw new IllegalArgumentException("refusing to overwrite runtime");
    }

    Snapshot base = snapshot(runtime);
    Map<String, Path> app;
    String startup;

    if (opt.jar()) {
      String name = opt.source().getFileName().toString();

      if (name.contains("\n") || name.contains("\r")) {
        throw new IllegalArgumentException("unsafe JAR filename");
      }

      app = new TreeMap<>();
      app.put("app/" + name, opt.source());

      if (opt.mainClass() == null) {
        startup = "-jar\n/zip/app/" + name + "\n...\n";
      } else {
        String cp = "/zip/app/" + name;
        if (opt.classpath() != null) {
          app.putAll(collect(opt.classpath(), "app/lib/"));
          cp += ":/zip/app/lib/*";
        }
        startup = "-cp\n" + cp + "\n" + opt.mainClass() + "\n...\n";
      }
    } else {
      app = collect(opt.source());
      String mainPath = "app/classes/" + opt.mainClass().replace('.', '/') + ".class";

      if (!app.containsKey(mainPath)) {
        throw new IllegalArgumentException("main class not found in CLASSES_DIR: " + mainPath);
      }

      startup = "-cp\n/zip/app/classes\n" + opt.mainClass() + "\n...\n";
    }

    Map<String, byte[]> modules = opt.modules().isEmpty() ? Map.of() : selectModules(runtime, opt);

    create(runtime, opt.output(), base, app, modules, startup);

    System.out.printf("Bundled %d application file(s), %d module file(s): %s (%d bytes)%n", app.size(), modules.size(), opt.output(), Files.size(opt.output()));
  }

  private static Map<String, byte[]> selectModules(Path runtime, Options opt) throws IOException {
    Path repository = opt.moduleRepository() == null ? defaultRepository() : opt.moduleRepository();

    if (!Files.isRegularFile(repository)) {
      throw new IllegalArgumentException(
          "missing module repository: " + repository + " (pass --module-repository FILE)");
    }

    Properties base = properties(runtime, ".__javacosmofy__/runtime.properties");
    Properties profile = properties(repository, ".__javacosmofy__/module-repository.properties");

    if (!"1".equals(base.getProperty("format")) || !"1".equals(profile.getProperty("format"))
        || !base.getProperty("source_sha256", "").equals(profile.getProperty("source_sha256"))
        || !base.getProperty("patch_sha256", "").equals(profile.getProperty("patch_sha256"))) {
      throw new IllegalArgumentException("module repository does not match this java.com runtime");
    }

    Set<String> present = moduleNames(runtime);

    try (ZipFile zip = new ZipFile(repository.toFile())) {
      Set<String> available = moduleNames(zip);
      Set<String> chosen = new LinkedHashSet<>();

      if (opt.modules().contains("all")) {
        chosen.addAll(available);
      } else {
        chosen.addAll(opt.modules());
      }

      for (String name : List.copyOf(chosen)) {
        if (!available.contains(name) && !present.contains(name)) {
          throw new IllegalArgumentException("module is unavailable: " + name);
        }
      }

      List<String> queue = new ArrayList<>(chosen);
      for (int i = 0; i < queue.size(); i++) {
        String name = queue.get(i);

        if (present.contains(name)) {
          continue;
        }

        ZipEntry info = zip.getEntry("modules/" + name + "/module-info.class");

        if (info == null) {
          throw new IOException("module repository is missing module-info.class: " + name);
        }
        try (InputStream in = zip.getInputStream(info)) {
          for (ModuleDescriptor.Requires require : ModuleDescriptor.read(in).requires()) {
            String dependency = require.name();

            if (!present.contains(dependency) && chosen.add(dependency)) {
              queue.add(dependency);
            }
          }
        }
      }

      // Existing runtime modules must stay in the base central directory;
      // appending them would duplicate entries and unnecessarily bloat output.
      chosen.removeAll(present);

      Map<String, byte[]> result = new TreeMap<>();
      for (var entries = zip.entries(); entries.hasMoreElements();) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();

        if (entry.isDirectory() || !name.startsWith("modules/")) {
          continue;
        }

        int slash = name.indexOf('/', "modules/".length());

        if (slash < 0 || !chosen.contains(name.substring("modules/".length(), slash))) {
          continue;
        }

        try (InputStream in = zip.getInputStream(entry)) {
          result.put(name, in.readAllBytes());
        }
      }

      return result;
    }
  }

  private static Path defaultRepository() throws IOException {
    String configured = System.getenv("JAVACOSMOFY_MODULE_REPOSITORY");

    if (configured != null && !configured.isBlank()) {
      return Path.of(configured).toAbsolutePath().normalize();
    }

    Path self = currentRuntime();
    Path parent = self.getParent();

    if (parent != null && parent.getParent() != null) {
      return parent.getParent().resolve("modules/jdk25-cosmo.zip");
    }

    throw new IllegalArgumentException("cannot locate module repository; pass --module-repository FILE");
  }

  private static Properties properties(Path zip, String name) throws IOException {
    try (ZipFile file = new ZipFile(zip.toFile())) {
      ZipEntry entry = file.getEntry(name);

      if (entry == null) {
        throw new IOException("missing " + name + " in " + zip);
      }

      Properties result = new Properties();
      try (InputStream in = file.getInputStream(entry)) {
        result.load(in);
      }
      return result;
    }
  }

  private static Set<String> moduleNames(Path zip) throws IOException {
    try (ZipFile file = new ZipFile(zip.toFile())) {
      return moduleNames(file);
    }
  }

  private static Set<String> moduleNames(ZipFile zip) {
    Set<String> result = new LinkedHashSet<>();
    for (var entries = zip.entries(); entries.hasMoreElements();) {
      String name = entries.nextElement().getName();
      if (!name.startsWith("modules/")) {
        continue;
      }
      int slash = name.indexOf('/', "modules/".length());
      if (slash > 0) {
        result.add(name.substring("modules/".length(), slash));
      }
    }
    return result;
  }

  private static Path currentRuntime() throws IOException {
    String configured = System.getenv("JAVACOSMOFY_RUNTIME");

    if (configured != null && !configured.isBlank()) {
      return Path.of(configured).toAbsolutePath().normalize();
    }

    Path proc = Path.of("/proc/self/exe");
    if (Files.exists(proc)) {
      Path resolved = proc.toRealPath();

      if (resolved.getFileName().toString().endsWith(".com")) {
        return resolved;
      }
    }

    Path maps = Path.of("/proc/self/maps");
    if (Files.isReadable(maps)) {
      try (var lines = Files.lines(maps)) {
        Path mapped = lines
            .map(line -> {
              int index = line.lastIndexOf(" /");
              return index < 0 ? null : Path.of(line.substring(index + 1));
            })
            .filter(path -> path != null && path.getFileName().toString().endsWith(".com") && Files.isRegularFile(path))
            .findFirst().orElse(null);

        if (mapped != null) {
          return mapped;
        }
      } catch (RuntimeException ignored) {
        // Fall through to the explicit-runtime error below.
      }
    }

    throw new IllegalArgumentException("cannot discover this APE; pass --runtime FILE or set JAVACOSMOFY_RUNTIME");
  }

  private static Map<String, Path> collect(Path source) throws IOException {
    return collect(source, "app/classes/");
  }

  private static Map<String, Path> collect(Path source, String prefix) throws IOException {
    Map<String, Path> result = new TreeMap<>();
    try (var paths = Files.walk(source, FileVisitOption.FOLLOW_LINKS)) {
      for (Path p : paths.toList()) {
        if (Files.isSymbolicLink(p)) {
          throw new IllegalArgumentException("symlinks cannot be embedded: " + source.relativize(p));
        }

        if (!Files.isRegularFile(p)) {
          continue;
        }

        String relative = source.relativize(p).toString().replace('\\', '/');

        if (relative.isEmpty() || relative.startsWith("../") || relative.contains("\u0000")) {
          throw new IllegalArgumentException("unsafe source path");
        }

        result.put(prefix + relative, p);
      }
    }

    return result;
  }

  private record Eocd(int count, long offset, long size) {}
  private record Snapshot(long length, Eocd eocd, byte[] directory) {}

  private static Snapshot snapshot(Path runtime) throws IOException {
    long fileSize = Files.size(runtime);
    Eocd latest = eocd(runtime, fileSize);
    byte[] latestDirectory = read(runtime, latest.offset(), latest.size());
    Long embeddedBase = baseLength(runtime, latestDirectory, latest.count());

    long length = embeddedBase == null ? fileSize : embeddedBase;

    if (length < 1024 || length > fileSize) {
      throw new IOException("invalid embedded base length");
    }

    Eocd base = embeddedBase == null ? latest : eocd(runtime, length);
    byte[] directory = embeddedBase == null ? latestDirectory : read(runtime, base.offset(), base.size());

    if (embeddedBase == null && contains(directory, base.count(), ".args")) {
      throw new IOException("runtime already has .args; use a javacosmofy bundle with base metadata");
    }

    return new Snapshot(length, base, directory);
  }

  private static Eocd eocd(Path path, long end) throws IOException {
    int length = (int)Math.min(end, 65557L);
    byte[] tail = read(path, end - length, length);

    for (int p = tail.length - 22; p >= 0; p--) {
      if (u32(tail, p) != 0x06054b50L) {
        continue;
      }

      int comment = u16(tail, p + 20);
      if (p + 22 + comment != tail.length) {
        continue;
      }

      int disk = u16(tail, p + 4), cdDisk = u16(tail, p + 6), diskCount = u16(tail, p + 8), count = u16(tail, p + 10);
      long size = u32(tail, p + 12), offset = u32(tail, p + 16), location = end - length + p;

      if (disk != 0 || cdDisk != 0 || diskCount != count || count == 0xffff
          || size == 0xffffffffL || offset == 0xffffffffL) {
        continue;
      }

      if (offset + size > location) {
        continue;
      }

      return new Eocd(count, offset, size);
    }

    throw new IOException("runtime has no valid ZIP32 end-of-central-directory");
  }

  private static Long baseLength(Path path, byte[] directory, int count) throws IOException {
    for (Central c : central(directory, count)) {
      if (!c.name().equals(META)) {
        continue;
      }

      if (c.method() != 0 || c.size() > 4096) {
        throw new IOException("invalid javacosmofy metadata");
      }

      byte[] header = read(path, c.localOffset(), 30);
      if (u32(header, 0) != 0x04034b50L) {
        throw new IOException("invalid metadata local header");
      }

      long payload = c.localOffset() + 30L + u16(header, 26) + u16(header, 28);
      String text = new String(read(path, payload, c.size()), StandardCharsets.UTF_8);

      for (String line : text.split("\\n")) {
        if (line.startsWith("base_length=")) {
          return Long.parseLong(line.substring(12));
        }
      }

      throw new IOException("metadata has no base_length");
    }

    return null;
  }

  private record Central(String name, int method, long size, long localOffset) {}
  private static List<Central> central(byte[] cd, int count) throws IOException {
    List<Central> entries = new ArrayList<>();
    int p = 0;
    for (int i = 0; i < count; i++) {
      if (p + 46 > cd.length || u32(cd, p) != 0x02014b50L) {
        throw new IOException("invalid ZIP central directory");
      }

      int nameLen = u16(cd, p + 28), extra = u16(cd, p + 30), comment = u16(cd, p + 32), end = p + 46 + nameLen + extra + comment;
      if (end > cd.length) {
        throw new IOException("truncated ZIP central directory");
      }

      entries.add(new Central(new String(cd, p + 46, nameLen, StandardCharsets.UTF_8), u16(cd, p + 10), u32(cd, p + 20), u32(cd, p + 42)));
      p = end;
    }

    if (p != cd.length) {
      throw new IOException("unexpected ZIP central-directory data");
    }

    return entries;
  }

  private static boolean contains(byte[] cd, int count, String name) throws IOException {
    for (Central c : central(cd, count)) {
      if (c.name().equals(name)) {
        return true;
      }
    }

    return false;
  }

  private static void create(Path runtime, Path output, Snapshot base, Map<String, Path> files, Map<String, byte[]> modules, String startup) throws IOException {
    Files.createDirectories(output.getParent());
    Path temp = output.resolveSibling(output.getFileName() + ".tmp");

    Map<String, byte[]> generated = new TreeMap<>();
    generated.put(".args", startup.getBytes(StandardCharsets.UTF_8));
    generated.put(META, ("format=1\nbase_length=" + base.length() + "\n").getBytes(StandardCharsets.UTF_8));
    generated.putAll(modules);

    try (FileChannel in = FileChannel.open(runtime, StandardOpenOption.READ);
         FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE,
             StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
      copy(in, out, base.length());

      ByteArrayOutputStream addedCd = new ByteArrayOutputStream();
      int added = 0;
      for (var entry : generated.entrySet()) {
        addedCd.writeBytes(addBytes(out, entry.getKey(), entry.getValue()));
        added++;
      }

      for (var entry : files.entrySet()) {
        addedCd.writeBytes(addFile(out, entry.getKey(), entry.getValue()));
        added++;
      }

      long cdOffset = out.position();
      long cdSize = base.directory().length + addedCd.size();
      int total = base.eocd().count() + added;
      if (total > 65534 || cdOffset + cdSize >= 0xffffffffL || cdSize >= 0xffffffffL) {
        throw new IOException("output exceeds ZIP32 limits");
      }
      out.write(ByteBuffer.wrap(base.directory()));
      out.write(ByteBuffer.wrap(addedCd.toByteArray()));

      out.write(le(22).putInt(0x06054b50).putShort((short)0).putShort((short)0).putShort((short)total).putShort((short)total).putInt((int)cdSize).putInt((int)cdOffset).putShort((short)0).flip());
    } catch (IOException e) {
      Files.deleteIfExists(temp);
      throw e;
    }

    Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    output.toFile().setExecutable(true, false);
  }

  private static byte[] addBytes(FileChannel out, String name, byte[] data) throws IOException {
    CRC32 crc = new CRC32();
    crc.update(data);

    long offset = out.position();
    writeLocal(out, name, data.length, crc.getValue());
    out.write(ByteBuffer.wrap(data));

    return central(name, data.length, crc.getValue(), offset);
  }

  private static byte[] addFile(FileChannel out, String name, Path path) throws IOException {
    long size = Files.size(path);
    if (size >= 0xffffffffL) {
      throw new IOException("file exceeds ZIP32: " + name);
    }

    CRC32 crc = new CRC32();
    try (var in = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];

      for (int count; (count = in.read(buffer)) >= 0;) {
        crc.update(buffer, 0, count);
      }
    }

    long offset = out.position();
    writeLocal(out, name, size, crc.getValue());

    try (FileChannel in = FileChannel.open(path, StandardOpenOption.READ)) {
      copy(in, out, size);
    }

    return central(name, size, crc.getValue(), offset);
  }

  private static void writeLocal(FileChannel out, String name, long size, long crc) throws IOException {
    byte[] encodedName = name.getBytes(StandardCharsets.UTF_8);
    if (encodedName.length > 65535 || out.position() >= 0xffffffffL) {
      throw new IOException("ZIP32 limit: " + name);
    }

    ByteBuffer header = le(30)
        .putInt(0x04034b50)
        .putShort((short)20)
        .putShort((short)0)
        .putShort((short)0)
        .putShort((short)0)
        .putShort((short)33)
        .putInt((int)crc)
        .putInt((int)size)
        .putInt((int)size)
        .putShort((short)encodedName.length)
        .putShort((short)0)
        .flip();
    out.write(header);
    out.write(ByteBuffer.wrap(encodedName));
  }

  private static byte[] central(String name, long size, long crc, long offset) {
    byte[] encodedName = name.getBytes(StandardCharsets.UTF_8);
    ByteBuffer entry = le(46 + encodedName.length);
    entry.putInt(0x02014b50)
        .putShort((short)20)
        .putShort((short)20)
        .putShort((short)0)
        .putShort((short)0)
        .putShort((short)0)
        .putShort((short)33)
        .putInt((int)crc)
        .putInt((int)size)
        .putInt((int)size)
        .putShort((short)encodedName.length)
        .putShort((short)0)
        .putShort((short)0)
        .putShort((short)0)
        .putShort((short)0)
        .putInt(0)
        .putInt((int)offset)
        .put(encodedName);

    return entry.array();
  }

  private static ByteBuffer le(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static void copy(FileChannel in, FileChannel out, long length) throws IOException {
    for (long copied = 0; copied < length;) {
      long count = in.transferTo(copied, length - copied, out);
      if (count <= 0) {
        throw new IOException("short read while bundling");
      }

      copied += count;
    }
  }

  private static byte[] read(Path path, long offset, long length) throws IOException {
    if (length > Integer.MAX_VALUE) {
      throw new IOException("ZIP section too large");
    }

    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      ByteBuffer buffer = ByteBuffer.allocate((int)length);

      while (buffer.hasRemaining()) {
        if (channel.read(buffer, offset + buffer.position()) < 0) {
          throw new IOException("unexpected end of ZIP");
        }
      }

      return buffer.array();
    }
  }

  private static int u16(byte[] bytes, int position) {
    return (bytes[position] & 255) | ((bytes[position + 1] & 255) << 8);
  }

  private static long u32(byte[] bytes, int position) {
    return Integer.toUnsignedLong((bytes[position] & 255)
        | ((bytes[position + 1] & 255) << 8)
        | ((bytes[position + 2] & 255) << 16)
        | ((bytes[position + 3] & 255) << 24));
  }
}
