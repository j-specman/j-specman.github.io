import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds the release history of the Specman website: one HTML page per release plus an index page
 * listing all releases, newest first. The sources are the Markdown release notes
 * ({@code release-notes-<version>.md}), the conversion to HTML is done by pandoc in a Docker container.
 *
 * <h2>Why this is a single source file without a build step</h2>
 * This class is NOT compiled by Maven and there is no class file. It is started directly from its source:
 * <pre>java tools/ReleaseHistory.java releasenotes _site/releasenotes releasenotes-theme/template.html pandoc/core:3.8</pre>
 * Since Java 11 (JEP 330) the {@code java} launcher can compile a single source file in memory and run its
 * first class right away. This saves the website repository a Java source tree, a compiler plugin and a jar,
 * for a program of this size. The price is a restriction that a Java developer might stumble over:
 * <ul>
 *   <li>Everything must be in this one file. With JDK 21 the launcher does not see any other source file
 *       (a multi-file source launch only exists since JDK 22). That is why the program consists of static
 *       methods in one class instead of several small classes.</li>
 *   <li>No libraries. There is no classpath with Maven dependencies, only the JDK is available.</li>
 *   <li>The file is compiled on every start. That takes a moment, which is negligible here.</li>
 * </ul>
 * Maven starts the program with the exec-maven-plugin, see the {@code release-history} execution in pom.xml.
 *
 * <h2>Arguments</h2>
 * <ol>
 *   <li>Directory with the release notes</li>
 *   <li>Output directory, created if missing. Existing files with the same name are overwritten.</li>
 *   <li>pandoc HTML template</li>
 *   <li>Docker image providing pandoc, e.g. {@code pandoc/core:3.8}</li>
 * </ol>
 * All paths must be relative to the working directory, which is mounted into the Docker container.
 * Docker is the only tool required, pandoc itself doesn't have to be installed.
 */
public class ReleaseHistory {
  private static final String NOTES_PREFIX = "release-notes-";
  private static final String NOTES_SUFFIX = ".md";
  private static final String USAGE =
      "Usage: java ReleaseHistory.java <notes-dir> <output-dir> <template> <pandoc-docker-image>";

