# UpJasperReport6To7

`UpJasperReport6To7` is a command-line helper for converting legacy JasperReports 6.x-style `.jrxml` files into JasperReports 7.x-compatible `.jasper` files.

The tool is designed for batch migration work:

1. Recursively scan a directory for `*.jrxml` files.
2. Transform legacy JRXML structure in memory.
3. Load the transformed XML with JasperReports 7.
4. Compile it into `.jasper` bytes.
5. Load the compiled `.jasper` again as a round-trip verification.
6. Optionally write the generated `.jasper` files either to an output directory or in place next to the source `.jrxml` files.
7. Write a JSON report summarizing the batch result.

The original `.jrxml` files are treated as read-only input. The tool does not rewrite them.

---

## Features

- Batch scan for `*.jrxml` under a source directory.
- Safe dry-run mode by default.
- Optional in-place `.jasper` regeneration.
- Optional mirror output directory for generated `.jasper` files.
- JSON report output.
- DOM-based transformer instead of regex-only XML rewriting.
- Legacy JRXML namespace cleanup.
- Common JR6 → JR7 element / attribute conversion.
- Dataset and query conversion.
- CDATA preservation for expression nodes.
- Barcode component support for JasperReports 7 Jackson XML loading.
- Round-trip verification using `JRLoader`.

---

## Requirements

- JDK 25, or another JDK compatible with the project build.
- Maven.
- Network or local Maven repository access for JasperReports dependencies.

Recommended check:

```bash
java -version
mvn -version
```

---

## Build

Compile the project:

```bash
mvn -q -DskipTests compile
```

Build the dependency classpath file:

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
```

The generated `cp.txt` is used by the recommended runtime command.

---

## Recommended execution mode

Use exploded classpath execution:

```bash
java -cp "target/classes;$(cat cp.txt)" com.dsic.upjasperreport6to7.UpJasperReport6To7Application
```

On Windows PowerShell, use:

```powershell
$cp = Get-Content cp.txt -Raw
java -cp "target/classes;$cp" com.dsic.upjasperreport6to7.UpJasperReport6To7Application
```

Why exploded classpath is recommended:

- JasperReports may compile report expressions with the JDK compiler.
- When using a Spring Boot fat jar, nested dependency jars may not be visible to that compiler as normal file-system classpath entries.
- Exploded classpath avoids that issue.

---

## Configuration

The tool uses Spring Boot properties with the `upj.*` prefix.

Default configuration is in:

```text
src/main/resources/application.properties
```

Available properties:

| Property | Default | Description |
| --- | --- | --- |
| `upj.source-dir` | `../LANDWEBAP/src/main/webapp/WEB-INF` | Directory to scan recursively for `.jrxml` files. Override this for your own reports. |
| `upj.out-dir` | `out-jasper` | Output directory used when `upj.in-place=false` and `upj.dry-run=false`. |
| `upj.in-place` | `false` | If `true`, generated `.jasper` files replace sibling `.jasper` files next to each `.jrxml`. |
| `upj.dry-run` | `true` | If `true`, verify only and write no `.jasper` files. |
| `upj.report-file` | `recompile-report.json` | JSON report output path. |
| `upj.auto-run` | `true` | If `false`, starts Spring context without running the batch. |

All properties can be overridden from the command line.

---

## Usage examples

### 1. Dry-run a report directory

Dry-run is the safest first step. It verifies transform, JR7 load, compile, and round-trip load without writing `.jasper` files.

```bash
java -cp "target/classes;$(cat cp.txt)" \
  com.dsic.upjasperreport6to7.UpJasperReport6To7Application \
  --upj.source-dir="/path/to/reports" \
  --upj.dry-run=true \
  --upj.report-file="recompile-report.json"
```

Expected successful summary:

```text
==== SUMMARY: total=N ok=N fail=0 dryRun=true report=... ====
```

### 2. Generate `.jasper` files to an output directory

This keeps source directories untouched and writes generated files into a mirror output tree.

```bash
java -cp "target/classes;$(cat cp.txt)" \
  com.dsic.upjasperreport6to7.UpJasperReport6To7Application \
  --upj.source-dir="/path/to/reports" \
  --upj.out-dir="out-jasper" \
  --upj.in-place=false \
  --upj.dry-run=false \
  --upj.report-file="recompile-report.json"
```

Example:

```text
/path/to/reports/a/b/sample.jrxml
```

becomes:

```text
out-jasper/a/b/sample.jasper
```

### 3. Regenerate `.jasper` files in place

Use this only after dry-run passes.

```bash
java -cp "target/classes;$(cat cp.txt)" \
  com.dsic.upjasperreport6to7.UpJasperReport6To7Application \
  --upj.source-dir="/path/to/reports" \
  --upj.in-place=true \
  --upj.dry-run=false \
  --upj.report-file="recompile-report.json"
