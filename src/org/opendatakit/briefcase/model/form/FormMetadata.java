package org.opendatakit.briefcase.model.form;

import static org.opendatakit.briefcase.model.form.AsJson.getJson;
import static org.opendatakit.briefcase.util.Host.isWindows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.opendatakit.briefcase.export.XmlElement;
import org.opendatakit.briefcase.pull.aggregate.Cursor;
import org.opendatakit.briefcase.reused.BriefcaseException;

public class FormMetadata implements AsJson {
  private final FormKey key;
  private final Path storageRoot;
  private final Path formDir;
  private final boolean hasBeenPulled;
  private final Cursor cursor;
  private final Optional<SubmissionExportMetadata> lastExportedSubmission;
  /**
   * The form versions of the submissions associated with this form version. Briefcase only keeps track of the currently-published form version. However,
   * there could be form submissions that point to previous form versions.
   */
  private final Set<String> submissionVersions;

  public FormMetadata(FormKey key, Path storageRoot, Path formDir, boolean hasBeenPulled, Cursor cursor, Optional<SubmissionExportMetadata> lastExportedSubmission, Set<String> submissionVersions) {
    this.key = key;
    this.storageRoot = storageRoot;
    this.formDir = formDir.isAbsolute() ? storageRoot.relativize(formDir) : formDir;
    this.hasBeenPulled = hasBeenPulled;
    this.cursor = cursor;
    this.lastExportedSubmission = lastExportedSubmission;
    this.submissionVersions = submissionVersions;
  }

  public static FormMetadata of(FormKey key, Path storageRoot, Path formDir) {
    return new FormMetadata(
        key,
        storageRoot,
        formDir,
        false, Cursor.empty(), Optional.empty(), new HashSet<>());
  }

  public static FormMetadata from(Path storageRoot, Path formFile) {
    XmlElement root = XmlElement.from(formFile);
    assert root.getName().equals("html");
    String name = root.findElements("head", "title").get(0)
        .maybeValue()
        .orElseThrow(BriefcaseException::new);
    XmlElement mainInstance = root.findElements("head", "model", "instance").stream()
        .filter(FormMetadata::isTheMainInstance)
        .findFirst()
        .orElseThrow(BriefcaseException::new);
    String id = mainInstance.childrenOf().get(0).getAttributeValue("id").orElseThrow(BriefcaseException::new);
    FormKey key = FormKey.of(name, id);
    return new FormMetadata(key, storageRoot, formFile.getParent(), true, Cursor.empty(), Optional.empty(), new HashSet<>());
  }

  public static FormMetadata from(Path storageRoot, JsonNode root) {
    Set<String> submissionVersions = new HashSet<>();
    if (root.has("submissionVersions")) {
      root.withArray("submissionVersions").elements().forEachRemaining(e -> submissionVersions.add(e.asText()));
    }

    return new FormMetadata(
        FormKey.from(root.get("key")),
        storageRoot,
        getJson(root, "formDir").map(JsonNode::asText).map(Paths::get).orElseThrow(BriefcaseException::new),
        getJson(root, "hasBeenPulled").map(JsonNode::asBoolean).orElseThrow(BriefcaseException::new),
        Cursor.from(root.get("cursor")),
        getJson(root, "lastExportedSubmission").map(SubmissionExportMetadata::from),
        submissionVersions
    );
  }

  private static boolean isTheMainInstance(XmlElement e) {
    return !e.hasAttribute("id") // It's not a secondary instance
        && e.childrenOf().size() == 1 // Has only one child (sanity check: an <instance> with more than one children is probably illegal)
        && e.childrenOf().get(0).hasAttribute("id"); // The only child has an id (sanity check: we can't handle forms without form id)
  }

  public FormKey getKey() {
    return key;
  }

  public Path getFormDir() {
    return storageRoot.resolve(formDir);
  }

  public boolean hasBeenPulled() {
    return hasBeenPulled;
  }

  public Cursor getCursor() {
    return cursor;
  }

  public Optional<SubmissionExportMetadata> getLastExportedSubmission() {
    return lastExportedSubmission;
  }

  public Set<String> getSubmissionVersions() {
    return submissionVersions;
  }

  FormMetadata withCursor(Cursor cursor) {
    return new FormMetadata(key, storageRoot, formDir, hasBeenPulled, cursor, lastExportedSubmission, submissionVersions);
  }

  public FormMetadata withoutCursor() {
    return new FormMetadata(key, storageRoot, formDir, hasBeenPulled, Cursor.empty(), lastExportedSubmission, submissionVersions);
  }

  FormMetadata withSubmissionVersions(Set<String> submissionVersions) {
    Set<String> updatedSubmissionVersions = new HashSet<>(submissionVersions);
    updatedSubmissionVersions.addAll(this.submissionVersions);

    return new FormMetadata(key, storageRoot, formDir, hasBeenPulled, cursor, lastExportedSubmission, updatedSubmissionVersions);
  }

  FormMetadata withHasBeenPulled(boolean hasBeenPulled, Set<String> submissionVersions) {
    Set<String> updatedSubmissionVersions = new HashSet<>(submissionVersions);
    updatedSubmissionVersions.addAll(this.submissionVersions);

    return new FormMetadata(key, storageRoot, formDir, hasBeenPulled, cursor, lastExportedSubmission, updatedSubmissionVersions);
  }

  FormMetadata withLastExportedSubmission(String instanceId, OffsetDateTime submissionDate, OffsetDateTime exportDateTime) {
    return new FormMetadata(key, storageRoot, formDir, hasBeenPulled, cursor, Optional.of(new SubmissionExportMetadata(instanceId, submissionDate, exportDateTime)), submissionVersions);
  }

  @Override
  public ObjectNode asJson(ObjectMapper mapper) {
    String portableFormDir = isWindows()
        ? formDir.toString().replace("\\", "/")
        : formDir.toString();
    ObjectNode root = mapper.createObjectNode();
    root.putObject("key").setAll(key.asJson(mapper));
    root.put("formDir", portableFormDir);
    root.put("hasBeenPulled", hasBeenPulled);
    root.putObject("cursor").setAll(cursor.asJson(mapper));
    lastExportedSubmission.ifPresent(o -> root.putObject("lastExportedSubmission").setAll(o.asJson(mapper)));
    root.putArray("submissionVersions").addAll((ArrayNode) mapper.valueToTree(submissionVersions));

    return root;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    FormMetadata that = (FormMetadata) o;
    return hasBeenPulled == that.hasBeenPulled &&
        Objects.equals(key, that.key) &&
        Objects.equals(storageRoot, that.storageRoot) &&
        Objects.equals(formDir, that.formDir) &&
        Objects.equals(cursor, that.cursor) &&
        Objects.equals(lastExportedSubmission, that.lastExportedSubmission);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, storageRoot, formDir, hasBeenPulled, cursor, lastExportedSubmission);
  }

  @Override
  public String toString() {
    return "FormMetadata{" +
        "key=" + key +
        ", storageRoot=" + storageRoot +
        ", formDir=" + formDir +
        ", hasBeenPulled=" + hasBeenPulled +
        ", cursor=" + cursor +
        ", lastExportedSubmission=" + lastExportedSubmission +
        '}';
  }
}