  public static void main(String[] args) {
    if (args.length != 4) {
      System.err.println(USAGE);
      System.exit(2);
    }
    try {
      buildHistory(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), args[3]);
    }
    catch (IOException | InterruptedException | IllegalArgumentException e) {
      System.err.println("Release history failed: " + e.getMessage());
      System.exit(1);
    }
  }

  private static void buildHistory(Path notesDir, Path outDir, Path template, String pandocImage)
      throws IOException, InterruptedException {
    requireRelative(notesDir);
    requireRelative(outDir);
    requireRelative(template);
    List<Path> notes = listNotesNewestFirst(notesDir);
    Files.createDirectories(outDir);
    for (Path note : notes) {
      String title = "Specman " + versionOf(note) + " release notes";
      convertToHtml(note, outDir.resolve(htmlFileName(note)), title, template, pandocImage);
    }
    writeIndex(notes, outDir, template, pandocImage);
  }

  private static void writeIndex(List<Path> notes, Path outDir, Path template, String pandocImage)
      throws IOException, InterruptedException {
    Path indexMarkdown = outDir.resolve("index.md");
    Files.writeString(indexMarkdown, indexMarkdown(notes), StandardCharsets.UTF_8);
    convertToHtml(indexMarkdown, outDir.resolve("index.html"), "Specman release history", template, pandocImage);
    Files.delete(indexMarkdown);
  }

  // Level 2 on purpose: the conversion shifts headings up by one level, so this becomes the page heading (h1)
  private static String indexMarkdown(List<Path> notes) {
    StringBuilder markdown = new StringBuilder("## Release history\n\n");
    for (Path note : notes) {
      markdown.append("- [Specman ").append(versionOf(note)).append("](").append(htmlFileName(note)).append(")\n");
    }
    return markdown.toString();
  }

  private static List<Path> listNotesNewestFirst(Path notesDir) throws IOException {
    if (!Files.isDirectory(notesDir)) {
      throw new IllegalArgumentException("Release notes directory not found: " + notesDir);
    }
    List<Path> notes;
    try (Stream<Path> files = Files.list(notesDir)) {
      notes = files.filter(ReleaseHistory::isReleaseNotes).collect(Collectors.toCollection(ArrayList::new));
    }
    if (notes.isEmpty()) {
      throw new IllegalArgumentException("No " + NOTES_PREFIX + "*" + NOTES_SUFFIX + " found in " + notesDir);
    }
    for (Path note : notes) {
      requireNumericVersion(note);
    }
    notes.sort((first, second) -> compareVersions(versionOf(second), versionOf(first)));
    return notes;
  }

  // Also needed for a single file, where the sort below never compares anything
  private static void requireNumericVersion(Path notes) {
    versionNumbers(versionOf(notes));
  }

  private static boolean isReleaseNotes(Path file) {
    String name = file.getFileName().toString();
    return Files.isRegularFile(file) && name.startsWith(NOTES_PREFIX) && name.endsWith(NOTES_SUFFIX);
  }

  private static String versionOf(Path notes) {
    String name = notes.getFileName().toString();
    return name.substring(NOTES_PREFIX.length(), name.length() - NOTES_SUFFIX.length());
  }

  private static String htmlFileName(Path notes) {
    String name = notes.getFileName().toString();
    return name.substring(0, name.length() - NOTES_SUFFIX.length()) + ".html";
  }

  // Compares numerically per segment, a plain string comparison would sort 1.10.0 before 1.9.0
  private static int compareVersions(String first, String second) {
    int[] firstNumbers = versionNumbers(first);
    int[] secondNumbers = versionNumbers(second);
    int segments = Math.max(firstNumbers.length, secondNumbers.length);
    for (int i = 0; i < segments; i++) {
      int result = Integer.compare(numberAt(firstNumbers, i), numberAt(secondNumbers, i));
      if (result != 0) {
        return result;
      }
    }
    return 0;
  }

  private static int numberAt(int[] numbers, int index) {
    return index < numbers.length ? numbers[index] : 0;
  }

  private static int[] versionNumbers(String version) {
    try {
      return Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
    }
    catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "The version in the name of " + NOTES_PREFIX + version + NOTES_SUFFIX + " must consist of numbers separated by dots");
    }
  }

  private static void convertToHtml(Path source, Path target, String pageTitle, Path template, String pandocImage)
      throws IOException, InterruptedException {
    List<String> command = List.of(
        "docker", "run", "--rm",
        "-v", Path.of("").toAbsolutePath() + ":/data",
        "-w", "/data",
        pandocImage,
        "-f", "gfm", "-t", "html5",
        "--template", containerPath(template),
        "--shift-heading-level-by=-1",
        "--metadata", "pagetitle=" + pageTitle,
        "-o", containerPath(target),
        containerPath(source));
    run(command);
  }

  // The container is a Linux, so paths need forward slashes even if this program runs on Windows
  private static String containerPath(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static void run(List<String> command) throws IOException, InterruptedException {
    Process process;
    try {
      process = new ProcessBuilder(command).inheritIO().start();
    }
    catch (IOException e) {
      throw new IOException("Cannot start " + command.get(0) + ". Is Docker installed and running? (" + e.getMessage() + ")");
    }
    if (process.waitFor() != 0) {
      throw new IOException("Command failed, see output above: " + String.join(" ", command));
    }
  }

  private static void requireRelative(Path path) {
    if (path.isAbsolute()) {
      throw new IllegalArgumentException(
          "Paths must be relative to the working directory because it is mounted into the Docker container: " + path);
    }
  }
}