```

For each input:

```text
sample.jrxml
```

it writes or replaces:

```text
sample.jasper
```

The `.jrxml` file itself is not rewritten.

### 4. Start the application without running the batch

```bash
java -cp "target/classes;$(cat cp.txt)" \
  com.dsic.upjasperreport6to7.UpJasperReport6To7Application \
  --upj.auto-run=false
```

---

## JSON report format

The report file is written to `upj.report-file`.

Top-level fields:

| Field | Description |
| --- | --- |
| `total` | Number of `.jrxml` files discovered. |
| `ok` | Number of files successfully transformed, loaded, compiled, and verified. |
| `failed` | Number of failed files. |
| `failures` | List of failure details. Empty when all files pass. |
| `jasperOut` | Configured output directory. |
| `written` | `true` when `dry-run=false`; otherwise `false`. |

Failure item fields:

| Field | Description |
| --- | --- |
| `path` | Relative path of the failed `.jrxml`. |
| `ok` | Always `false` for failure entries. |
| `jasperBytes` | Generated byte count. Usually `0` on failure. |
| `ms` | Time spent on that file. |
| `stage` | Approximate failure stage, such as `load`, `load(jackson)`, or `compile`. |
| `error` | Error message. |

---

## Transformation notes

The transformer is implemented in `LegacyJrxmlTransformer`.

Important conversions include:

- Remove or normalize legacy JRXML namespace declarations.
- Convert `subDataset` to `dataset`.
- Convert `queryString` to `query`.
- Convert legacy band / frame / element structures into JR7-friendly element structures.
- Hoist `reportElement` attributes into JR7 `element` attributes.
- Preserve style-related children such as `box`, `textElement`, `font`, and `paragraph`.
- Convert common legacy boolean attributes from `isXxx` to `xxx`.
- Preserve CDATA content in raw cloned XML nodes.

---

## CDATA preservation

Some JasperReports expressions are commonly stored as CDATA, for example:

```xml
<printWhenExpression><![CDATA[$F{value} > 0]]></printWhenExpression>
```

The transformer preserves CDATA sections when cloning raw XML nodes. This is important for expression fidelity because silently changing an expression node into an empty node can change report behavior.

CDATA preservation applies to cases such as:

- `printWhenExpression`
- `codeExpression`
- `filterExpression`
- other raw-cloned expression nodes

---

## Barcode component support

JasperReports 7 uses Jackson mapping for XML component deserialization. Some legacy barcode components require explicit mapping to load correctly.

The project includes:

```text
src/main/resources/jasperreports_extension.properties
```

This registers Jackson mappings for common Barbecue barcode component types, such as:

- `Code39`
- `Code128`
- `EAN13`
- `EAN8`
- `UPCA`
- `UPCE`
- `Codabar`
- `PDF417`

The transformer also maps legacy barcode attributes to JR7-compatible properties:

| Legacy attribute | JR7-compatible attribute |
| --- | --- |
| `moduleWidth` | `barWidth` |
| `textPosition="none"` | `drawText="false"` |
| other `textPosition` values | `drawText="true"` |

Quiet-zone style attributes that are not accepted by the JR7 component model are removed during transformation.

---

## Recommended workflow

1. Build the project.
2. Build `cp.txt`.
3. Run dry-run against the target report directory.
4. Inspect `recompile-report.json`.
5. Fix unsupported legacy patterns if any failures remain.
6. Re-run dry-run until `failed=0`.
7. Run with `--upj.dry-run=false`.
8. Prefer output directory mode first.
9. Use in-place mode only after verification.
10. Validate generated `.jasper` files in the target runtime.

---

## Troubleshooting

### `InvalidTypeIdException` for barcode components

Make sure `src/main/resources/jasperreports_extension.properties` is present on the runtime classpath and that the Barbecue-related JasperReports dependency is available.

### `Unrecognized field` during JR7 XML load

This usually means a legacy attribute was copied into a JR7 component that no longer accepts it. Add a transformer rule to rename or remove that attribute.

### Expression compile errors when using `java -jar`

Use exploded classpath execution instead:

```bash
java -cp "target/classes;$(cat cp.txt)" com.dsic.upjasperreport6to7.UpJasperReport6To7Application
```

### `.jrxml` source files changed unexpectedly

The tool should not write `.jrxml` files. Check any external formatter or editor. The tool only writes `.jasper` when `upj.dry-run=false`.

---

## Current status

The core migration workflow is implemented:

- Dry-run validation.
- Output-directory generation.
- In-place `.jasper` regeneration.
- JSON reporting.
- CDATA preservation.
- Barcode component mapping.
- Legacy barcode attribute conversion.

Open future improvements should be tracked in separate issues.
