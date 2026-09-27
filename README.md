# javacosmofy

`javacosmofy` packages compiled Java class trees as a self-contained
Cosmopolitan Java APE. It is itself a `java.com` plus `/zip/.args`; no updater,
host `zip`, or Java compiler is needed after the initial bootstrap.

## Bootstrap

With a superconfigure checkout that has already built `java.com`:

```sh
./scripts/bootstrap.sh /path/to/superconfigure/results/bin/java.com
JAVACOSMOFY_JAVAC=/path/to/superconfigure/build-tools/openjdk25-boot/bin/javac \
  ./scripts/verify-native.sh
```

The bootstrap JDK is only used to compile javacosmofy's source. The resulting
`dist/javacosmofy.com` runs its own `bundle` command and writes ZIP32 entries
using Java APIs.

## Example

```sh
/path/to/superconfigure/build-tools/openjdk25-boot/bin/javac \
  -d .build/hello examples/hello/src/example/Hello.java
./dist/javacosmofy.com bundle .build/hello --main example.Hello -o dist/hello.com
./dist/hello.com 'two words'
# Hello from embedded Java: two words
```

The generated APE carries this native startup configuration:

```text
-cp
/zip/app/classes
example.Hello
...
```

`...` forwards caller arguments to the Java main class. Rebundling a generated
`javacosmofy.com` uses its embedded base-runtime metadata, so old app payloads
are not accumulated.

## More examples

- [`examples/cfr`](examples/cfr/README.md) packages the CFR Java decompiler.
- [`examples/picocli`](examples/picocli/README.md) builds and packages a
  Picocli checksum application.
- [`examples/jadx`](examples/jadx/README.md) packages the JADX Android CLI.
- [`examples/tika`](examples/tika/README.md) packages the complete Apache Tika CLI.
- [`examples/h2`](examples/h2/README.md) packages the H2 SQL Shell.
- [`examples/awt`](examples/awt/README.md) tests an on-demand desktop module.

## Runnable JAR distributions

Use `--jar` for a self-contained runnable JAR. Override its manifest main class
with `--main` when a distribution defaults to a GUI. For a thin launcher plus a
distribution `lib/` directory, embed both with its CLI main class:

```sh
./dist/javacosmofy.com bundle app.jar --jar --classpath lib --main example.Main -o app.com
```

## Optional JDK modules

`java.com` is intentionally minimal. A matching superconfigure build writes
`results/libexec/java-modules.zip`, containing all compiled Java modules but
not embedding them in the runtime. Install that asset beside javacosmofy:

```sh
./scripts/install-module-repository.sh /path/to/results/libexec/java-modules.zip
```

Then add only the modules an application needs. `javacosmofy` reads each
module descriptor and adds its required-module closure automatically:

```sh
./dist/javacosmofy.com bundle app.jar --jar --main example.Main \
  --modules java.desktop -o app.com
```

The repository and `java.com` carry matching source/patch fingerprints; a
mismatch is rejected. Module classes/resources can be appended this way, but
native functionality still depends on code linked into the base runtime.
